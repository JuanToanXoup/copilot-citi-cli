"""Unit tests for the Copilot SDK layer (copilot_sdk.py, sdk_providers.py, sdk_session_store.py)."""

import json
import os
import sys
import tempfile
import shutil
import threading
import time
import unittest
from unittest.mock import MagicMock, patch

# Ensure the project root is on the path
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, PROJECT_ROOT)

from copilot_sdk import (
    CopilotSDK,
    Session,
    SessionConfig,
    ProviderConfig,
    EventType,
    EventData,
    Event,
    EventEmitter,
    Tool,
    define_tool,
    Hooks,
    HookResult,
    InfiniteSessionConfig,
    Message,
    _encode_image,
    _infer_schema,
    _handle_slash_command,
)
from sdk_providers import (
    BaseProvider,
    OpenAIProvider,
    AzureProvider,
    AnthropicProvider,
    create_provider,
)
from sdk_session_store import SessionStore


# ---------------------------------------------------------------------------
# EventEmitter tests
# ---------------------------------------------------------------------------

class TestEventEmitter(unittest.TestCase):
    """Test the EventEmitter mixin."""

    def test_global_listener(self):
        emitter = EventEmitter()
        received = []
        emitter.on(lambda e: received.append(e))

        event = Event(type=EventType.ASSISTANT_MESSAGE,
                      data=EventData(content="hello"))
        emitter._emit(event)

        self.assertEqual(len(received), 1)
        self.assertEqual(received[0].data.content, "hello")

    def test_typed_listener(self):
        emitter = EventEmitter()
        received = []
        emitter.on(lambda e: received.append(e),
                   event_type=EventType.ASSISTANT_MESSAGE_DELTA)

        # Emit a non-matching event
        emitter._emit(Event(type=EventType.ASSISTANT_MESSAGE,
                            data=EventData(content="full")))
        self.assertEqual(len(received), 0)

        # Emit matching event
        emitter._emit(Event(type=EventType.ASSISTANT_MESSAGE_DELTA,
                            data=EventData(delta_content="partial")))
        self.assertEqual(len(received), 1)
        self.assertEqual(received[0].data.delta_content, "partial")

    def test_unsubscribe(self):
        emitter = EventEmitter()
        received = []
        unsub = emitter.on(lambda e: received.append(e))

        emitter._emit(Event(type=EventType.SESSION_IDLE, data=EventData()))
        self.assertEqual(len(received), 1)

        unsub()
        emitter._emit(Event(type=EventType.SESSION_IDLE, data=EventData()))
        self.assertEqual(len(received), 1)  # No new events after unsubscribe

    def test_typed_unsubscribe(self):
        emitter = EventEmitter()
        received = []
        unsub = emitter.on(lambda e: received.append(e),
                           event_type=EventType.TOOL_CALL)

        emitter._emit(Event(type=EventType.TOOL_CALL,
                            data=EventData(tool_name="test")))
        self.assertEqual(len(received), 1)

        unsub()
        emitter._emit(Event(type=EventType.TOOL_CALL,
                            data=EventData(tool_name="test2")))
        self.assertEqual(len(received), 1)

    def test_multiple_listeners(self):
        emitter = EventEmitter()
        r1, r2 = [], []
        emitter.on(lambda e: r1.append(e))
        emitter.on(lambda e: r2.append(e))

        emitter._emit(Event(type=EventType.SESSION_IDLE, data=EventData()))
        self.assertEqual(len(r1), 1)
        self.assertEqual(len(r2), 1)

    def test_listener_error_doesnt_break_others(self):
        emitter = EventEmitter()
        received = []

        def bad_listener(e):
            raise ValueError("boom")

        emitter.on(bad_listener)
        emitter.on(lambda e: received.append(e))

        emitter._emit(Event(type=EventType.SESSION_IDLE, data=EventData()))
        self.assertEqual(len(received), 1)


# ---------------------------------------------------------------------------
# EventType tests
# ---------------------------------------------------------------------------

class TestEventType(unittest.TestCase):
    """Test EventType enum values."""

    def test_all_event_types(self):
        expected = {
            "assistant.message", "assistant.message_delta",
            "assistant.reasoning", "assistant.reasoning_delta",
            "session.idle",
            "session.compaction_start", "session.compaction_complete",
            "tool.call", "tool.result",
            "user.input_request", "error",
        }
        actual = {e.value for e in EventType}
        self.assertEqual(actual, expected)

    def test_event_type_comparison(self):
        self.assertEqual(EventType.ASSISTANT_MESSAGE,
                         EventType.ASSISTANT_MESSAGE)
        self.assertNotEqual(EventType.ASSISTANT_MESSAGE,
                            EventType.ASSISTANT_MESSAGE_DELTA)


# ---------------------------------------------------------------------------
# Event / EventData tests
# ---------------------------------------------------------------------------

class TestEventData(unittest.TestCase):

    def test_defaults(self):
        data = EventData()
        self.assertEqual(data.content, "")
        self.assertEqual(data.delta_content, "")
        self.assertIsNone(data.tool_args)
        self.assertIsNone(data.error)

    def test_content(self):
        data = EventData(content="hello", tool_name="run_test")
        self.assertEqual(data.content, "hello")
        self.assertEqual(data.tool_name, "run_test")


class TestEvent(unittest.TestCase):

    def test_event_creation(self):
        event = Event(
            type=EventType.ASSISTANT_MESSAGE,
            data=EventData(content="test"),
            session_id="s1",
        )
        self.assertEqual(event.type, EventType.ASSISTANT_MESSAGE)
        self.assertEqual(event.data.content, "test")
        self.assertEqual(event.session_id, "s1")
        self.assertGreater(event.timestamp, 0)


# ---------------------------------------------------------------------------
# Tool / define_tool tests
# ---------------------------------------------------------------------------

class TestTool(unittest.TestCase):

    def test_tool_creation(self):
        tool = Tool(
            name="add",
            description="Add two numbers",
            parameters={
                "type": "object",
                "properties": {
                    "a": {"type": "number"},
                    "b": {"type": "number"},
                },
                "required": ["a", "b"],
            },
            handler=lambda p: str(p["a"] + p["b"]),
        )
        self.assertEqual(tool.name, "add")
        self.assertEqual(tool.handler({"a": 1, "b": 2}), "3")

    def test_to_schema(self):
        tool = Tool(
            name="greet",
            description="Greet someone",
            parameters={"type": "object", "properties": {"name": {"type": "string"}}},
            handler=lambda p: f"Hello, {p['name']}",
        )
        schema = tool.to_schema()
        self.assertEqual(schema["name"], "greet")
        self.assertEqual(schema["description"], "Greet someone")
        self.assertIn("inputSchema", schema)
        # 'required' should be auto-added
        self.assertIn("required", schema["inputSchema"])

    def test_to_schema_preserves_required(self):
        tool = Tool(
            name="t",
            description="t",
            parameters={"type": "object", "properties": {}, "required": ["x"]},
            handler=lambda p: "",
        )
        schema = tool.to_schema()
        self.assertEqual(schema["inputSchema"]["required"], ["x"])


class TestDefineTool(unittest.TestCase):

    def test_basic_decorator(self):
        @define_tool(description="Add two numbers")
        def add(params):
            return str(params["a"] + params["b"])

        self.assertTrue(hasattr(add, "_tool"))
        self.assertTrue(hasattr(add, "_is_sdk_tool"))
        self.assertEqual(add._tool.name, "add")
        self.assertEqual(add._tool.description, "Add two numbers")

    def test_custom_name(self):
        @define_tool(description="test", name="custom_name")
        def my_func(params):
            return "ok"

        self.assertEqual(my_func._tool.name, "custom_name")

    def test_handler_callable(self):
        @define_tool(description="Echo")
        def echo(params):
            return params.get("text", "")

        result = echo._tool.handler({"text": "hello"})
        self.assertEqual(result, "hello")

    def test_function_still_works(self):
        @define_tool(description="test")
        def func(params):
            return "result"

        # The decorated function should still be callable normally
        self.assertEqual(func({}), "result")

    def test_tool_schema_generation(self):
        @define_tool(description="test")
        def func(params):
            return ""

        schema = func._tool.to_schema()
        self.assertIn("inputSchema", schema)
        self.assertEqual(schema["inputSchema"]["type"], "object")

    def test_pydantic_schema_inference(self):
        """Test that Pydantic models are used for schema inference if available."""
        try:
            from pydantic import BaseModel, Field

            class TestParams(BaseModel):
                name: str = Field(description="The name")
                count: int = Field(default=1, description="Count")

            @define_tool(description="Pydantic test")
            def func(params: TestParams):
                return params.name

            schema = func._tool.parameters
            self.assertIn("properties", schema)
            self.assertIn("name", schema["properties"])
        except ImportError:
            self.skipTest("pydantic not installed")


# ---------------------------------------------------------------------------
# Schema inference tests
# ---------------------------------------------------------------------------

class TestInferSchema(unittest.TestCase):

    def test_no_params(self):
        def func():
            pass
        schema = _infer_schema(func)
        self.assertEqual(schema["type"], "object")
        self.assertEqual(schema["properties"], {})

    def test_untyped_param(self):
        def func(params):
            pass
        schema = _infer_schema(func)
        self.assertEqual(schema["type"], "object")

    def test_dict_annotation(self):
        def func(params: dict):
            pass
        schema = _infer_schema(func)
        self.assertEqual(schema["type"], "object")


# ---------------------------------------------------------------------------
# ProviderConfig tests
# ---------------------------------------------------------------------------

class TestProviderConfig(unittest.TestCase):

    def test_openai_config(self):
        config = ProviderConfig(
            type="openai",
            base_url="https://api.openai.com/v1",
            api_key="sk-test",
        )
        self.assertEqual(config.type, "openai")
        self.assertEqual(config.base_url, "https://api.openai.com/v1")

    def test_anthropic_config(self):
        config = ProviderConfig(
            type="anthropic",
            base_url="https://api.anthropic.com",
            api_key="sk-ant-test",
        )
        self.assertEqual(config.type, "anthropic")

    def test_azure_config(self):
        config = ProviderConfig(
            type="azure",
            base_url="https://myresource.openai.azure.com",
            api_key="abc123",
            azure={"api_version": "2024-10-21"},
        )
        self.assertEqual(config.azure["api_version"], "2024-10-21")

    def test_bearer_token(self):
        config = ProviderConfig(
            type="openai",
            base_url="http://localhost:11434/v1",
            bearer_token="my-token",
        )
        self.assertEqual(config.bearer_token, "my-token")
        self.assertIsNone(config.api_key)


# ---------------------------------------------------------------------------
# Hooks tests
# ---------------------------------------------------------------------------

class TestHooks(unittest.TestCase):

    def test_hooks_default_none(self):
        hooks = Hooks()
        self.assertIsNone(hooks.on_pre_tool_use)
        self.assertIsNone(hooks.on_post_tool_use)
        self.assertIsNone(hooks.on_user_prompt_submitted)

    def test_hooks_with_callables(self):
        pre = lambda inp, inv: HookResult(permission_decision="allow")
        post = lambda inp, inv: None
        prompt = lambda inp, inv: HookResult(modified_prompt="modified")

        hooks = Hooks(
            on_pre_tool_use=pre,
            on_post_tool_use=post,
            on_user_prompt_submitted=prompt,
        )
        self.assertIsNotNone(hooks.on_pre_tool_use)
        self.assertIsNotNone(hooks.on_post_tool_use)
        self.assertIsNotNone(hooks.on_user_prompt_submitted)

    def test_hook_result(self):
        result = HookResult(permission_decision="deny")
        self.assertEqual(result.permission_decision, "deny")
        self.assertIsNone(result.modified_args)

    def test_hook_result_with_modified_args(self):
        result = HookResult(
            permission_decision="allow",
            modified_args={"key": "value"},
        )
        self.assertEqual(result.modified_args, {"key": "value"})

    def test_hook_result_with_modified_prompt(self):
        result = HookResult(modified_prompt="new prompt")
        self.assertEqual(result.modified_prompt, "new prompt")


# ---------------------------------------------------------------------------
# InfiniteSessionConfig tests
# ---------------------------------------------------------------------------

class TestInfiniteSessionConfig(unittest.TestCase):

    def test_defaults(self):
        config = InfiniteSessionConfig()
        self.assertFalse(config.enabled)
        self.assertAlmostEqual(config.background_compaction_threshold, 0.80)
        self.assertAlmostEqual(config.buffer_exhaustion_threshold, 0.95)

    def test_enabled(self):
        config = InfiniteSessionConfig(enabled=True)
        self.assertTrue(config.enabled)

    def test_custom_thresholds(self):
        config = InfiniteSessionConfig(
            enabled=True,
            background_compaction_threshold=0.70,
            buffer_exhaustion_threshold=0.90,
        )
        self.assertAlmostEqual(config.background_compaction_threshold, 0.70)
        self.assertAlmostEqual(config.buffer_exhaustion_threshold, 0.90)


# ---------------------------------------------------------------------------
# SessionConfig tests
# ---------------------------------------------------------------------------

class TestSessionConfig(unittest.TestCase):

    def test_defaults(self):
        config = SessionConfig()
        self.assertIsNone(config.model)
        self.assertTrue(config.streaming)
        self.assertFalse(config.agent_mode)
        self.assertIsNone(config.provider)
        self.assertIsNone(config.hooks)
        self.assertIsNone(config.tools)

    def test_full_config(self):
        tool = Tool(name="t", description="t", parameters={}, handler=lambda p: "")
        config = SessionConfig(
            model="gpt-4.1",
            reasoning_effort="high",
            session_id="test-session",
            tools=[tool],
            system_message="You are helpful.",
            streaming=True,
            provider=ProviderConfig(type="openai", base_url="http://localhost"),
            infinite_sessions=InfiniteSessionConfig(enabled=True),
            hooks=Hooks(),
            agent_mode=True,
            workspace="/tmp/test",
        )
        self.assertEqual(config.model, "gpt-4.1")
        self.assertEqual(config.reasoning_effort, "high")
        self.assertEqual(config.session_id, "test-session")
        self.assertEqual(len(config.tools), 1)
        self.assertTrue(config.agent_mode)


# ---------------------------------------------------------------------------
# Message tests
# ---------------------------------------------------------------------------

class TestMessage(unittest.TestCase):

    def test_user_message(self):
        msg = Message(role="user", content="hello")
        self.assertEqual(msg.role, "user")
        self.assertEqual(msg.content, "hello")
        self.assertIsNone(msg.tool_calls)
        self.assertGreater(msg.timestamp, 0)

    def test_assistant_message(self):
        msg = Message(role="assistant", content="response", tool_calls=[{"id": "1"}])
        self.assertEqual(msg.role, "assistant")
        self.assertEqual(len(msg.tool_calls), 1)

    def test_message_with_attachments(self):
        msg = Message(role="user", content="look at this",
                      attachments=[{"type": "file", "path": "/tmp/img.png"}])
        self.assertEqual(len(msg.attachments), 1)


# ---------------------------------------------------------------------------
# Session tests (without backend)
# ---------------------------------------------------------------------------

class TestSession(unittest.TestCase):

    def _make_session(self, **kwargs):
        """Create a Session with a mocked SDK."""
        sdk = MagicMock(spec=CopilotSDK)
        sdk._client = MagicMock()
        config = SessionConfig(**kwargs)
        return Session(sdk, config)

    def test_session_id_generation(self):
        session = self._make_session()
        self.assertTrue(session.session_id.startswith("session-"))

    def test_custom_session_id(self):
        session = self._make_session(session_id="my-session")
        self.assertEqual(session.session_id, "my-session")

    def test_event_subscription(self):
        session = self._make_session()
        received = []
        session.on(lambda e: received.append(e))

        session._emit(Event(
            type=EventType.ASSISTANT_MESSAGE,
            data=EventData(content="test"),
            session_id=session.session_id,
        ))
        self.assertEqual(len(received), 1)

    def test_get_messages_empty(self):
        session = self._make_session()
        self.assertEqual(session.get_messages(), [])

    def test_is_idle(self):
        session = self._make_session()
        self.assertTrue(session.is_idle)

    def test_tool_registration_from_tool_objects(self):
        tool = Tool(
            name="test_tool",
            description="Test",
            parameters={"type": "object", "properties": {}, "required": []},
            handler=lambda p: "ok",
        )
        session = self._make_session(tools=[tool])
        self.assertIn("test_tool", session._tools)

    def test_tool_registration_from_decorated(self):
        @define_tool(description="Test decorator")
        def my_tool(params):
            return "result"

        session = self._make_session(tools=[my_tool])
        self.assertIn("my_tool", session._tools)

    def test_infinite_session_workspace(self):
        session = self._make_session(
            infinite_sessions=InfiniteSessionConfig(enabled=True),
            session_id="test-inf",
        )
        self.assertIsNotNone(session.workspace_path)
        self.assertIn("test-inf", session.workspace_path)
        # Clean up
        if session.workspace_path and os.path.isdir(session.workspace_path):
            shutil.rmtree(session.workspace_path, ignore_errors=True)

    def test_compact(self):
        session = self._make_session()
        # Add several messages
        for i in range(8):
            session._messages.append(
                Message(role="user" if i % 2 == 0 else "assistant",
                        content=f"message {i}")
            )
        self.assertEqual(len(session._messages), 8)

        events = []
        session.on(lambda e: events.append(e))
        session.compact()

        # After compaction: 1 summary + 4 recent = 5
        self.assertEqual(len(session._messages), 5)
        self.assertEqual(session._messages[0].role, "system")
        self.assertIn("summary", session._messages[0].content.lower())
        self.assertEqual(session._compaction_count, 1)

        # Check compaction events were emitted
        event_types = [e.type for e in events]
        self.assertIn(EventType.SESSION_COMPACTION_START, event_types)
        self.assertIn(EventType.SESSION_COMPACTION_COMPLETE, event_types)

    def test_compact_too_few_messages(self):
        session = self._make_session()
        session._messages.append(Message(role="user", content="hi"))
        session.compact()  # Should not crash with < 4 messages
        self.assertEqual(len(session._messages), 1)

    def test_compact_preserves_system_message(self):
        session = self._make_session()
        # Add a system message (simulating config.system_message injection)
        session._messages.append(
            Message(role="system", content="You are a helpful assistant.")
        )
        for i in range(8):
            session._messages.append(
                Message(role="user" if i % 2 == 0 else "assistant",
                        content=f"message {i}")
            )
        session.compact()

        # Original system message should be preserved
        system_msgs = [m for m in session._messages if m.role == "system"]
        self.assertTrue(
            any("You are a helpful assistant" in m.content for m in system_msgs),
            "Original system message was not preserved after compaction",
        )
        # Summary system message should also be present
        self.assertTrue(
            any("summary" in m.content.lower() for m in system_msgs),
            "Summary system message was not created",
        )

    def test_compact_tool_call_messages(self):
        session = self._make_session()
        # Add messages including one with tool_calls but no text content
        session._messages.append(Message(role="user", content="run the tests"))
        session._messages.append(
            Message(role="assistant", content="",
                    tool_calls=[{"function": {"name": "run_tests", "arguments": "{}"}}])
        )
        session._messages.append(Message(role="tool", content="All tests passed"))
        session._messages.append(Message(role="assistant", content="Tests passed!"))
        session._messages.append(Message(role="user", content="great"))
        session._messages.append(Message(role="assistant", content="happy to help"))
        session._messages.append(Message(role="user", content="bye"))
        session._messages.append(Message(role="assistant", content="goodbye"))
        session.compact()

        # The summary should reference the tool call
        summary_msgs = [m for m in session._messages
                        if m.role == "system" and "summary" in m.content.lower()]
        self.assertEqual(len(summary_msgs), 1)
        self.assertIn("run_tests", summary_msgs[0].content)

    def test_compact_no_event_when_nothing_changed(self):
        session = self._make_session()
        # Exactly 4 non-system messages — nothing to compact
        for i in range(4):
            session._messages.append(
                Message(role="user" if i % 2 == 0 else "assistant",
                        content=f"m{i}")
            )
        events = []
        session.on(lambda e: events.append(e))
        session.compact()

        # No compaction events should fire since nothing changed
        compaction_events = [e for e in events
                            if e.type in (EventType.SESSION_COMPACTION_START,
                                          EventType.SESSION_COMPACTION_COMPLETE)]
        self.assertEqual(len(compaction_events), 0)

    def test_estimate_token_count(self):
        session = self._make_session()
        # 400 chars = ~100 tokens at 4 chars/token
        session._messages.append(Message(role="user", content="x" * 400))
        tokens = session._estimate_token_count()
        self.assertEqual(tokens, 100)

    def test_context_usage_ratio(self):
        session = self._make_session()
        # Default window is 128k tokens. 512k chars = 128k tokens = ratio 1.0
        session._messages.append(Message(role="user", content="x" * 512_000))
        ratio = session._context_usage_ratio()
        self.assertAlmostEqual(ratio, 1.0, places=2)

    def test_auto_compact_disabled_by_default(self):
        session = self._make_session()
        for i in range(20):
            session._messages.append(
                Message(role="user" if i % 2 == 0 else "assistant",
                        content="x" * 50_000)
            )
        result = session._auto_compact_if_needed()
        self.assertFalse(result)
        # Messages unchanged
        self.assertEqual(len(session._messages), 20)

    def test_auto_compact_triggers_at_background_threshold(self):
        session = self._make_session(
            infinite_sessions=InfiniteSessionConfig(
                enabled=True,
                background_compaction_threshold=0.80,
                buffer_exhaustion_threshold=0.95,
            ),
        )
        # Fill to ~80% of 128k window.  128k * 0.85 * 4 chars/token = 435,200 chars
        # Split across several messages to exceed threshold
        for i in range(10):
            session._messages.append(
                Message(role="user" if i % 2 == 0 else "assistant",
                        content="x" * 44_000)
            )
        self.assertGreaterEqual(session._context_usage_ratio(), 0.80)

        events = []
        session.on(lambda e: events.append(e))
        result = session._auto_compact_if_needed()

        self.assertTrue(result)
        self.assertEqual(session._compaction_count, 1)
        # Should have emitted compaction events
        event_types = [e.type for e in events]
        self.assertIn(EventType.SESSION_COMPACTION_START, event_types)
        self.assertIn(EventType.SESSION_COMPACTION_COMPLETE, event_types)

    def test_auto_compact_no_trigger_below_threshold(self):
        session = self._make_session(
            infinite_sessions=InfiniteSessionConfig(
                enabled=True,
                background_compaction_threshold=0.80,
                buffer_exhaustion_threshold=0.95,
            ),
        )
        # Add small messages — well below 80%
        for i in range(6):
            session._messages.append(
                Message(role="user" if i % 2 == 0 else "assistant",
                        content=f"short message {i}")
            )
        self.assertLess(session._context_usage_ratio(), 0.80)

        result = session._auto_compact_if_needed()
        self.assertFalse(result)
        self.assertEqual(len(session._messages), 6)

    # -- LLM-powered summarization tests ----------------------------------

    def test_compact_uses_llm_summary_when_provider_available(self):
        """When a provider is configured, compact() should call it."""
        session = self._make_session(
            provider=ProviderConfig(type="openai", base_url="http://test"),
            model="test-model",
        )
        for i in range(8):
            session._messages.append(
                Message(role="user" if i % 2 == 0 else "assistant",
                        content=f"message {i}")
            )

        # Mock the provider to return a canned summary
        mock_provider = MagicMock()
        mock_provider.chat.return_value = {
            "content": "User discussed 8 messages about testing.",
        }
        session._provider_instance = mock_provider

        events = []
        session.on(lambda e: events.append(e))
        session.compact()

        # Provider should have been called
        mock_provider.chat.assert_called_once()
        call_kwargs = mock_provider.chat.call_args
        # The prompt should contain the default summary prompt text
        prompt_text = call_kwargs[1]["messages"][0]["content"] if call_kwargs[1] else call_kwargs[0][1][0]["content"]
        self.assertIn("conversation summarizer", prompt_text.lower())

        # Summary should contain the LLM output
        summary_msgs = [m for m in session._messages
                        if m.role == "system" and "summary" in m.content.lower()]
        self.assertEqual(len(summary_msgs), 1)
        self.assertIn("User discussed 8 messages", summary_msgs[0].content)

        # Event should report "llm" method
        complete_events = [e for e in events
                          if e.type == EventType.SESSION_COMPACTION_COMPLETE]
        self.assertEqual(len(complete_events), 1)
        self.assertEqual(complete_events[0].data.raw["summary_method"], "llm")

    def test_compact_falls_back_on_provider_failure(self):
        """If the provider call fails, fall back to truncation."""
        session = self._make_session(
            provider=ProviderConfig(type="openai", base_url="http://test"),
            model="test-model",
        )
        for i in range(8):
            session._messages.append(
                Message(role="user" if i % 2 == 0 else "assistant",
                        content=f"message {i}")
            )

        mock_provider = MagicMock()
        mock_provider.chat.side_effect = RuntimeError("API error 500")
        session._provider_instance = mock_provider

        events = []
        session.on(lambda e: events.append(e))
        session.compact()

        # Should still compact successfully via truncation
        self.assertEqual(session._compaction_count, 1)
        summary_msgs = [m for m in session._messages
                        if m.role == "system" and "summary" in m.content.lower()]
        self.assertEqual(len(summary_msgs), 1)

        # Event should report "truncation" method
        complete_events = [e for e in events
                          if e.type == EventType.SESSION_COMPACTION_COMPLETE]
        self.assertEqual(complete_events[0].data.raw["summary_method"], "truncation")

    def test_compact_truncation_when_no_provider(self):
        """Without a provider, compact() uses truncation and reports it."""
        session = self._make_session()  # No provider
        for i in range(8):
            session._messages.append(
                Message(role="user" if i % 2 == 0 else "assistant",
                        content=f"message {i}")
            )

        events = []
        session.on(lambda e: events.append(e))
        session.compact()

        complete_events = [e for e in events
                          if e.type == EventType.SESSION_COMPACTION_COMPLETE]
        self.assertEqual(complete_events[0].data.raw["summary_method"], "truncation")

    def test_compact_custom_summary_prompt(self):
        """A custom summary_prompt on InfiniteSessionConfig is used."""
        custom_prompt = "Summarize in haiku form:\n"
        session = self._make_session(
            provider=ProviderConfig(type="openai", base_url="http://test"),
            model="test-model",
            infinite_sessions=InfiniteSessionConfig(
                enabled=True,
                summary_prompt=custom_prompt,
            ),
        )
        for i in range(8):
            session._messages.append(
                Message(role="user" if i % 2 == 0 else "assistant",
                        content=f"message {i}")
            )

        mock_provider = MagicMock()
        mock_provider.chat.return_value = {"content": "A haiku summary"}
        session._provider_instance = mock_provider

        session.compact()

        # The custom prompt should appear in the call
        call_args = mock_provider.chat.call_args
        prompt_text = call_args[1]["messages"][0]["content"] if call_args[1] else call_args[0][1][0]["content"]
        self.assertIn("haiku", prompt_text.lower())

    def test_format_messages_for_summary(self):
        """_format_messages_for_summary produces a readable transcript."""
        from copilot_sdk import Session
        messages = [
            Message(role="user", content="Fix the bug"),
            Message(role="assistant", content="",
                    tool_calls=[{"function": {"name": "edit_file", "arguments": "{}"}}]),
            Message(role="assistant", content="Done!"),
        ]
        transcript = Session._format_messages_for_summary(messages)
        self.assertIn("user: Fix the bug", transcript)
        self.assertIn("[called tools: edit_file]", transcript)
        self.assertIn("assistant: Done!", transcript)

    def test_compact_llm_returns_empty(self):
        """If the LLM returns empty content, fall back to truncation."""
        session = self._make_session(
            provider=ProviderConfig(type="openai", base_url="http://test"),
            model="test-model",
        )
        for i in range(8):
            session._messages.append(
                Message(role="user" if i % 2 == 0 else "assistant",
                        content=f"message {i}")
            )

        mock_provider = MagicMock()
        mock_provider.chat.return_value = {"content": ""}  # Empty response
        session._provider_instance = mock_provider

        events = []
        session.on(lambda e: events.append(e))
        session.compact()

        # Should fall back to truncation
        complete_events = [e for e in events
                          if e.type == EventType.SESSION_COMPACTION_COMPLETE]
        self.assertEqual(complete_events[0].data.raw["summary_method"], "truncation")

    def test_destroy(self):
        session = self._make_session()
        session.conversation_id = "conv-123"
        session._messages.append(Message(role="user", content="hi"))

        session.destroy()
        self.assertIsNone(session.conversation_id)
        self.assertEqual(len(session._messages), 0)

    def test_hooks_on_user_prompt(self):
        """Test that on_user_prompt_submitted hook modifies the prompt."""
        def modify_prompt(input_data, invocation):
            return HookResult(modified_prompt=input_data["prompt"] + " MODIFIED")

        session = self._make_session(
            hooks=Hooks(on_user_prompt_submitted=modify_prompt),
            provider=ProviderConfig(type="openai", base_url="http://test"),
            model="test-model",
        )

        # Mock the provider to capture what prompt was sent
        with patch("sdk_providers.create_provider") as mock_create:
            mock_provider = MagicMock()
            mock_provider.chat.return_value = {"content": "reply", "tool_calls": []}
            mock_create.return_value = mock_provider

            session.send("hello")

            # Check the messages recorded include the modified prompt
            user_msg = session._messages[0]
            self.assertEqual(user_msg.content, "hello MODIFIED")


# ---------------------------------------------------------------------------
# Provider factory tests
# ---------------------------------------------------------------------------

class TestCreateProvider(unittest.TestCase):

    def test_create_openai(self):
        config = ProviderConfig(type="openai", base_url="http://test")
        provider = create_provider(config)
        self.assertIsInstance(provider, OpenAIProvider)

    def test_create_anthropic(self):
        config = ProviderConfig(type="anthropic", base_url="http://test")
        provider = create_provider(config)
        self.assertIsInstance(provider, AnthropicProvider)

    def test_create_azure(self):
        config = ProviderConfig(
            type="azure", base_url="http://test",
            azure={"api_version": "2024-10-21"},
        )
        provider = create_provider(config)
        self.assertIsInstance(provider, AzureProvider)

    def test_create_from_dict(self):
        config = {"type": "openai", "base_url": "http://test", "api_key": "sk-123"}
        provider = create_provider(config)
        self.assertIsInstance(provider, OpenAIProvider)
        self.assertEqual(provider.api_key, "sk-123")

    def test_default_to_openai(self):
        config = ProviderConfig(type="unknown", base_url="http://test")
        provider = create_provider(config)
        self.assertIsInstance(provider, OpenAIProvider)


# ---------------------------------------------------------------------------
# Provider message conversion tests
# ---------------------------------------------------------------------------

class TestOpenAIProvider(unittest.TestCase):

    def test_convert_messages_basic(self):
        provider = OpenAIProvider("http://test")
        messages = [
            {"role": "user", "content": "hello"},
            {"role": "assistant", "content": "hi"},
        ]
        converted = provider._convert_messages(messages)
        self.assertEqual(len(converted), 2)
        self.assertEqual(converted[0]["role"], "user")
        self.assertEqual(converted[1]["role"], "assistant")

    def test_convert_messages_tool_result(self):
        provider = OpenAIProvider("http://test")
        messages = [
            {"role": "tool", "tool_call_id": "tc1", "content": "result"},
        ]
        converted = provider._convert_messages(messages)
        self.assertEqual(converted[0]["role"], "tool")
        self.assertEqual(converted[0]["tool_call_id"], "tc1")

    def test_convert_messages_tool_calls(self):
        provider = OpenAIProvider("http://test")
        messages = [{
            "role": "assistant",
            "content": "",
            "tool_calls": [{"id": "tc1", "function": {"name": "test"}}],
        }]
        converted = provider._convert_messages(messages)
        self.assertIn("tool_calls", converted[0])

    def test_parse_response(self):
        provider = OpenAIProvider("http://test")
        resp = {
            "choices": [{
                "message": {"content": "hello", "tool_calls": []},
                "finish_reason": "stop",
            }],
            "usage": {"total_tokens": 10},
        }
        parsed = provider._parse_response(resp)
        self.assertEqual(parsed["content"], "hello")
        self.assertEqual(parsed["tool_calls"], [])
        self.assertEqual(parsed["finish_reason"], "stop")


class TestAnthropicProvider(unittest.TestCase):

    def test_convert_messages_basic(self):
        provider = AnthropicProvider("http://test")
        messages = [
            {"role": "user", "content": "hello"},
            {"role": "assistant", "content": "hi"},
        ]
        converted = provider._convert_messages(messages)
        self.assertEqual(len(converted), 2)

    def test_convert_messages_tool_result(self):
        provider = AnthropicProvider("http://test")
        messages = [
            {"role": "tool", "tool_call_id": "tu1", "content": "result text"},
        ]
        converted = provider._convert_messages(messages)
        self.assertEqual(converted[0]["role"], "user")
        content = converted[0]["content"]
        self.assertEqual(content[0]["type"], "tool_result")
        self.assertEqual(content[0]["tool_use_id"], "tu1")

    def test_convert_messages_tool_calls(self):
        provider = AnthropicProvider("http://test")
        messages = [{
            "role": "assistant",
            "content": "thinking...",
            "tool_calls": [{
                "id": "tu1",
                "function": {"name": "search", "arguments": '{"q": "test"}'},
            }],
        }]
        converted = provider._convert_messages(messages)
        content = converted[0]["content"]
        self.assertEqual(content[0]["type"], "text")
        self.assertEqual(content[1]["type"], "tool_use")
        self.assertEqual(content[1]["name"], "search")
        self.assertEqual(content[1]["input"], {"q": "test"})

    def test_convert_tools(self):
        provider = AnthropicProvider("http://test")
        openai_tools = [{
            "type": "function",
            "function": {
                "name": "search",
                "description": "Search the web",
                "parameters": {"type": "object", "properties": {"q": {"type": "string"}}},
            },
        }]
        converted = provider._convert_tools(openai_tools)
        self.assertEqual(len(converted), 1)
        self.assertEqual(converted[0]["name"], "search")
        self.assertIn("input_schema", converted[0])

    def test_parse_response_text(self):
        provider = AnthropicProvider("http://test")
        resp = {
            "content": [{"type": "text", "text": "Hello!"}],
            "stop_reason": "end_turn",
        }
        parsed = provider._parse_response(resp)
        self.assertEqual(parsed["content"], "Hello!")
        self.assertEqual(parsed["tool_calls"], [])

    def test_parse_response_tool_use(self):
        provider = AnthropicProvider("http://test")
        resp = {
            "content": [
                {"type": "text", "text": "Let me search."},
                {"type": "tool_use", "id": "tu1", "name": "search",
                 "input": {"q": "test"}},
            ],
            "stop_reason": "tool_use",
        }
        parsed = provider._parse_response(resp)
        self.assertEqual(parsed["content"], "Let me search.")
        self.assertEqual(len(parsed["tool_calls"]), 1)
        self.assertEqual(parsed["tool_calls"][0]["function"]["name"], "search")

    def test_convert_multipart_image(self):
        provider = AnthropicProvider("http://test")
        messages = [{
            "role": "user",
            "content": [
                {"type": "text", "text": "What's this?"},
                {"type": "image_url", "image_url": {
                    "url": "data:image/png;base64,AAAA",
                }},
            ],
        }]
        converted = provider._convert_messages(messages)
        content = converted[0]["content"]
        self.assertEqual(content[0]["type"], "text")
        self.assertEqual(content[1]["type"], "image")
        self.assertEqual(content[1]["source"]["type"], "base64")
        self.assertEqual(content[1]["source"]["media_type"], "image/png")
        self.assertEqual(content[1]["source"]["data"], "AAAA")


class TestAzureProvider(unittest.TestCase):

    def test_inherits_openai(self):
        provider = AzureProvider("http://test", azure_config={"api_version": "2024-10-21"})
        self.assertIsInstance(provider, OpenAIProvider)
        self.assertEqual(provider.api_version, "2024-10-21")

    def test_default_api_version(self):
        provider = AzureProvider("http://test")
        self.assertEqual(provider.api_version, "2024-10-21")


# ---------------------------------------------------------------------------
# CopilotSDK tests (without real server)
# ---------------------------------------------------------------------------

class TestCopilotSDK(unittest.TestCase):

    def _make_sdk(self, **overrides):
        """Create a CopilotSDK with mocked internals (no real server)."""
        sdk = CopilotSDK.__new__(CopilotSDK)
        EventEmitter.__init__(sdk)
        sdk.cli_path = None
        sdk.cli_url = None
        sdk.cwd = "/tmp"
        sdk.port = None
        sdk.use_stdio = True
        sdk.auto_start = False
        sdk.auto_restart = False
        sdk.github_token = None
        sdk.use_logged_in_user = True
        sdk._client = None
        sdk._sessions = {}
        sdk._started = False
        sdk._restart_lock = threading.Lock()
        sdk._health_thread = None
        sdk.log_level = "info"
        # Use a temp DB so tests don't touch the real store
        sdk._store = SessionStore(db_path=os.path.join(
            tempfile.mkdtemp(), "test_sessions.db"))
        for k, v in overrides.items():
            setattr(sdk, k, v)
        return sdk

    def test_default_init(self):
        sdk = self._make_sdk()
        self.assertFalse(sdk.is_running)
        self.assertEqual(sdk.list_sessions(), [])

    def test_create_session_byok(self):
        """BYOK sessions don't need the Copilot backend."""
        sdk = self._make_sdk()
        config = SessionConfig(
            model="gpt-4.1",
            provider=ProviderConfig(type="openai", base_url="http://test"),
        )
        session = sdk.create_session(config)
        self.assertIsInstance(session, Session)
        self.assertIn(session.session_id, sdk._sessions)

    def test_list_sessions(self):
        sdk = self._make_sdk()
        config = SessionConfig(
            provider=ProviderConfig(type="openai", base_url="http://test"),
        )
        s1 = sdk.create_session(config)
        s2 = sdk.create_session(config)

        sessions = sdk.list_sessions()
        self.assertEqual(len(sessions), 2)

    def test_get_session(self):
        sdk = self._make_sdk()
        config = SessionConfig(
            session_id="test-get",
            provider=ProviderConfig(type="openai", base_url="http://test"),
        )
        session = sdk.create_session(config)
        found = sdk.get_session("test-get")
        self.assertEqual(found, session)
        self.assertIsNone(sdk.get_session("nonexistent"))

    def test_foreground_session(self):
        sdk = self._make_sdk()
        config = SessionConfig(
            provider=ProviderConfig(type="openai", base_url="http://test"),
        )
        s1 = sdk.create_session(config)
        s2 = sdk.create_session(config)

        # Last created should be foreground
        self.assertEqual(sdk.get_foreground_session_id(), s2.session_id)

        # Set s1 as foreground
        sdk.set_foreground_session_id(s1.session_id)
        self.assertEqual(sdk.get_foreground_session_id(), s1.session_id)

    def test_stop(self):
        sdk = self._make_sdk(_client=MagicMock(), _started=True)
        config = SessionConfig(
            provider=ProviderConfig(type="openai", base_url="http://test"),
        )
        sdk.create_session(config)
        self.assertEqual(len(sdk._sessions), 1)

        sdk.stop()
        self.assertFalse(sdk._started)
        self.assertEqual(len(sdk._sessions), 0)
        self.assertIsNone(sdk._client)


# ---------------------------------------------------------------------------
# Image encoding tests
# ---------------------------------------------------------------------------

class TestEncodeImage(unittest.TestCase):

    def test_encode_real_file(self):
        # Create a small test image (1x1 PNG)
        import base64
        # Minimal valid PNG: 1x1 red pixel
        png_data = base64.b64decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQ"
            "DwADhQGAWjR9awAAAABJRU5ErkJggg=="
        )
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            f.write(png_data)
            path = f.name

        try:
            result = _encode_image(path)
            self.assertIsNotNone(result)
            self.assertEqual(result["type"], "image_url")
            self.assertTrue(result["image_url"]["url"].startswith("data:image/png;base64,"))
        finally:
            os.unlink(path)

    def test_encode_nonexistent_file(self):
        result = _encode_image("/nonexistent/file.png")
        self.assertIsNone(result)

    def test_encode_non_image(self):
        with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as f:
            f.write(b"not an image")
            path = f.name

        try:
            result = _encode_image(path)
            # text/plain is not image/*, so returns None
            self.assertIsNone(result)
        finally:
            os.unlink(path)


# ---------------------------------------------------------------------------
# Provider tool loop integration test
# ---------------------------------------------------------------------------

class TestProviderToolLoop(unittest.TestCase):

    def test_tool_loop_no_tools(self):
        """Provider response with no tool calls returns content directly."""
        session = Session.__new__(Session)
        EventEmitter.__init__(session)
        session.config = SessionConfig(model="test")
        session._tools = {}
        session.session_id = "test"

        mock_provider = MagicMock()
        mock_provider.chat.return_value = {"content": "hello", "tool_calls": []}

        result = session._provider_tool_loop(
            mock_provider, [{"role": "user", "content": "hi"}],
            [], lambda x: None,
        )
        self.assertEqual(result, "hello")
        mock_provider.chat.assert_called_once()

    def test_tool_loop_with_tool_call(self):
        """Provider response with tool calls executes tools and loops."""
        session = Session.__new__(Session)
        EventEmitter.__init__(session)
        session.config = SessionConfig(model="test")
        session._tools = {
            "add": Tool(
                name="add",
                description="Add numbers",
                parameters={},
                handler=lambda p: str(int(p["a"]) + int(p["b"])),
            ),
        }
        session.session_id = "test"

        mock_provider = MagicMock()
        # First call returns a tool call, second returns final answer
        mock_provider.chat.side_effect = [
            {
                "content": "",
                "tool_calls": [{
                    "id": "tc1",
                    "function": {"name": "add", "arguments": '{"a": 1, "b": 2}'},
                }],
            },
            {"content": "The answer is 3", "tool_calls": []},
        ]

        result = session._provider_tool_loop(
            mock_provider, [{"role": "user", "content": "add 1+2"}],
            [{"type": "function", "function": {"name": "add"}}],
            lambda x: None,
        )
        self.assertEqual(result, "The answer is 3")
        self.assertEqual(mock_provider.chat.call_count, 2)

    def test_tool_loop_pre_hook_deny(self):
        """Pre-tool hook with deny blocks tool execution."""
        session = Session.__new__(Session)
        EventEmitter.__init__(session)
        session.config = SessionConfig(
            model="test",
            hooks=Hooks(on_pre_tool_use=lambda inp, inv: HookResult(permission_decision="deny")),
        )
        session._tools = {
            "dangerous": Tool(name="dangerous", description="", parameters={},
                              handler=lambda p: "should not run"),
        }
        session.session_id = "test"

        mock_provider = MagicMock()
        mock_provider.chat.side_effect = [
            {
                "content": "",
                "tool_calls": [{
                    "id": "tc1",
                    "function": {"name": "dangerous", "arguments": "{}"},
                }],
            },
            {"content": "Tool was denied", "tool_calls": []},
        ]

        result = session._provider_tool_loop(
            mock_provider, [], [], lambda x: None,
        )
        self.assertEqual(result, "Tool was denied")


# ---------------------------------------------------------------------------
# CopilotClient attachment encoding test
# ---------------------------------------------------------------------------

class TestClientAttachmentEncoding(unittest.TestCase):

    def test_encode_attachments_file(self):
        from copilot_client import CopilotClient
        import base64

        # Create a temp file
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            f.write(b"\x89PNG\r\n\x1a\n")  # PNG magic bytes
            path = f.name

        try:
            result = CopilotClient._encode_attachments([
                {"type": "file", "path": path},
            ])
            self.assertEqual(len(result), 1)
            self.assertIn("uri", result[0])
            self.assertIn("data", result[0])
            self.assertIn("mimeType", result[0])
            self.assertEqual(result[0]["name"], os.path.basename(path))
        finally:
            os.unlink(path)

    def test_encode_attachments_passthrough(self):
        from copilot_client import CopilotClient
        result = CopilotClient._encode_attachments([
            {"uri": "file:///test.png", "mimeType": "image/png"},
        ])
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["uri"], "file:///test.png")

    def test_encode_attachments_missing_file(self):
        from copilot_client import CopilotClient
        result = CopilotClient._encode_attachments([
            {"type": "file", "path": "/nonexistent/file.png"},
        ])
        self.assertEqual(len(result), 0)


# ---------------------------------------------------------------------------
# SessionStore tests
# ---------------------------------------------------------------------------

class TestSessionStore(unittest.TestCase):
    """Test the SQLite-backed session store."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.db_path = os.path.join(self.tmpdir, "test_sessions.db")
        self.store = SessionStore(db_path=self.db_path)

    def tearDown(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def test_db_created(self):
        self.assertTrue(os.path.isfile(self.db_path))

    def test_save_and_get_session(self):
        self.store.save_session("s1", name="test-session", model="gpt-4.1",
                                provider_type="openai")
        record = self.store.get_session("s1")
        self.assertIsNotNone(record)
        self.assertEqual(record["session_id"], "s1")
        self.assertEqual(record["name"], "test-session")
        self.assertEqual(record["model"], "gpt-4.1")
        self.assertEqual(record["provider_type"], "openai")

    def test_get_session_not_found(self):
        result = self.store.get_session("nonexistent")
        self.assertIsNone(result)

    def test_save_session_upsert(self):
        self.store.save_session("s1", name="original", model="gpt-4.1",
                                provider_type="openai", workspace="/tmp/old",
                                system_message="old system msg")
        self.store.save_session("s1", name="updated", model="gpt-4o",
                                provider_type="anthropic", workspace="/tmp/new",
                                system_message="new system msg")
        record = self.store.get_session("s1")
        self.assertEqual(record["name"], "updated")
        self.assertEqual(record["model"], "gpt-4o")
        self.assertEqual(record["provider_type"], "anthropic")
        self.assertEqual(record["workspace"], "/tmp/new")
        self.assertEqual(record["system_message"], "new system msg")

    def test_find_session_by_name(self):
        self.store.save_session("s1", name="my-chat", model="gpt-4.1")
        self.store.save_session("s2", name="other-chat", model="gpt-4.1")
        result = self.store.find_session("my-chat")
        self.assertIsNotNone(result)
        self.assertEqual(result["session_id"], "s1")

    def test_find_session_by_id_prefix(self):
        self.store.save_session("session-abc123", name="test")
        result = self.store.find_session("session-abc")
        self.assertIsNotNone(result)
        self.assertEqual(result["session_id"], "session-abc123")

    def test_find_session_not_found(self):
        result = self.store.find_session("nonexistent")
        self.assertIsNone(result)

    def test_list_sessions_empty(self):
        result = self.store.list_sessions()
        self.assertEqual(result, [])

    def test_list_sessions_ordered(self):
        self.store.save_session("s1", name="first")
        time.sleep(0.05)
        self.store.save_session("s2", name="second")
        sessions = self.store.list_sessions()
        self.assertEqual(len(sessions), 2)
        # Most recently updated should be first
        self.assertEqual(sessions[0]["session_id"], "s2")
        self.assertEqual(sessions[1]["session_id"], "s1")

    def test_list_sessions_with_message_count(self):
        self.store.save_session("s1", name="test")
        self.store.save_message("s1", "user", "hello")
        self.store.save_message("s1", "assistant", "hi")
        sessions = self.store.list_sessions()
        self.assertEqual(sessions[0]["message_count"], 2)

    def test_list_sessions_limit(self):
        for i in range(10):
            self.store.save_session(f"s{i}")
            time.sleep(0.01)
        sessions = self.store.list_sessions(limit=5)
        self.assertEqual(len(sessions), 5)

    def test_delete_session(self):
        self.store.save_session("s1", name="test")
        self.store.save_message("s1", "user", "hello")
        result = self.store.delete_session("s1")
        self.assertTrue(result)
        self.assertIsNone(self.store.get_session("s1"))
        # Messages should be cascade-deleted
        self.assertEqual(self.store.get_messages("s1"), [])

    def test_delete_session_not_found(self):
        result = self.store.delete_session("nonexistent")
        self.assertFalse(result)

    def test_clear_old_sessions(self):
        self.store.save_session("old")
        # Manually set the updated_at to 60 days ago
        import sqlite3
        conn = sqlite3.connect(self.db_path)
        old_time = time.time() - (60 * 86400)
        conn.execute("UPDATE sessions SET updated_at = ? WHERE session_id = ?",
                      (old_time, "old"))
        conn.commit()
        conn.close()

        self.store.save_session("recent")
        count = self.store.clear_old_sessions(max_age_days=30)
        self.assertEqual(count, 1)
        self.assertIsNone(self.store.get_session("old"))
        self.assertIsNotNone(self.store.get_session("recent"))

    def test_touch_session(self):
        self.store.save_session("s1")
        record_before = self.store.get_session("s1")
        time.sleep(0.05)
        self.store.touch_session("s1")
        record_after = self.store.get_session("s1")
        self.assertGreater(record_after["updated_at"], record_before["updated_at"])

    # --- Message tests ---

    def test_save_and_get_messages(self):
        self.store.save_session("s1")
        self.store.save_message("s1", "user", "hello")
        self.store.save_message("s1", "assistant", "hi there")
        messages = self.store.get_messages("s1")
        self.assertEqual(len(messages), 2)
        self.assertEqual(messages[0]["role"], "user")
        self.assertEqual(messages[0]["content"], "hello")
        self.assertEqual(messages[1]["role"], "assistant")
        self.assertEqual(messages[1]["content"], "hi there")

    def test_messages_ordered_by_timestamp(self):
        self.store.save_session("s1")
        self.store.save_message("s1", "user", "first", timestamp=100.0)
        self.store.save_message("s1", "assistant", "second", timestamp=200.0)
        self.store.save_message("s1", "user", "third", timestamp=300.0)
        messages = self.store.get_messages("s1")
        self.assertEqual(messages[0]["content"], "first")
        self.assertEqual(messages[1]["content"], "second")
        self.assertEqual(messages[2]["content"], "third")

    def test_message_with_tool_calls(self):
        self.store.save_session("s1")
        tool_calls = [{"id": "tc1", "function": {"name": "search", "arguments": "{}"}}]
        self.store.save_message("s1", "assistant", "", tool_calls=tool_calls)
        messages = self.store.get_messages("s1")
        self.assertEqual(len(messages), 1)
        self.assertEqual(messages[0]["tool_calls"][0]["id"], "tc1")

    def test_message_with_attachments(self):
        self.store.save_session("s1")
        attachments = [{"type": "file", "path": "/tmp/img.png"}]
        self.store.save_message("s1", "user", "look", attachments=attachments)
        messages = self.store.get_messages("s1")
        self.assertEqual(messages[0]["attachments"][0]["type"], "file")

    def test_get_messages_with_limit(self):
        self.store.save_session("s1")
        for i in range(10):
            self.store.save_message("s1", "user", f"msg {i}")
        messages = self.store.get_messages("s1", limit=3)
        self.assertEqual(len(messages), 3)

    def test_get_message_count(self):
        self.store.save_session("s1")
        self.store.save_message("s1", "user", "a")
        self.store.save_message("s1", "user", "b")
        self.store.save_message("s1", "user", "c")
        self.assertEqual(self.store.get_message_count("s1"), 3)

    def test_clear_messages(self):
        self.store.save_session("s1")
        self.store.save_message("s1", "user", "a")
        self.store.save_message("s1", "user", "b")
        count = self.store.clear_messages("s1")
        self.assertEqual(count, 2)
        self.assertEqual(self.store.get_messages("s1"), [])
        # Session record should still exist
        self.assertIsNotNone(self.store.get_session("s1"))

    def test_search_messages(self):
        self.store.save_session("s1", name="chat")
        self.store.save_message("s1", "user", "tell me about Python")
        self.store.save_message("s1", "assistant", "Python is a programming language")
        self.store.save_session("s2", name="other")
        self.store.save_message("s2", "user", "tell me about Java")

        results = self.store.search_messages("Python")
        self.assertEqual(len(results), 2)  # Both messages mention Python

    def test_search_messages_no_results(self):
        self.store.save_session("s1")
        self.store.save_message("s1", "user", "hello")
        results = self.store.search_messages("nonexistent_term_xyz")
        self.assertEqual(len(results), 0)

    def test_search_messages_escapes_wildcards(self):
        """Ensure SQL wildcards in query are treated as literal characters."""
        self.store.save_session("s1")
        self.store.save_message("s1", "user", "100% done")
        self.store.save_message("s1", "user", "file_name.txt")
        self.store.save_message("s1", "user", "something else")
        # '%' should match the literal percent, not act as a wildcard
        results = self.store.search_messages("100%")
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["content"], "100% done")
        # '_' should match the literal underscore, not any single char
        results = self.store.search_messages("file_name")
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["content"], "file_name.txt")

    def test_save_message_touches_session(self):
        self.store.save_session("s1")
        record_before = self.store.get_session("s1")
        time.sleep(0.05)
        self.store.save_message("s1", "user", "hello")
        record_after = self.store.get_session("s1")
        self.assertGreater(record_after["updated_at"], record_before["updated_at"])

    # --- Utility tests ---

    def test_db_size(self):
        size = self.store.db_size()
        self.assertNotEqual(size, "unknown")
        # Should be a string like "32.0 KB"
        self.assertTrue(any(unit in size for unit in ("B", "KB", "MB", "GB")))

    def test_config_json(self):
        config = {"temperature": 0.7, "max_tokens": 1000}
        self.store.save_session("s1", config=config)
        record = self.store.get_session("s1")
        self.assertIsNotNone(record["config_json"])
        parsed = json.loads(record["config_json"])
        self.assertEqual(parsed["temperature"], 0.7)


# ---------------------------------------------------------------------------
# SDK + Store integration tests
# ---------------------------------------------------------------------------

class TestSDKSessionPersistence(unittest.TestCase):
    """Test that SDK sessions are persisted via the store."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.db_path = os.path.join(self.tmpdir, "test_sessions.db")

    def tearDown(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def _make_sdk(self):
        sdk = CopilotSDK.__new__(CopilotSDK)
        EventEmitter.__init__(sdk)
        sdk.cli_path = None
        sdk.cli_url = None
        sdk.cwd = "/tmp"
        sdk.port = None
        sdk.use_stdio = True
        sdk.auto_start = False
        sdk.auto_restart = False
        sdk.github_token = None
        sdk.use_logged_in_user = True
        sdk._client = None
        sdk._sessions = {}
        sdk._started = False
        sdk._restart_lock = threading.Lock()
        sdk._health_thread = None
        sdk.log_level = "info"
        sdk._store = SessionStore(db_path=self.db_path)
        return sdk

    def test_create_session_persists(self):
        sdk = self._make_sdk()
        config = SessionConfig(
            model="gpt-4.1",
            provider=ProviderConfig(type="openai", base_url="http://test"),
        )
        session = sdk.create_session(config, name="my-chat")

        # Check it was saved to the DB
        record = sdk._store.get_session(session.session_id)
        self.assertIsNotNone(record)
        self.assertEqual(record["name"], "my-chat")
        self.assertEqual(record["model"], "gpt-4.1")

    def test_resume_session(self):
        sdk = self._make_sdk()
        config = SessionConfig(
            model="gpt-4.1",
            provider=ProviderConfig(type="openai", base_url="http://test"),
        )
        session = sdk.create_session(config, name="resume-test")

        # Manually save some messages
        sdk._store.save_message(session.session_id, "user", "hello")
        sdk._store.save_message(session.session_id, "assistant", "hi there")

        # Clear in-memory sessions
        sdk._sessions.clear()

        # Resume by name
        resumed = sdk.resume_session("resume-test")
        self.assertIsNotNone(resumed)
        self.assertEqual(resumed.session_id, session.session_id)
        messages = resumed.get_messages()
        self.assertEqual(len(messages), 2)
        self.assertEqual(messages[0].content, "hello")
        self.assertEqual(messages[1].content, "hi there")

    def test_resume_session_by_id_prefix(self):
        sdk = self._make_sdk()
        config = SessionConfig(
            session_id="session-abc123def",
            provider=ProviderConfig(type="openai", base_url="http://test"),
        )
        sdk.create_session(config)

        sdk._sessions.clear()
        resumed = sdk.resume_session("session-abc")
        self.assertIsNotNone(resumed)
        self.assertEqual(resumed.session_id, "session-abc123def")

    def test_resume_session_not_found(self):
        sdk = self._make_sdk()
        result = sdk.resume_session("nonexistent")
        self.assertIsNone(result)

    def test_message_auto_persisted_on_send(self):
        sdk = self._make_sdk()
        config = SessionConfig(
            model="test",
            provider=ProviderConfig(type="openai", base_url="http://test"),
        )
        session = sdk.create_session(config)

        with patch("sdk_providers.create_provider") as mock_create:
            mock_provider = MagicMock()
            mock_provider.chat.return_value = {"content": "reply", "tool_calls": []}
            mock_create.return_value = mock_provider

            session.send("hello")

        # Check messages were saved to DB
        db_messages = sdk._store.get_messages(session.session_id)
        self.assertEqual(len(db_messages), 2)  # user + assistant
        self.assertEqual(db_messages[0]["role"], "user")
        self.assertEqual(db_messages[0]["content"], "hello")
        self.assertEqual(db_messages[1]["role"], "assistant")
        self.assertEqual(db_messages[1]["content"], "reply")


# ---------------------------------------------------------------------------
# Slash command tests
# ---------------------------------------------------------------------------

class TestSlashCommands(unittest.TestCase):
    """Test the _handle_slash_command function."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.db_path = os.path.join(self.tmpdir, "test_sessions.db")
        self.sdk = CopilotSDK.__new__(CopilotSDK)
        EventEmitter.__init__(self.sdk)
        self.sdk.cli_path = None
        self.sdk.cli_url = None
        self.sdk.cwd = "/tmp"
        self.sdk.port = None
        self.sdk.use_stdio = True
        self.sdk.auto_start = False
        self.sdk.auto_restart = False
        self.sdk.github_token = None
        self.sdk.use_logged_in_user = True
        self.sdk._client = None
        self.sdk._sessions = {}
        self.sdk._started = False
        self.sdk._restart_lock = threading.Lock()
        self.sdk._health_thread = None
        self.sdk.log_level = "info"
        self.sdk._store = SessionStore(db_path=self.db_path)

    def tearDown(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def _make_session(self):
        config = SessionConfig(
            model="test",
            provider=ProviderConfig(type="openai", base_url="http://test"),
        )
        return self.sdk.create_session(config)

    def test_quit_command(self):
        session = self._make_session()
        result = _handle_slash_command("/quit", session, self.sdk, None)
        self.assertEqual(result, "break")

    def test_exit_command(self):
        session = self._make_session()
        result = _handle_slash_command("/exit", session, self.sdk, None)
        self.assertEqual(result, "break")

    def test_help_command(self):
        session = self._make_session()
        result = _handle_slash_command("/help", session, self.sdk, None)
        self.assertEqual(result, "continue")

    def test_history_command(self):
        session = self._make_session()
        session._messages.append(Message(role="user", content="test msg"))
        result = _handle_slash_command("/history", session, self.sdk, None)
        self.assertEqual(result, "continue")

    def test_list_command(self):
        session = self._make_session()
        result = _handle_slash_command("/list", session, self.sdk, None)
        self.assertEqual(result, "continue")

    def test_compact_command(self):
        session = self._make_session()
        for i in range(8):
            session._messages.append(Message(role="user", content=f"msg {i}"))
        result = _handle_slash_command("/compact", session, self.sdk, None)
        self.assertEqual(result, "continue")
        # Should have compacted
        self.assertLess(len(session.get_messages()), 8)

    def test_new_command(self):
        session = self._make_session()
        old_id = session.session_id
        result = _handle_slash_command("/new my-new-session", session, self.sdk, None)
        self.assertEqual(result, "continue")
        # Session should have a new ID
        self.assertNotEqual(session.session_id, old_id)

    def test_unknown_slash_command(self):
        session = self._make_session()
        result = _handle_slash_command("/unknown_cmd", session, self.sdk, None)
        self.assertIsNone(result)

    def test_switch_no_arg(self):
        session = self._make_session()
        result = _handle_slash_command("/switch", session, self.sdk, None)
        self.assertEqual(result, "continue")  # prints usage

    def test_switch_not_found(self):
        session = self._make_session()
        result = _handle_slash_command("/switch nonexistent", session, self.sdk, None)
        self.assertEqual(result, "continue")  # prints not found

    def test_switch_to_existing(self):
        # Create two sessions
        s1 = self._make_session()
        s1_id = s1.session_id
        config2 = SessionConfig(
            session_id="target-session",
            model="test",
            provider=ProviderConfig(type="openai", base_url="http://test"),
        )
        s2 = self.sdk.create_session(config2, name="target")
        self.sdk._store.save_message("target-session", "user", "old msg")

        result = _handle_slash_command("/switch target", s1, self.sdk, None)
        self.assertEqual(result, "continue")
        # s1's dict should now have target-session data
        self.assertEqual(s1.session_id, "target-session")


if __name__ == "__main__":
    unittest.main()
