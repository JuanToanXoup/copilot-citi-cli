"""Agent-to-Agent Communication Protocol: Orchestrator <-> Worker agents.

This module implements a multi-agent orchestrator pattern with **two transport
modes** for agent-to-agent communication:

1. **MCP transport** (default) — Each worker runs as an MCP server process.
   The orchestrator connects to them using the standard MCP protocol
   (``tools/call`` with ``execute_task``).  This follows the emerging MCP
   agent-to-agent pattern where agents are discoverable MCP servers.

2. **Queue transport** (in-process) — Workers run as threads with
   ``queue.Queue`` message passing.  Simpler, lower overhead, but limited
   to a single process.

MCP Transport Architecture
--------------------------
::

    Orchestrator (MCP Client)
        |
        |-- MCPServer("coder")  --stdio-->  MCPAgentServer(CopilotClient)
        |-- MCPServer("reviewer") --stdio-->  MCPAgentServer(CopilotClient)
        +-- MCPServer("tester")  --stdio-->  MCPAgentServer(CopilotClient)

    Each worker is a child process running ``mcp_agent.py`` that:
    - Exposes ``execute_task``, ``get_status``, ``get_capabilities`` as MCP tools
    - Internally manages its own CopilotClient session
    - Communicates via MCP stdio transport (newline-delimited JSON-RPC)

Queue Transport Architecture
----------------------------
::

    Orchestrator
        |
        +-- queue --> WorkerAgent (thread, CopilotClient)
        +-- queue --> WorkerAgent (thread, CopilotClient)
        +-- queue --> WorkerAgent (thread, CopilotClient)

Usage
-----
::

    from copilot_cli.orchestrator import Orchestrator, WorkerConfig

    workers = [
        WorkerConfig(role="coder", system_prompt="..."),
        WorkerConfig(role="reviewer", system_prompt="...",
                     tools_enabled=["read_file", "grep_search"]),
    ]

    # MCP transport (default) — workers are MCP server processes
    orch = Orchestrator(workspace="/my/project", workers=workers,
                        transport="mcp")

    # Queue transport — workers are in-process threads
    orch = Orchestrator(workspace="/my/project", workers=workers,
                        transport="queue")

    orch.start()
    results = orch.run("Fix the login bug and add tests")
    orch.stop()
"""

from __future__ import annotations

import dataclasses
import json
import os
import queue
import sys
import threading
import time
import uuid
from typing import Callable

from copilot_cli.client import CopilotClient, _init_client, release_client
from copilot_cli.platform_utils import path_to_file_uri


# ── Message types (used by queue transport) ───────────────────────────────────

MSG_TASK_ASSIGN = "task_assign"
MSG_TASK_RESULT = "task_result"
MSG_TASK_PROGRESS = "task_progress"
MSG_SHUTDOWN = "shutdown"


def _msg_task_assign(task_id: str, worker_id: str, prompt: str,
                     context: dict | None = None) -> dict:
    return {
        "type": MSG_TASK_ASSIGN,
        "task_id": task_id,
        "worker_id": worker_id,
        "prompt": prompt,
        "context": context or {},
    }


def _msg_task_result(task_id: str, worker_id: str, status: str,
                     result: str, agent_rounds: list | None = None) -> dict:
    return {
        "type": MSG_TASK_RESULT,
        "task_id": task_id,
        "worker_id": worker_id,
        "status": status,
        "result": result,
        "agent_rounds": agent_rounds or [],
    }


def _msg_task_progress(task_id: str, worker_id: str, message: str) -> dict:
    return {
        "type": MSG_TASK_PROGRESS,
        "task_id": task_id,
        "worker_id": worker_id,
        "message": message,
    }


def _msg_shutdown() -> dict:
    return {"type": MSG_SHUTDOWN}


# ── Worker Config ─────────────────────────────────────────────────────────────

@dataclasses.dataclass
class WorkerConfig:
    """Configuration for a single worker agent.

    Per-worker overrides for ``workspace_root``, ``proxy_url``,
    ``no_ssl_verify``, ``mcp_servers``, and ``lsp_servers`` default to
    ``None`` which signals "inherit from the orchestrator".
    """
    role: str                          # Short identifier (e.g. "bug_fixer")
    system_prompt: str = ""            # Injected as <system_instructions> in first turn
    model: str | None = None           # Model override (None = server default)
    tools_enabled: list[str] | str = "__ALL__"  # "__ALL__" or list of tool names
    agent_mode: bool = True            # True for agent mode, False for chat-only
    workspace_root: str | None = None  # Per-worker workspace (None = inherit)
    proxy_url: str | None = None       # Per-worker proxy URL (None = inherit)
    no_ssl_verify: bool | None = None  # Per-worker SSL bypass (None = inherit)
    mcp_servers: dict | None = None    # Per-worker MCP config (None = inherit)
    lsp_servers: dict | None = None    # Per-worker LSP config (None = inherit)


# ── MCP Worker (each worker is an MCP server process) ─────────────────────────

class MCPWorker:
    """Manages a worker agent running as an MCP server child process.

    Uses the existing ``MCPServer`` class from ``copilot_cli.mcp`` to
    communicate with the child process via MCP stdio transport.  The child
    process runs ``mcp_agent.py`` which exposes ``execute_task``,
    ``get_status``, and ``get_capabilities`` as MCP tools.
    """

    def __init__(self, config: WorkerConfig, workspace: str,
                 proxy_url: str | None = None,
                 no_ssl_verify: bool = False,
                 mcp_config: dict | None = None,
                 lsp_config: dict | None = None):
        self.config = config
        # Per-worker overrides take precedence over orchestrator values
        self.workspace = config.workspace_root or workspace
        self.proxy_url = config.proxy_url if config.proxy_url is not None else proxy_url
        self.no_ssl_verify = config.no_ssl_verify if config.no_ssl_verify is not None else no_ssl_verify
        self.mcp_config = config.mcp_servers if config.mcp_servers is not None else mcp_config
        self.lsp_config = config.lsp_servers if config.lsp_servers is not None else lsp_config
        self._mcp_server = None

    def start(self):
        """Spawn the MCP agent server as a child process."""
        from copilot_cli.mcp import MCPServer

        # Build the config JSON that mcp_agent.py expects
        cfg = {
            "role": self.config.role,
            "name": f"{self.config.role} Agent",
            "system_prompt": self.config.system_prompt,
            "model": self.config.model,
            "tools_enabled": self.config.tools_enabled,
            "agent_mode": self.config.agent_mode,
            "workspace": self.workspace,
            "proxy_url": self.proxy_url,
            "no_ssl_verify": self.no_ssl_verify,
        }
        if self.mcp_config:
            cfg["mcp_servers"] = self.mcp_config
        if self.lsp_config:
            cfg["lsp_servers"] = self.lsp_config
        agent_config = json.dumps(cfg)

        # Spawn mcp_agent.py as a child process using MCP stdio transport
        self._mcp_server = MCPServer(
            name=f"agent-{self.config.role}",
            command=sys.executable,
            args=["-m", "copilot_cli.mcp_agent", agent_config],
            init_timeout=120,
        )
        self._mcp_server.start()
        time.sleep(0.5)
        self._mcp_server.initialize()
        self._mcp_server.list_tools()

    def execute_task(self, prompt: str, context: dict | None = None) -> dict:
        """Send a task to the worker via MCP tools/call(execute_task).

        Returns dict with keys: status, reply, worker.
        """
        arguments = {"prompt": prompt}
        if context:
            arguments["context"] = json.dumps(context)

        result = self._mcp_server.call_tool("execute_task", arguments)

        # Parse the MCP result — content[0].text is a JSON string
        content = result.get("content", [])
        if content and isinstance(content[0], dict):
            text = content[0].get("text", "{}")
            try:
                return json.loads(text)
            except json.JSONDecodeError:
                return {"status": "success", "reply": text, "worker": self.config.role}

        return {"status": "error", "reply": str(result), "worker": self.config.role}

    def get_status(self) -> dict:
        """Check worker status via MCP."""
        result = self._mcp_server.call_tool("get_status", {})
        content = result.get("content", [])
        if content and isinstance(content[0], dict):
            try:
                return json.loads(content[0].get("text", "{}"))
            except json.JSONDecodeError:
                pass
        return {"status": "unknown"}

    def get_capabilities(self) -> dict:
        """Get worker capabilities via MCP."""
        result = self._mcp_server.call_tool("get_capabilities", {})
        content = result.get("content", [])
        if content and isinstance(content[0], dict):
            try:
                return json.loads(content[0].get("text", "{}"))
            except json.JSONDecodeError:
                pass
        return {}

    def stop(self):
        """Terminate the MCP agent server process."""
        if self._mcp_server:
            self._mcp_server.stop()


# ── Queue Worker (in-process thread) ──────────────────────────────────────────

class QueueWorker:
    """A worker agent that processes tasks from its inbox queue (in-process).

    Each worker owns an independent ``CopilotClient`` session.
    """

    def __init__(self, worker_id: str, config: WorkerConfig,
                 workspace: str, inbox: queue.Queue, outbox: queue.Queue,
                 proxy_url: str | None = None,
                 no_ssl_verify: bool = False,
                 mcp_config: dict | None = None,
                 lsp_config: dict | None = None):
        self.worker_id = worker_id
        self.config = config
        # Per-worker overrides take precedence over orchestrator values
        self.workspace = config.workspace_root or workspace
        self.inbox = inbox
        self.outbox = outbox
        self.proxy_url = config.proxy_url if config.proxy_url is not None else proxy_url
        self.no_ssl_verify = config.no_ssl_verify if config.no_ssl_verify is not None else no_ssl_verify
        self.mcp_config = config.mcp_servers if config.mcp_servers is not None else mcp_config
        self.lsp_config = config.lsp_servers if config.lsp_servers is not None else lsp_config
        self._client: CopilotClient | None = None
        self._thread: threading.Thread | None = None
        self._conversation_id: str | None = None
        self._running = False

    def start(self):
        self._running = True
        self._thread = threading.Thread(
            target=self._run_loop, daemon=True,
            name=f"worker-{self.worker_id}",
        )
        self._thread.start()

    def _init_client(self):
        if self.config.tools_enabled != "__ALL__":
            from copilot_cli.tools import TOOL_SCHEMAS, TOOL_EXECUTORS
            enabled = set(self.config.tools_enabled)
            for name in list(TOOL_SCHEMAS.keys()):
                if name not in enabled:
                    del TOOL_SCHEMAS[name]
            for name in list(TOOL_EXECUTORS.keys()):
                if name not in enabled:
                    del TOOL_EXECUTORS[name]

        self._client = _init_client(
            self.workspace,
            agent_mode=self.config.agent_mode,
            mcp_config=self.mcp_config,
            lsp_config=self.lsp_config,
            proxy_url=self.proxy_url,
            no_ssl_verify=self.no_ssl_verify,
            shared=True,
        )

    def _run_loop(self):
        try:
            self._init_client()
        except Exception as e:
            self.outbox.put(_msg_task_result(
                task_id="__init__", worker_id=self.worker_id,
                status="error", result=f"Worker init failed: {e}",
            ))
            return

        while self._running:
            try:
                msg = self.inbox.get(timeout=1.0)
            except queue.Empty:
                continue

            if msg["type"] == MSG_SHUTDOWN:
                break
            elif msg["type"] == MSG_TASK_ASSIGN:
                self._handle_task(msg)

        self._stop_client()

    def _handle_task(self, msg: dict):
        task_id = msg["task_id"]
        prompt = msg["prompt"]
        context = msg.get("context", {})

        parts = []
        if self.config.system_prompt:
            parts.append(
                f"<system_instructions>{self.config.system_prompt}</system_instructions>"
            )
        if context:
            parts.append(
                f"<shared_context>{json.dumps(context, indent=2)}</shared_context>"
            )
        parts.append(prompt)
        actual_prompt = "\n\n".join(parts)

        def on_progress(kind, data):
            if kind == "delta":
                delta = data.get("delta", "")
                if delta:
                    self.outbox.put(_msg_task_progress(
                        task_id=task_id, worker_id=self.worker_id,
                        message=delta,
                    ))

        workspace_uri = path_to_file_uri(self.workspace)

        try:
            if self._conversation_id is None:
                result = self._client.conversation_create(
                    actual_prompt,
                    model=self.config.model,
                    agent_mode=self.config.agent_mode,
                    workspace_folder=workspace_uri if self.config.agent_mode else None,
                    on_progress=on_progress,
                )
                self._conversation_id = result.get("conversationId")
            else:
                result = self._client.conversation_turn(
                    self._conversation_id, actual_prompt,
                    model=self.config.model,
                    agent_mode=self.config.agent_mode,
                    workspace_folder=workspace_uri if self.config.agent_mode else None,
                    on_progress=on_progress,
                )

            self.outbox.put(_msg_task_result(
                task_id=task_id,
                worker_id=self.worker_id,
                status="success",
                result=result.get("reply", ""),
                agent_rounds=result.get("agent_rounds", []),
            ))
        except Exception as e:
            self.outbox.put(_msg_task_result(
                task_id=task_id,
                worker_id=self.worker_id,
                status="error",
                result=str(e),
            ))

    def _stop_client(self):
        if self._client:
            if self._conversation_id:
                try:
                    self._client.conversation_destroy(self._conversation_id)
                except Exception:
                    pass
            try:
                release_client(self._client)
            except Exception:
                pass

    def stop(self):
        self._running = False
        self.inbox.put(_msg_shutdown())
        if self._thread:
            self._thread.join(timeout=10)


# ── Orchestrator ──────────────────────────────────────────────────────────────

class Orchestrator:
    """Orchestrator agent that decomposes goals and delegates to workers.

    Supports two transport modes:

    - ``"mcp"`` (default): Workers run as MCP server child processes.
      Communication uses the standard MCP protocol (``tools/call``).
    - ``"queue"``: Workers run as in-process threads with queue-based
      message passing.

    Args:
        workspace: Absolute path to the shared workspace.
        workers: List of worker configurations.
        model: Model for the orchestrator's own planning session.
        transport: ``"mcp"`` or ``"queue"``.
        proxy_url: HTTP proxy URL.
        no_ssl_verify: Disable SSL verification.
        on_event: Optional callback for UI integration.
    """

    PLANNING_SYSTEM_PROMPT = """\
You are an orchestrator agent. Your job is to break down complex tasks into \
discrete subtasks and assign them to specialised worker agents.

Available workers:
{workers_description}

When given a task, respond with a JSON array of subtask assignments. Each \
element must have:
- "worker_role": one of the available worker roles
- "task": a clear, self-contained description of what the worker should do
- "depends_on": list of task indices (0-based) that must complete first, or []

Example response:
```json
[
  {{"worker_role": "bug_fixer", "task": "Find and fix the null pointer in auth.py line 42", "depends_on": []}},
  {{"worker_role": "test_writer", "task": "Write unit tests for the auth.py fix", "depends_on": [0]}}
]
```

IMPORTANT: Respond ONLY with the JSON array. No other text."""

    def __init__(self, workspace: str, workers: list[WorkerConfig],
                 model: str | None = None,
                 transport: str = "mcp",
                 proxy_url: str | None = None,
                 no_ssl_verify: bool = False,
                 mcp_config: dict | None = None,
                 lsp_config: dict | None = None,
                 on_event: Callable | None = None):
        self.workspace = os.path.abspath(workspace)
        self.worker_configs = {w.role: w for w in workers}
        self.model = model
        self.transport = transport
        self.proxy_url = proxy_url
        self.no_ssl_verify = no_ssl_verify
        self.mcp_config = mcp_config
        self.lsp_config = lsp_config
        self.on_event = on_event

        # Orchestrator's own client (for planning)
        self._client: CopilotClient | None = None
        self._conversation_id: str | None = None

        # MCP transport: worker processes
        self._mcp_workers: dict[str, MCPWorker] = {}

        # Queue transport: worker threads and queues
        self._queue_workers: dict[str, QueueWorker] = {}
        self._worker_inboxes: dict[str, queue.Queue] = {}
        self._result_queue: queue.Queue = queue.Queue()

    def _emit(self, event_type: str, data: dict):
        if self.on_event:
            self.on_event(event_type, data)

    def start(self):
        """Initialize the orchestrator client and all worker agents."""
        transport_label = "MCP" if self.transport == "mcp" else "Queue"
        print(f"\033[94m╭─ Orchestrator ({transport_label} transport)\033[0m")
        print(f"\033[94m│\033[0m  {len(self.worker_configs)} workers: "
              f"{', '.join(self.worker_configs.keys())}")
        print(f"\033[94m│\033[0m  \033[90m{os.path.basename(self.workspace)}\033[0m")
        print(f"\033[94m╰─\033[0m")

        # Start orchestrator's own session (chat-only, for planning).
        # Use shared=True so queue workers reuse the same LSP process.
        self._client = _init_client(
            self.workspace,
            agent_mode=False,
            proxy_url=self.proxy_url,
            no_ssl_verify=self.no_ssl_verify,
            shared=True,
        )

        if self.transport == "mcp":
            self._start_mcp_workers()
        else:
            self._start_queue_workers()

    def _start_mcp_workers(self):
        """Start each worker as an MCP server child process."""
        for role, config in self.worker_configs.items():
            print(f"\033[32m⏺\033[0m Starting MCP worker: \033[1m{role}\033[0m "
                  f"(model={config.model or 'default'})")
            worker = MCPWorker(
                config=config,
                workspace=self.workspace,
                proxy_url=self.proxy_url,
                no_ssl_verify=self.no_ssl_verify,
                mcp_config=self.mcp_config,
                lsp_config=self.lsp_config,
            )
            try:
                worker.start()
                self._mcp_workers[role] = worker
                print(f"  \033[90mMCP agent-{role}: ready "
                      f"({len(worker._mcp_server.tools)} tools)\033[0m")
            except Exception as e:
                print(f"  \033[31mFailed to start worker {role}: {e}\033[0m")

    def _start_queue_workers(self):
        """Start each worker as an in-process thread."""
        for role, config in self.worker_configs.items():
            worker_id = f"{role}-{uuid.uuid4().hex[:6]}"
            inbox = queue.Queue()
            self._worker_inboxes[role] = inbox
            worker = QueueWorker(
                worker_id=worker_id,
                config=config,
                workspace=self.workspace,
                inbox=inbox,
                outbox=self._result_queue,
                proxy_url=self.proxy_url,
                no_ssl_verify=self.no_ssl_verify,
                mcp_config=self.mcp_config,
                lsp_config=self.lsp_config,
            )
            self._queue_workers[role] = worker
            print(f"\033[32m⏺\033[0m Starting queue worker: \033[1m{role}\033[0m "
                  f"(model={config.model or 'default'})")
            worker.start()

    def _plan_tasks(self, goal: str) -> list[dict]:
        """Use the orchestrator's LLM session to decompose a goal into tasks."""
        workers_desc = "\n".join(
            f"- {role}: {cfg.system_prompt[:120]}"
            for role, cfg in self.worker_configs.items()
        )
        planning_prompt = self.PLANNING_SYSTEM_PROMPT.format(
            workers_description=workers_desc
        ) + f"\n\nGoal: {goal}"

        if self._conversation_id is None:
            result = self._client.conversation_create(
                planning_prompt, model=self.model, agent_mode=False,
            )
            self._conversation_id = result.get("conversationId")
        else:
            result = self._client.conversation_turn(
                self._conversation_id, planning_prompt,
                model=self.model, agent_mode=False,
            )

        reply = result.get("reply", "")

        # Extract JSON from the reply (handle markdown fences)
        json_str = reply.strip()
        if "```json" in json_str:
            json_str = json_str.split("```json", 1)[1]
            json_str = json_str.split("```", 1)[0]
        elif "```" in json_str:
            json_str = json_str.split("```", 1)[1]
            json_str = json_str.split("```", 1)[0]
        json_str = json_str.strip()

        try:
            tasks = json.loads(json_str)
        except json.JSONDecodeError:
            first_role = next(iter(self.worker_configs))
            tasks = [{"worker_role": first_role, "task": goal, "depends_on": []}]

        validated = []
        for t in tasks:
            role = t.get("worker_role", "")
            if role not in self.worker_configs:
                role = next(iter(self.worker_configs))
            validated.append({
                "worker_role": role,
                "task": t.get("task", ""),
                "depends_on": t.get("depends_on", []),
            })

        return validated

    # ── Task execution (dispatches to the appropriate transport) ───────────

    def run(self, goal: str, context: dict | None = None) -> dict:
        """Execute a high-level goal by planning, delegating, and aggregating.

        Returns dict with keys: ``tasks``, ``results``, ``summary``.
        """
        # Step 1: Plan
        print(f"\n\033[94m⏺\033[0m Planning task decomposition...")
        self._emit("plan", {"goal": goal, "status": "planning"})
        tasks = self._plan_tasks(goal)

        print(f"\033[94m⏺\033[0m Plan: {len(tasks)} subtask(s)")
        for i, t in enumerate(tasks):
            dep_str = f" (after: {t['depends_on']})" if t["depends_on"] else ""
            print(f"  \033[90m{i}. [{t['worker_role']}]{dep_str} {t['task'][:100]}\033[0m")
        self._emit("plan", {"goal": goal, "tasks": tasks, "status": "planned"})

        # Step 2: Execute
        if self.transport == "mcp":
            results = self._execute_mcp(tasks, context)
        else:
            results = self._execute_queue(tasks, context)

        # Step 3: Summarize
        summary = self._summarize(goal, results)

        return {"tasks": tasks, "results": results, "summary": summary}

    def _execute_mcp(self, tasks: list[dict], context: dict | None) -> list[dict]:
        """Execute tasks using MCP transport (child processes)."""
        completed: dict[int, dict] = {}
        pending = set(range(len(tasks)))

        while pending:
            # Find tasks whose dependencies are satisfied
            ready = [idx for idx in pending
                     if all(d in completed for d in tasks[idx].get("depends_on", []))]

            if not ready:
                break

            # Dispatch ready tasks in parallel threads
            results_lock = threading.Lock()
            threads = []

            for idx in ready:
                pending.discard(idx)
                t = tasks[idx]
                role = t["worker_role"]

                # Build context from completed dependencies
                dep_context = dict(context or {})
                for dep_idx in t.get("depends_on", []):
                    dep_result = completed.get(dep_idx, {})
                    dep_role = tasks[dep_idx]["worker_role"]
                    dep_context[f"result_from_{dep_role}_task_{dep_idx}"] = (
                        dep_result.get("result", "")
                    )

                worker = self._mcp_workers.get(role)
                if not worker:
                    completed[idx] = {
                        "status": "error",
                        "result": f"No MCP worker for role: {role}",
                    }
                    continue

                print(f"\033[32m⏺\033[0m Assigning task {idx} to "
                      f"\033[1m{role}\033[0m (MCP): {t['task'][:80]}")
                self._emit("assign", {"worker_role": role, "task": t["task"],
                                       "index": idx})

                def _run_task(w=worker, i=idx, p=t["task"], c=dep_context):
                    try:
                        result = w.execute_task(p, c)
                        with results_lock:
                            completed[i] = {
                                "status": result.get("status", "success"),
                                "result": result.get("reply", str(result)),
                            }
                    except Exception as e:
                        with results_lock:
                            completed[i] = {"status": "error", "result": str(e)}

                thread = threading.Thread(target=_run_task, daemon=True)
                threads.append(thread)
                thread.start()

            # Wait for all dispatched tasks
            for thread in threads:
                thread.join(timeout=300)

            # Print results
            for idx in ready:
                if idx in completed:
                    r = completed[idx]
                    icon = "\033[32m✓\033[0m" if r["status"] == "success" else "\033[31m✗\033[0m"
                    print(f"  {icon} Task {idx} [{tasks[idx]['worker_role']}]: "
                          f"{r['status']}")
                    self._emit("result", {**r, "index": idx,
                                          "worker_role": tasks[idx]["worker_role"]})

        # Build results list
        results = []
        for i, t in enumerate(tasks):
            r = completed.get(i, {"status": "skipped", "result": "Not executed"})
            results.append({
                "index": i,
                "worker_role": t["worker_role"],
                "task": t["task"],
                "status": r.get("status", "unknown"),
                "result": r.get("result", ""),
            })

        return results

    def _execute_queue(self, tasks: list[dict], context: dict | None) -> list[dict]:
        """Execute tasks using queue transport (in-process threads)."""
        task_ids = [f"task-{uuid.uuid4().hex[:8]}" for _ in tasks]
        completed: dict[int, dict] = {}
        pending = set(range(len(tasks)))

        while pending:
            ready = [idx for idx in pending
                     if all(d in completed for d in tasks[idx].get("depends_on", []))]

            if not ready:
                try:
                    result_msg = self._result_queue.get(timeout=300)
                    self._handle_queue_result(result_msg, task_ids, tasks,
                                              completed, pending)
                except queue.Empty:
                    print("\033[31m⏺\033[0m Timeout waiting for worker results")
                    break
                continue

            for idx in ready:
                pending.discard(idx)
                t = tasks[idx]
                role = t["worker_role"]
                task_id = task_ids[idx]

                dep_context = dict(context or {})
                for dep_idx in t.get("depends_on", []):
                    dep_result = completed.get(dep_idx, {})
                    dep_role = tasks[dep_idx]["worker_role"]
                    dep_context[f"result_from_{dep_role}_task_{dep_idx}"] = (
                        dep_result.get("result", "")
                    )

                print(f"\033[32m⏺\033[0m Assigning task {idx} to "
                      f"\033[1m{role}\033[0m (queue): {t['task'][:80]}")
                self._emit("assign", {"task_id": task_id, "worker_role": role,
                                       "task": t["task"], "index": idx})

                inbox = self._worker_inboxes.get(role)
                if inbox:
                    inbox.put(_msg_task_assign(
                        task_id=task_id, worker_id=role,
                        prompt=t["task"], context=dep_context,
                    ))
                else:
                    completed[idx] = {
                        "status": "error",
                        "result": f"No worker found for role: {role}",
                    }

            while len(completed) < len(tasks) - len(pending):
                try:
                    result_msg = self._result_queue.get(timeout=300)
                    self._handle_queue_result(result_msg, task_ids, tasks,
                                              completed, pending)
                except queue.Empty:
                    print("\033[31m⏺\033[0m Timeout waiting for worker results")
                    break

        results = []
        for i, t in enumerate(tasks):
            r = completed.get(i, {"status": "skipped", "result": "Not executed"})
            results.append({
                "index": i,
                "worker_role": t["worker_role"],
                "task": t["task"],
                "status": r.get("status", "unknown"),
                "result": r.get("result", ""),
            })

        return results

    def _handle_queue_result(self, msg: dict, task_ids: list[str],
                             tasks: list[dict], completed: dict, pending: set):
        if msg["type"] == MSG_TASK_PROGRESS:
            self._emit("progress", msg)
            return

        if msg["type"] == MSG_TASK_RESULT:
            task_id = msg.get("task_id")
            if task_id in task_ids:
                idx = task_ids.index(task_id)
                completed[idx] = msg
                icon = "\033[32m✓\033[0m" if msg["status"] == "success" else "\033[31m✗\033[0m"
                print(f"  {icon} Task {idx} [{tasks[idx]['worker_role']}]: "
                      f"{msg['status']}")
                self._emit("result", {**msg, "index": idx})

    def _summarize(self, goal: str, results: list[dict]) -> str:
        results_text = "\n".join(
            f"Task {r['index']} [{r['worker_role']}] ({r['status']}): "
            f"{r['result'][:500]}"
            for r in results
        )
        summary_prompt = (
            f"The original goal was: {goal}\n\n"
            f"Here are the results from the worker agents:\n{results_text}\n\n"
            f"Please provide a concise summary of what was accomplished, "
            f"any issues encountered, and next steps if applicable."
        )

        try:
            result = self._client.conversation_turn(
                self._conversation_id, summary_prompt, model=self.model,
                agent_mode=False,
            )
            return result.get("reply", "")
        except Exception as e:
            return f"Summary generation failed: {e}"

    def stop(self):
        """Shut down all workers and the orchestrator client."""
        # Stop MCP workers
        for role, worker in self._mcp_workers.items():
            print(f"\033[90m  Stopping MCP worker: {role}\033[0m")
            worker.stop()
        self._mcp_workers.clear()

        # Stop queue workers
        for role, worker in self._queue_workers.items():
            print(f"\033[90m  Stopping queue worker: {role}\033[0m")
            worker.stop()
        self._queue_workers.clear()

        if self._client:
            if self._conversation_id:
                try:
                    self._client.conversation_destroy(self._conversation_id)
                except Exception:
                    pass
            try:
                release_client(self._client)
            except Exception:
                pass
        print("\033[94m⏺\033[0m Orchestrator stopped.")


# ── CLI entry point ───────────────────────────────────────────────────────────

def run_orchestrator_cli(workspace: str, goal: str,
                         workers: list[WorkerConfig] | None = None,
                         model: str | None = None,
                         transport: str = "mcp",
                         proxy_url: str | None = None,
                         no_ssl_verify: bool = False,
                         mcp_config: dict | None = None,
                         lsp_config: dict | None = None):
    """Run the orchestrator from the CLI.

    If no workers are specified, uses a default set of three workers:
    - coder: reads/writes code, runs commands
    - reviewer: reviews code changes (read-only)
    - tester: writes and runs tests
    """
    if workers is None:
        workers = [
            WorkerConfig(
                role="coder",
                system_prompt=(
                    "You are a skilled software engineer. Read code, understand "
                    "the codebase, make edits, and run commands as needed. "
                    "Focus on clean, working implementations."
                ),
                model=model,
                agent_mode=True,
            ),
            WorkerConfig(
                role="reviewer",
                system_prompt=(
                    "You are a code review expert. Examine code for bugs, "
                    "style issues, security vulnerabilities, and suggest "
                    "improvements. Do NOT edit files — only report findings."
                ),
                model=model,
                tools_enabled=[
                    "read_file", "list_dir", "file_search", "grep_search",
                    "get_errors", "search_workspace_symbols", "list_code_usages",
                    "get_changed_files", "get_project_setup_info",
                ],
                agent_mode=True,
            ),
            WorkerConfig(
                role="tester",
                system_prompt=(
                    "You are a testing specialist. Write comprehensive tests, "
                    "run the test suite, and report results. Ensure good "
                    "coverage of edge cases and failure modes."
                ),
                model=model,
                agent_mode=True,
            ),
        ]

    orch = Orchestrator(
        workspace=workspace,
        workers=workers,
        model=model,
        transport=transport,
        proxy_url=proxy_url,
        no_ssl_verify=no_ssl_verify,
        mcp_config=mcp_config,
        lsp_config=lsp_config,
    )

    try:
        orch.start()
        result = orch.run(goal)

        print(f"\n\033[94m{'─' * 60}\033[0m")
        print(f"\033[94m⏺ Summary\033[0m")
        print(f"\033[94m{'─' * 60}\033[0m")
        print(result["summary"])
        print()

        return result
    finally:
        orch.stop()
