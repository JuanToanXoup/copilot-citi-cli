"""Unit tests for worker Q&A schema validation."""

import json
import os
import sys
import unittest

# Ensure the cli source is on the path
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(PROJECT_ROOT, "cli", "src"))

from copilot_cli.schema_validation import (
    schema_to_json_schema,
    schema_to_description,
    soft_validate,
    build_answer_from_validation,
)


class TestSchemaToJsonSchema(unittest.TestCase):
    """Test conversion from compact schema format to JSON Schema."""

    def test_basic_conversion(self):
        schema = {
            "file_path": {"type": "string", "required": True, "description": "Path to review"},
            "diff": {"type": "string", "description": "The diff"},
        }
        result = schema_to_json_schema(schema)

        self.assertEqual(result["type"], "object")
        self.assertIn("file_path", result["properties"])
        self.assertIn("diff", result["properties"])
        self.assertEqual(result["properties"]["file_path"]["type"], "string")
        self.assertEqual(result["properties"]["file_path"]["description"], "Path to review")
        self.assertEqual(result["required"], ["file_path"])

    def test_no_required_fields(self):
        schema = {
            "goal": {"type": "string", "description": "What to focus on"},
        }
        result = schema_to_json_schema(schema)
        self.assertNotIn("required", result)

    def test_multiple_required_fields(self):
        schema = {
            "a": {"type": "string", "required": True},
            "b": {"type": "integer", "required": True},
            "c": {"type": "boolean"},
        }
        result = schema_to_json_schema(schema)
        self.assertEqual(sorted(result["required"]), ["a", "b"])

    def test_empty_schema(self):
        result = schema_to_json_schema({})
        self.assertEqual(result["type"], "object")
        self.assertEqual(result["properties"], {})

    def test_preserves_items(self):
        schema = {
            "issues": {"type": "array", "items": {"type": "string"}, "description": "List of issues"},
        }
        result = schema_to_json_schema(schema)
        self.assertEqual(result["properties"]["issues"]["items"], {"type": "string"})

    def test_preserves_default(self):
        schema = {
            "approved": {"type": "boolean", "default": False},
        }
        result = schema_to_json_schema(schema)
        self.assertEqual(result["properties"]["approved"]["default"], False)

    def test_ignores_non_dict_values(self):
        schema = {
            "valid_field": {"type": "string"},
            "metadata": "not a dict",
        }
        result = schema_to_json_schema(schema)
        self.assertIn("valid_field", result["properties"])
        self.assertNotIn("metadata", result["properties"])


class TestSchemaToDescription(unittest.TestCase):
    """Test rendering schemas as human-readable descriptions."""

    def test_basic_description(self):
        schema = {
            "file_path": {"type": "string", "required": True, "description": "Path to review"},
            "diff": {"type": "string", "description": "The diff"},
        }
        result = schema_to_description(schema)
        self.assertIn("Parameters:", result)
        self.assertIn("file_path (string, required): Path to review", result)
        self.assertIn("diff (string): The diff", result)

    def test_custom_label(self):
        schema = {"approved": {"type": "boolean"}}
        result = schema_to_description(schema, label="Returns")
        self.assertIn("Returns:", result)

    def test_no_description(self):
        schema = {"count": {"type": "integer"}}
        result = schema_to_description(schema)
        self.assertIn("count (integer)", result)
        self.assertNotIn(":", result.split("\n")[1].split(")")[-1].strip() or "x")

    def test_empty_schema(self):
        result = schema_to_description({})
        self.assertEqual(result, "")

    def test_no_type(self):
        schema = {"data": {"description": "Some data"}}
        result = schema_to_description(schema)
        self.assertIn("data (any): Some data", result)


class TestSoftValidate(unittest.TestCase):
    """Test soft-validation of data against schemas."""

    def setUp(self):
        self.review_schema = {
            "approved": {"type": "boolean", "required": True, "description": "Whether approved"},
            "issues": {"type": "array", "description": "Issues found"},
            "summary": {"type": "string", "required": True, "description": "Summary"},
        }

    def test_perfect_match(self):
        data = {"approved": True, "issues": [], "summary": "Looks good"}
        result = soft_validate(data, self.review_schema)

        self.assertEqual(result["parsed"]["approved"], True)
        self.assertEqual(result["parsed"]["issues"], [])
        self.assertEqual(result["parsed"]["summary"], "Looks good")
        self.assertEqual(result["missing"], [])
        self.assertEqual(result["extras"], {})
        self.assertEqual(result["warnings"], [])

    def test_extra_fields_preserved(self):
        data = {"approved": True, "summary": "OK", "confidence": 0.95, "reviewer": "bot"}
        result = soft_validate(data, self.review_schema)

        self.assertEqual(result["parsed"]["approved"], True)
        self.assertEqual(result["extras"]["confidence"], 0.95)
        self.assertEqual(result["extras"]["reviewer"], "bot")

    def test_missing_required_fields(self):
        data = {"issues": ["bug in line 5"]}
        result = soft_validate(data, self.review_schema)

        self.assertIn("approved", result["missing"])
        self.assertIn("summary", result["missing"])
        self.assertTrue(len(result["warnings"]) >= 2)

    def test_type_coercion_string(self):
        data = {"approved": True, "summary": 42}  # int to string
        schema = {
            "approved": {"type": "boolean"},
            "summary": {"type": "string"},
        }
        result = soft_validate(data, schema)
        self.assertEqual(result["parsed"]["summary"], "42")

    def test_type_coercion_boolean(self):
        data = {"approved": "true"}
        schema = {"approved": {"type": "boolean"}}
        result = soft_validate(data, schema)
        self.assertEqual(result["parsed"]["approved"], True)

    def test_type_coercion_boolean_false(self):
        data = {"approved": "false"}
        schema = {"approved": {"type": "boolean"}}
        result = soft_validate(data, schema)
        self.assertEqual(result["parsed"]["approved"], False)

    def test_type_coercion_number(self):
        data = {"score": "8.5"}
        schema = {"score": {"type": "number"}}
        result = soft_validate(data, schema)
        self.assertEqual(result["parsed"]["score"], 8.5)

    def test_type_coercion_integer(self):
        data = {"count": 3.0}
        schema = {"count": {"type": "integer"}}
        result = soft_validate(data, schema)
        self.assertEqual(result["parsed"]["count"], 3)

    def test_json_string_input(self):
        data_str = json.dumps({"approved": True, "summary": "OK"})
        result = soft_validate(data_str, self.review_schema)

        self.assertEqual(result["parsed"]["approved"], True)
        self.assertEqual(result["parsed"]["summary"], "OK")
        # approved and summary were found; only issues is optional and absent
        self.assertNotIn("approved", result["missing"])
        self.assertNotIn("summary", result["missing"])
        self.assertEqual(result["warnings"], [])

    def test_invalid_json_string(self):
        result = soft_validate("this is not json", self.review_schema)

        self.assertEqual(result["parsed"], {})
        self.assertIn("approved", result["missing"])
        self.assertIn("summary", result["missing"])
        self.assertTrue(any("not valid JSON" in w for w in result["warnings"]))
        self.assertEqual(result["raw"], "this is not json")

    def test_json_string_non_object(self):
        result = soft_validate("[1, 2, 3]", self.review_schema)

        self.assertEqual(result["parsed"], {})
        self.assertTrue(any("not a JSON object" in w for w in result["warnings"]))

    def test_optional_field_missing(self):
        data = {"approved": True, "summary": "Fine"}
        result = soft_validate(data, self.review_schema)

        self.assertNotIn("issues", result["missing"])
        self.assertEqual(result["warnings"], [])

    def test_never_raises(self):
        # Even with bizarre inputs, soft_validate should not raise
        for data in [None, 42, [], True, "", {}, "}", "{bad json}"]:
            try:
                if isinstance(data, (dict, str)):
                    soft_validate(data, self.review_schema)
            except Exception as e:
                self.fail(f"soft_validate raised {e} for input {data!r}")

    def test_raw_preserved(self):
        data = {"approved": True, "summary": "OK"}
        result = soft_validate(data, self.review_schema)
        self.assertEqual(result["raw"], data)


class TestBuildAnswerFromValidation(unittest.TestCase):
    """Test building unified answer dicts."""

    def test_merges_parsed_and_extras(self):
        validation = {
            "parsed": {"approved": True, "summary": "OK"},
            "extras": {"confidence": 0.9},
            "missing": [],
            "warnings": [],
            "raw": {"approved": True, "summary": "OK", "confidence": 0.9},
        }
        answer = build_answer_from_validation(validation)

        self.assertEqual(answer["approved"], True)
        self.assertEqual(answer["summary"], "OK")
        self.assertEqual(answer["confidence"], 0.9)
        self.assertEqual(answer["_validation"]["missing"], [])
        self.assertEqual(answer["_validation"]["warnings"], [])

    def test_includes_validation_metadata(self):
        validation = {
            "parsed": {},
            "extras": {},
            "missing": ["approved"],
            "warnings": ["Required field 'approved' is missing"],
            "raw": {},
        }
        answer = build_answer_from_validation(validation)
        self.assertEqual(answer["_validation"]["missing"], ["approved"])
        self.assertIn("Required field", answer["_validation"]["warnings"][0])


class TestWorkerConfigSchemas(unittest.TestCase):
    """Test that WorkerConfig correctly stores schema fields."""

    def test_default_schemas_none(self):
        from copilot_cli.orchestrator import WorkerConfig
        config = WorkerConfig(role="test")
        self.assertIsNone(config.question_schema)
        self.assertIsNone(config.answer_schema)

    def test_schemas_set(self):
        from copilot_cli.orchestrator import WorkerConfig
        q_schema = {"file_path": {"type": "string", "required": True}}
        a_schema = {"approved": {"type": "boolean"}}
        config = WorkerConfig(
            role="reviewer",
            question_schema=q_schema,
            answer_schema=a_schema,
        )
        self.assertEqual(config.question_schema, q_schema)
        self.assertEqual(config.answer_schema, a_schema)


class TestParseWorkerDefs(unittest.TestCase):
    """Test that _parse_worker_defs handles schema fields."""

    def test_parse_with_schemas(self):
        from copilot_cli.client import _parse_worker_defs
        defs = [{
            "role": "reviewer",
            "system_prompt": "Review code",
            "question_schema": {
                "file_path": {"type": "string", "required": True},
            },
            "answer_schema": {
                "approved": {"type": "boolean"},
                "issues": {"type": "array"},
            },
        }]
        workers = _parse_worker_defs(defs)
        self.assertEqual(len(workers), 1)
        self.assertEqual(workers[0].role, "reviewer")
        self.assertIsNotNone(workers[0].question_schema)
        self.assertIn("file_path", workers[0].question_schema)
        self.assertIsNotNone(workers[0].answer_schema)
        self.assertIn("approved", workers[0].answer_schema)

    def test_parse_without_schemas(self):
        from copilot_cli.client import _parse_worker_defs
        defs = [{"role": "coder", "system_prompt": "Write code"}]
        workers = _parse_worker_defs(defs)
        self.assertIsNone(workers[0].question_schema)
        self.assertIsNone(workers[0].answer_schema)


class TestAgentCardSchemas(unittest.TestCase):
    """Test that AgentCard includes schema fields."""

    def test_to_dict_with_schemas(self):
        from copilot_cli.mcp_agent import AgentCard
        card = AgentCard(
            name="reviewer",
            role="reviewer",
            question_schema={"file_path": {"type": "string"}},
            answer_schema={"approved": {"type": "boolean"}},
        )
        d = card.to_dict()
        self.assertIn("question_schema", d)
        self.assertIn("answer_schema", d)

    def test_to_dict_without_schemas(self):
        from copilot_cli.mcp_agent import AgentCard
        card = AgentCard(name="coder", role="coder")
        d = card.to_dict()
        self.assertNotIn("question_schema", d)
        self.assertNotIn("answer_schema", d)


class TestBuildAgentTools(unittest.TestCase):
    """Test dynamic MCP tool generation from schemas."""

    def test_tools_without_schemas(self):
        from copilot_cli.mcp_agent import AgentCard, _build_agent_tools
        card = AgentCard(name="coder", role="coder")
        tools = _build_agent_tools(card)

        self.assertEqual(len(tools), 3)
        exec_tool = tools[0]
        self.assertEqual(exec_tool["name"], "execute_task")
        # Should have only prompt and context
        props = exec_tool["inputSchema"]["properties"]
        self.assertIn("prompt", props)
        self.assertIn("context", props)
        self.assertEqual(len(props), 2)

    def test_tools_with_question_schema(self):
        from copilot_cli.mcp_agent import AgentCard, _build_agent_tools
        card = AgentCard(
            name="reviewer",
            role="reviewer",
            question_schema={
                "file_path": {"type": "string", "required": True, "description": "Path"},
                "diff": {"type": "string", "description": "The diff"},
            },
        )
        tools = _build_agent_tools(card)
        exec_tool = tools[0]
        props = exec_tool["inputSchema"]["properties"]

        # Should have prompt, context, plus schema fields
        self.assertIn("prompt", props)
        self.assertIn("file_path", props)
        self.assertIn("diff", props)
        self.assertEqual(props["file_path"]["type"], "string")
        self.assertEqual(props["file_path"]["description"], "Path")
        # file_path should be required
        self.assertIn("file_path", exec_tool["inputSchema"]["required"])

    def test_tools_with_answer_schema(self):
        from copilot_cli.mcp_agent import AgentCard, _build_agent_tools
        card = AgentCard(
            name="reviewer",
            role="reviewer",
            answer_schema={
                "approved": {"type": "boolean", "description": "Whether approved"},
                "issues": {"type": "array", "description": "Issues found"},
            },
        )
        tools = _build_agent_tools(card)
        exec_tool = tools[0]

        # Description should include answer schema info
        self.assertIn("Expected response fields", exec_tool["description"])
        self.assertIn("approved", exec_tool["description"])
        self.assertIn("issues", exec_tool["description"])


class TestExtractJsonFromReply(unittest.TestCase):
    """Test JSON extraction from LLM replies."""

    def test_bare_json(self):
        from copilot_cli.mcp_agent import MCPAgentServer
        result = MCPAgentServer._extract_json_from_reply(
            '{"approved": true, "summary": "looks good"}'
        )
        self.assertEqual(result, {"approved": True, "summary": "looks good"})

    def test_json_fenced(self):
        from copilot_cli.mcp_agent import MCPAgentServer
        result = MCPAgentServer._extract_json_from_reply(
            'Here is my review:\n```json\n{"approved": true}\n```\nDone.'
        )
        self.assertEqual(result, {"approved": True})

    def test_generic_fenced(self):
        from copilot_cli.mcp_agent import MCPAgentServer
        result = MCPAgentServer._extract_json_from_reply(
            'Result:\n```\n{"count": 5}\n```'
        )
        self.assertEqual(result, {"count": 5})

    def test_json_in_prose(self):
        from copilot_cli.mcp_agent import MCPAgentServer
        result = MCPAgentServer._extract_json_from_reply(
            'After reviewing the code, my assessment is {"approved": false, "reason": "bugs"} which...'
        )
        self.assertEqual(result, {"approved": False, "reason": "bugs"})

    def test_no_json(self):
        from copilot_cli.mcp_agent import MCPAgentServer
        result = MCPAgentServer._extract_json_from_reply(
            'The code looks fine, no issues found.'
        )
        self.assertIsNone(result)

    def test_empty_string(self):
        from copilot_cli.mcp_agent import MCPAgentServer
        result = MCPAgentServer._extract_json_from_reply("")
        self.assertIsNone(result)


class TestTomlRoundTrip(unittest.TestCase):
    """Test that schemas survive TOML serialization round-trip."""

    def test_toml_round_trip(self):
        try:
            import tomllib
        except ImportError:
            try:
                import tomli as tomllib
            except ImportError:
                self.skipTest("tomllib/tomli not available")

        try:
            import tomli_w
        except ImportError:
            self.skipTest("tomli_w not available")

        import io

        data = {
            "name": "test-agent",
            "workers": [
                {
                    "role": "reviewer",
                    "system_prompt": "Review code",
                    "question_schema": {
                        "file_path": {"type": "string", "required": True, "description": "Path"},
                    },
                    "answer_schema": {
                        "approved": {"type": "boolean", "description": "Whether approved"},
                        "issues": {"type": "array", "description": "Issues found"},
                    },
                },
            ],
        }

        # Serialize to TOML
        buf = io.BytesIO()
        tomli_w.dump(data, buf)
        toml_bytes = buf.getvalue()

        # Parse back
        parsed = tomllib.loads(toml_bytes.decode())

        self.assertEqual(len(parsed["workers"]), 1)
        worker = parsed["workers"][0]
        self.assertEqual(worker["role"], "reviewer")
        self.assertIn("question_schema", worker)
        self.assertIn("answer_schema", worker)
        self.assertEqual(
            worker["question_schema"]["file_path"]["type"], "string"
        )
        self.assertEqual(
            worker["answer_schema"]["approved"]["type"], "boolean"
        )


class TestCleanForToml(unittest.TestCase):
    """Test that _clean_for_toml handles schemas correctly."""

    def test_preserves_non_empty_schemas(self):
        sys.path.insert(0, os.path.join(PROJECT_ROOT, "agent-builder", "src"))
        from agent_builder.server import _clean_for_toml

        data = {
            "name": "test",
            "workers": [
                {
                    "role": "reviewer",
                    "question_schema": {"file_path": {"type": "string"}},
                    "answer_schema": {"approved": {"type": "boolean"}},
                },
            ],
        }
        result = _clean_for_toml(data)
        self.assertIn("workers", result)
        self.assertIn("question_schema", result["workers"][0])
        self.assertIn("answer_schema", result["workers"][0])

    def test_strips_empty_schemas(self):
        sys.path.insert(0, os.path.join(PROJECT_ROOT, "agent-builder", "src"))
        from agent_builder.server import _clean_for_toml

        data = {
            "name": "test",
            "workers": [
                {
                    "role": "coder",
                    "question_schema": {},
                    "answer_schema": {},
                },
            ],
        }
        result = _clean_for_toml(data)
        self.assertNotIn("question_schema", result["workers"][0])
        self.assertNotIn("answer_schema", result["workers"][0])


if __name__ == "__main__":
    unittest.main()
