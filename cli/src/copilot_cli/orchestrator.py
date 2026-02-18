"""Agent-to-Agent Communication Protocol: Orchestrator ↔ Worker agents.

This module implements a lightweight orchestrator pattern where a single
**orchestrator agent** decomposes high-level goals into discrete tasks and
delegates them to specialised **worker agents**.  Each worker is an independent
``CopilotClient`` session with its own conversation, tools, model, and system
prompt — but they all share a workspace and communicate through a typed
in-process message bus.

Architecture
------------
::

    ┌──────────────────────────────────────────┐
    │             Orchestrator Agent            │
    │  (plans tasks, fans out, aggregates)      │
    └──┬────────┬────────┬────────┬────────┬───┘
       │ assign │ assign │ assign │  ...   │
       ▼        ▼        ▼        ▼        ▼
    ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐
    │Worker│ │Worker│ │Worker│ │Worker│ │Worker│
    │  A   │ │  B   │ │  C   │ │  D   │ │  E   │
    └──┬───┘ └──┬───┘ └──┬───┘ └──┬───┘ └──┬───┘
       │ result │ result │ result │ result │ result
       ▼        ▼        ▼        ▼        ▼
    ┌──────────────────────────────────────────┐
    │        Orchestrator (aggregates)          │
    └──────────────────────────────────────────┘

Message Protocol
----------------
All messages are plain Python dicts transported via ``queue.Queue``.

.. code-block:: python

    # Orchestrator → Worker
    {
        "type": "task_assign",
        "task_id": "...",
        "worker_id": "...",
        "prompt": "...",
        "context": { ... },       # optional shared state
    }

    # Worker → Orchestrator
    {
        "type": "task_result",
        "task_id": "...",
        "worker_id": "...",
        "status": "success" | "error",
        "result": "...",
        "agent_rounds": [...],
    }

    # Worker → Orchestrator (progress)
    {
        "type": "task_progress",
        "task_id": "...",
        "worker_id": "...",
        "message": "...",
    }

    # Orchestrator → Worker
    {
        "type": "shutdown",
    }

Usage (programmatic)
--------------------
::

    from copilot_cli.orchestrator import Orchestrator, WorkerConfig

    workers = [
        WorkerConfig(role="bug_fixer", model="gpt-4.1",
                     system_prompt="You are a debugging expert..."),
        WorkerConfig(role="test_writer", model="claude-sonnet-4",
                     system_prompt="You are a test-writing specialist..."),
    ]

    orch = Orchestrator(workspace="/my/project", workers=workers)
    orch.start()
    results = orch.run("Fix the login bug and add tests for the fix")
    orch.stop()
"""

from __future__ import annotations

import dataclasses
import json
import os
import queue
import threading
import time
import uuid
from typing import Callable

from copilot_cli.client import CopilotClient, _init_client
from copilot_cli.platform_utils import path_to_file_uri


# ── Message types ─────────────────────────────────────────────────────────────

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
    """Configuration for a single worker agent."""
    role: str                          # Short identifier (e.g. "bug_fixer")
    system_prompt: str = ""            # Injected as <system_instructions> in first turn
    model: str | None = None           # Model override (None = server default)
    tools_enabled: list[str] | str = "__ALL__"  # "__ALL__" or list of tool names
    agent_mode: bool = True            # True for agent mode, False for chat-only


# ── Worker Agent ──────────────────────────────────────────────────────────────

class WorkerAgent:
    """A single worker agent that processes tasks from its inbox queue.

    Each worker owns an independent ``CopilotClient`` session.  The
    orchestrator sends ``task_assign`` messages to ``inbox`` and the
    worker posts ``task_result`` / ``task_progress`` messages to
    ``outbox``.
    """

    def __init__(self, worker_id: str, config: WorkerConfig,
                 workspace: str, inbox: queue.Queue, outbox: queue.Queue,
                 proxy_url: str | None = None,
                 no_ssl_verify: bool = False):
        self.worker_id = worker_id
        self.config = config
        self.workspace = workspace
        self.inbox = inbox
        self.outbox = outbox
        self.proxy_url = proxy_url
        self.no_ssl_verify = no_ssl_verify
        self._client: CopilotClient | None = None
        self._thread: threading.Thread | None = None
        self._conversation_id: str | None = None
        self._running = False

    def start(self):
        """Initialize the CopilotClient and start the message processing loop."""
        self._running = True
        self._thread = threading.Thread(
            target=self._run_loop, daemon=True,
            name=f"worker-{self.worker_id}",
        )
        self._thread.start()

    def _init_client(self):
        """Create and initialize the CopilotClient for this worker."""
        # Filter tools if needed
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
            proxy_url=self.proxy_url,
            no_ssl_verify=self.no_ssl_verify,
        )

    def _run_loop(self):
        """Main loop: wait for messages and process them."""
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

        # Cleanup
        self._stop_client()

    def _handle_task(self, msg: dict):
        """Execute a task assignment and post the result."""
        task_id = msg["task_id"]
        prompt = msg["prompt"]
        context = msg.get("context", {})

        # Build the actual prompt with system instructions and context
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

        # Progress callback
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
                self._client.stop()
            except Exception:
                pass

    def stop(self):
        """Signal the worker to shut down."""
        self._running = False
        self.inbox.put(_msg_shutdown())
        if self._thread:
            self._thread.join(timeout=10)


# ── Orchestrator ──────────────────────────────────────────────────────────────

class Orchestrator:
    """Orchestrator agent that decomposes goals and delegates to workers.

    The orchestrator itself is also a ``CopilotClient`` session.  Its job
    is to:

    1. Receive a high-level goal from the user.
    2. Use its own agent session to decompose the goal into discrete tasks.
    3. Assign each task to the appropriate worker.
    4. Collect results and produce a final summary.

    The orchestrator uses a dedicated conversation with a planning system
    prompt that instructs the model to output structured JSON task lists.

    Args:
        workspace: Absolute path to the shared workspace.
        workers: List of worker configurations.
        model: Model for the orchestrator's own planning session.
        proxy_url: HTTP proxy URL.
        no_ssl_verify: Disable SSL verification.
        on_event: Optional callback ``(event_type, data) -> None`` for
            UI integration.  Event types: ``"plan"``, ``"assign"``,
            ``"progress"``, ``"result"``, ``"summary"``.
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
                 proxy_url: str | None = None,
                 no_ssl_verify: bool = False,
                 on_event: Callable | None = None):
        self.workspace = os.path.abspath(workspace)
        self.worker_configs = {w.role: w for w in workers}
        self.model = model
        self.proxy_url = proxy_url
        self.no_ssl_verify = no_ssl_verify
        self.on_event = on_event

        # Orchestrator's own client (for planning)
        self._client: CopilotClient | None = None
        self._conversation_id: str | None = None

        # Worker instances and their queues
        self._workers: dict[str, WorkerAgent] = {}
        self._worker_inboxes: dict[str, queue.Queue] = {}
        self._result_queue: queue.Queue = queue.Queue()

    def _emit(self, event_type: str, data: dict):
        if self.on_event:
            self.on_event(event_type, data)

    def start(self):
        """Initialize the orchestrator client and all worker agents."""
        print(f"\033[94m╭─ Orchestrator\033[0m")
        print(f"\033[94m│\033[0m  {len(self.worker_configs)} workers: "
              f"{', '.join(self.worker_configs.keys())}")
        print(f"\033[94m│\033[0m  \033[90m{os.path.basename(self.workspace)}\033[0m")
        print(f"\033[94m╰─\033[0m")

        # Start orchestrator's own session (chat-only, for planning)
        self._client = _init_client(
            self.workspace,
            agent_mode=False,
            proxy_url=self.proxy_url,
            no_ssl_verify=self.no_ssl_verify,
        )

        # Start each worker
        for role, config in self.worker_configs.items():
            worker_id = f"{role}-{uuid.uuid4().hex[:6]}"
            inbox = queue.Queue()
            self._worker_inboxes[role] = inbox
            worker = WorkerAgent(
                worker_id=worker_id,
                config=config,
                workspace=self.workspace,
                inbox=inbox,
                outbox=self._result_queue,
                proxy_url=self.proxy_url,
                no_ssl_verify=self.no_ssl_verify,
            )
            self._workers[role] = worker
            print(f"\033[32m⏺\033[0m Starting worker: \033[1m{role}\033[0m "
                  f"(model={config.model or 'default'})")
            worker.start()

    def _plan_tasks(self, goal: str) -> list[dict]:
        """Use the orchestrator's LLM session to decompose a goal into tasks.

        Returns a list of dicts with keys: worker_role, task, depends_on.
        """
        workers_desc = "\n".join(
            f"- {role}: {cfg.system_prompt[:120]}"
            for role, cfg in self.worker_configs.items()
        )
        planning_prompt = self.PLANNING_SYSTEM_PROMPT.format(
            workers_description=workers_desc
        ) + f"\n\nGoal: {goal}"

        workspace_uri = path_to_file_uri(self.workspace)

        if self._conversation_id is None:
            result = self._client.conversation_create(
                planning_prompt, model=self.model,
                agent_mode=False,
            )
            self._conversation_id = result.get("conversationId")
        else:
            result = self._client.conversation_turn(
                self._conversation_id, planning_prompt, model=self.model,
                agent_mode=False,
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
            # Fallback: treat the entire goal as a single task for the first worker
            first_role = next(iter(self.worker_configs))
            tasks = [{"worker_role": first_role, "task": goal, "depends_on": []}]

        # Validate tasks
        validated = []
        for t in tasks:
            role = t.get("worker_role", "")
            if role not in self.worker_configs:
                # Assign to first available worker
                role = next(iter(self.worker_configs))
            validated.append({
                "worker_role": role,
                "task": t.get("task", ""),
                "depends_on": t.get("depends_on", []),
            })

        return validated

    def run(self, goal: str, context: dict | None = None) -> dict:
        """Execute a high-level goal by planning, delegating, and aggregating.

        Args:
            goal: The user's high-level request.
            context: Optional shared context available to all workers.

        Returns:
            Dict with keys: ``tasks``, ``results``, ``summary``.
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

        # Step 2: Execute tasks respecting dependencies
        task_ids = [f"task-{uuid.uuid4().hex[:8]}" for _ in tasks]
        completed: dict[int, dict] = {}  # index -> result msg
        pending = set(range(len(tasks)))

        while pending:
            # Find tasks whose dependencies are satisfied
            ready = []
            for idx in list(pending):
                deps = tasks[idx].get("depends_on", [])
                if all(d in completed for d in deps):
                    ready.append(idx)

            if not ready:
                # All remaining tasks have unsatisfied deps — try to collect results
                try:
                    result_msg = self._result_queue.get(timeout=300)
                    self._handle_result(result_msg, task_ids, tasks, completed, pending)
                except queue.Empty:
                    print("\033[31m⏺\033[0m Timeout waiting for worker results")
                    break
                continue

            # Dispatch ready tasks
            for idx in ready:
                pending.discard(idx)
                t = tasks[idx]
                role = t["worker_role"]
                task_id = task_ids[idx]

                # Build context from completed dependencies
                dep_context = dict(context or {})
                for dep_idx in t.get("depends_on", []):
                    dep_result = completed.get(dep_idx, {})
                    dep_role = tasks[dep_idx]["worker_role"]
                    dep_context[f"result_from_{dep_role}_task_{dep_idx}"] = (
                        dep_result.get("result", "")
                    )

                print(f"\033[32m⏺\033[0m Assigning task {idx} to "
                      f"\033[1m{role}\033[0m: {t['task'][:80]}")
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

            # Collect results for dispatched tasks
            while len(completed) < len(tasks) - len(pending):
                try:
                    result_msg = self._result_queue.get(timeout=300)
                    self._handle_result(result_msg, task_ids, tasks, completed, pending)
                except queue.Empty:
                    print("\033[31m⏺\033[0m Timeout waiting for worker results")
                    break

        # Step 3: Aggregate results
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

        # Generate summary via orchestrator LLM
        summary = self._summarize(goal, results)

        return {"tasks": tasks, "results": results, "summary": summary}

    def _handle_result(self, msg: dict, task_ids: list[str],
                       tasks: list[dict], completed: dict, pending: set):
        """Process a message from the result queue."""
        if msg["type"] == MSG_TASK_PROGRESS:
            self._emit("progress", msg)
            return

        if msg["type"] == MSG_TASK_RESULT:
            task_id = msg.get("task_id")
            if task_id in task_ids:
                idx = task_ids.index(task_id)
                completed[idx] = msg
                status_icon = "\033[32m✓\033[0m" if msg["status"] == "success" else "\033[31m✗\033[0m"
                print(f"  {status_icon} Task {idx} [{tasks[idx]['worker_role']}]: "
                      f"{msg['status']}")
                self._emit("result", {**msg, "index": idx})

    def _summarize(self, goal: str, results: list[dict]) -> str:
        """Ask the orchestrator LLM to produce a final summary."""
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
        for role, worker in self._workers.items():
            print(f"\033[90m  Stopping worker: {role}\033[0m")
            worker.stop()
        self._workers.clear()

        if self._client:
            if self._conversation_id:
                try:
                    self._client.conversation_destroy(self._conversation_id)
                except Exception:
                    pass
            try:
                self._client.stop()
            except Exception:
                pass
        print("\033[94m⏺\033[0m Orchestrator stopped.")


# ── CLI entry point ───────────────────────────────────────────────────────────

def run_orchestrator_cli(workspace: str, goal: str,
                         workers: list[WorkerConfig] | None = None,
                         model: str | None = None,
                         proxy_url: str | None = None,
                         no_ssl_verify: bool = False):
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
        proxy_url=proxy_url,
        no_ssl_verify=no_ssl_verify,
    )

    try:
        orch.start()
        result = orch.run(goal)

        # Print summary
        print(f"\n\033[94m{'─' * 60}\033[0m")
        print(f"\033[94m⏺ Summary\033[0m")
        print(f"\033[94m{'─' * 60}\033[0m")
        print(result["summary"])
        print()

        return result
    finally:
        orch.stop()
