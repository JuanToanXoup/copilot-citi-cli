"""Unit tests for all tools in the tools/ package."""

import os
import sys
import tempfile
import shutil
import unittest

# Ensure the cli source is on the path
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(PROJECT_ROOT, "cli", "src"))

from copilot_cli.tools import TOOL_SCHEMAS, TOOL_EXECUTORS, BUILTIN_TOOL_NAMES, ToolContext


def _make_ctx(workspace_root: str) -> ToolContext:
    """Create a ToolContext with no-op callbacks."""
    return ToolContext(
        workspace_root=workspace_root,
        sync_file_to_server=lambda path, content: None,
        open_document=lambda uri, lang, text: None,
    )


class TestToolDiscovery(unittest.TestCase):
    """Test the auto-discovery infrastructure."""

    def test_all_tools_discovered(self):
        expected = {
            # 15 built-in
            "insert_edit_into_file", "replace_string_in_file", "multi_replace_string",
            "create_file", "create_directory", "apply_patch", "read_file", "list_dir",
            "file_search", "grep_search", "find_test_files", "run_in_terminal",
            "run_tests", "fetch_web_page", "get_errors",
            # 7 new
            "search_workspace_symbols", "list_code_usages", "get_changed_files",
            "github_repo", "memory", "get_project_setup_info", "get_doc_info",
        }
        self.assertEqual(set(TOOL_SCHEMAS.keys()), expected)
        self.assertEqual(set(TOOL_EXECUTORS.keys()), expected)

    def test_builtin_names_match(self):
        self.assertEqual(len(BUILTIN_TOOL_NAMES), 15)
        for name in BUILTIN_TOOL_NAMES:
            self.assertIn(name, TOOL_SCHEMAS, f"Built-in tool '{name}' not in TOOL_SCHEMAS")

    def test_schemas_have_required_fields(self):
        for name, schema in TOOL_SCHEMAS.items():
            self.assertIn("name", schema, f"{name} schema missing 'name'")
            self.assertIn("description", schema, f"{name} schema missing 'description'")
            self.assertIn("inputSchema", schema, f"{name} schema missing 'inputSchema'")
            input_schema = schema["inputSchema"]
            self.assertIn("type", input_schema, f"{name} inputSchema missing 'type'")
            self.assertIn("properties", input_schema, f"{name} inputSchema missing 'properties'")
            self.assertIn("required", input_schema,
                          f"{name} inputSchema missing 'required' (must be present even if empty)")


class TestBuiltinTools(unittest.TestCase):
    """Test the 15 built-in tool executors."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix="test_tools_")
        self.ctx = _make_ctx(self.tmpdir)
        # Create a sample file
        self.sample_file = os.path.join(self.tmpdir, "sample.py")
        with open(self.sample_file, "w") as f:
            f.write('def hello():\n    """Say hello."""\n    print("hello")\n')

    def tearDown(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def test_create_file(self):
        fp = os.path.join(self.tmpdir, "new.txt")
        result = TOOL_EXECUTORS["create_file"]({"filePath": fp, "content": "test"}, self.ctx)
        self.assertEqual(result["result"], "success")
        self.assertTrue(os.path.exists(fp))
        self.assertEqual(open(fp).read(), "test")

    def test_create_directory(self):
        dp = os.path.join(self.tmpdir, "subdir")
        result = TOOL_EXECUTORS["create_directory"]({"dirPath": dp}, self.ctx)
        self.assertEqual(result["result"], "success")
        self.assertTrue(os.path.isdir(dp))

    def test_read_file(self):
        result = TOOL_EXECUTORS["read_file"]({"filePath": self.sample_file}, self.ctx)
        self.assertIsInstance(result, list)
        self.assertIn("def hello", result[0]["value"])

    def test_read_file_with_range(self):
        result = TOOL_EXECUTORS["read_file"](
            {"filePath": self.sample_file, "startLineNumberBaseOne": 1, "endLineNumberBaseOne": 1},
            self.ctx,
        )
        self.assertIn("def hello", result[0]["value"])
        self.assertNotIn("print", result[0]["value"])

    def test_list_dir(self):
        result = TOOL_EXECUTORS["list_dir"]({"path": self.tmpdir}, self.ctx)
        self.assertIsInstance(result, list)
        self.assertIn("sample.py", result[0]["value"])

    def test_insert_edit_into_file(self):
        fp = os.path.join(self.tmpdir, "edit_target.py")
        result = TOOL_EXECUTORS["insert_edit_into_file"](
            {"filePath": fp, "code": "x = 1\n", "explanation": "test"},
            self.ctx,
        )
        self.assertEqual(result["result"], "success")
        self.assertEqual(open(fp).read(), "x = 1\n")

    def test_replace_string_in_file(self):
        fp = os.path.join(self.tmpdir, "replace_target.txt")
        with open(fp, "w") as f:
            f.write("hello world")
        result = TOOL_EXECUTORS["replace_string_in_file"](
            {"filePath": fp, "oldString": "hello", "newString": "goodbye"},
            self.ctx,
        )
        self.assertEqual(result["result"], "success")
        self.assertEqual(open(fp).read(), "goodbye world")

    def test_file_search(self):
        result = TOOL_EXECUTORS["file_search"]({"query": "sample"}, self.ctx)
        self.assertIsInstance(result, list)
        self.assertIn("sample.py", result[0]["value"])

    def test_file_search_no_match(self):
        result = TOOL_EXECUTORS["file_search"]({"query": "nonexistent_xyz"}, self.ctx)
        self.assertIn("No files found", result[0]["value"])

    def test_grep_search(self):
        result = TOOL_EXECUTORS["grep_search"]({"query": "def hello"}, self.ctx)
        self.assertIsInstance(result, list)
        self.assertIn("def hello", result[0]["value"])

    def test_grep_search_no_match(self):
        result = TOOL_EXECUTORS["grep_search"]({"query": "nonexistent_pattern_xyz"}, self.ctx)
        self.assertIn("No matches found", result[0]["value"])

    def test_find_test_files(self):
        # Create source file and its test file
        src = os.path.join(self.tmpdir, "example.py")
        with open(src, "w") as f:
            f.write("def foo(): pass\n")
        tf = os.path.join(self.tmpdir, "test_example.py")
        with open(tf, "w") as f:
            f.write("def test_foo(): pass\n")
        result = TOOL_EXECUTORS["find_test_files"]({"filePaths": [src]}, self.ctx)
        self.assertIsInstance(result, list)
        self.assertIn("test_example.py", result[0]["value"])

    def test_get_errors_clean_file(self):
        result = TOOL_EXECUTORS["get_errors"]({"filePaths": [self.sample_file]}, self.ctx)
        self.assertIsInstance(result, list)
        self.assertIn("No errors", result[0]["value"])

    def test_get_errors_bad_file(self):
        bad = os.path.join(self.tmpdir, "bad.py")
        with open(bad, "w") as f:
            f.write("def broken(\n")  # syntax error
        result = TOOL_EXECUTORS["get_errors"]({"filePaths": [bad]}, self.ctx)
        self.assertIsInstance(result, list)
        # Should report an error
        self.assertNotIn("No errors", result[0]["value"])

    def test_apply_patch(self):
        target = os.path.join(self.tmpdir, "patch_file.txt")
        with open(target, "w") as f:
            f.write("line1\nline2\nline3\n")
        patch = (
            f"--- {target}\n"
            f"+++ {target}\n"
            "@@ -1,3 +1,3 @@\n"
            " line1\n"
            "-line2\n"
            "+LINE_TWO\n"
            " line3\n"
        )
        result = TOOL_EXECUTORS["apply_patch"]({"input": patch, "explanation": "test"}, self.ctx)
        self.assertEqual(result["result"], "success")
        self.assertIn("LINE_TWO", open(target).read())


class TestNewTools(unittest.TestCase):
    """Test the 7 new tool executors."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix="test_new_tools_")
        self.ctx = _make_ctx(self.tmpdir)
        # Create sample Python file
        self.py_file = os.path.join(self.tmpdir, "example.py")
        with open(self.py_file, "w") as f:
            f.write(
                '"""Module docstring."""\n\n'
                'def greet(name):\n'
                '    """Greet someone."""\n'
                '    return f"Hello, {name}"\n\n'
                'class Calculator:\n'
                '    """A calculator."""\n'
                '    def add(self, a, b):\n'
                '        return a + b\n'
            )

    def tearDown(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def test_search_workspace_symbols(self):
        result = TOOL_EXECUTORS["search_workspace_symbols"](
            {"symbolName": "Calculator"}, self.ctx,
        )
        self.assertIsInstance(result, list)
        self.assertIn("Calculator", result[0]["value"])

    def test_search_workspace_symbols_no_match(self):
        result = TOOL_EXECUTORS["search_workspace_symbols"](
            {"symbolName": "NonexistentXYZ123"}, self.ctx,
        )
        self.assertIn("No symbol definitions found", result[0]["value"])

    def test_list_code_usages(self):
        result = TOOL_EXECUTORS["list_code_usages"](
            {"symbolName": "greet"}, self.ctx,
        )
        self.assertIsInstance(result, list)
        self.assertIn("greet", result[0]["value"])

    def test_list_code_usages_specific_files(self):
        result = TOOL_EXECUTORS["list_code_usages"](
            {"symbolName": "greet", "filePaths": [self.py_file]}, self.ctx,
        )
        self.assertIn("greet", result[0]["value"])

    def test_list_code_usages_no_match(self):
        result = TOOL_EXECUTORS["list_code_usages"](
            {"symbolName": "zzz_nonexistent_zzz"}, self.ctx,
        )
        self.assertIn("No usages found", result[0]["value"])

    def test_get_changed_files(self):
        # Works even on non-git dirs — should gracefully return "No changed files"
        result = TOOL_EXECUTORS["get_changed_files"](
            {"repositoryPath": self.tmpdir}, self.ctx,
        )
        self.assertIsInstance(result, list)
        self.assertEqual(result[0]["type"], "text")

    def test_memory_save_read_list_delete(self):
        mem_dir = os.path.expanduser("~/.copilot-cli/memories")

        # Save
        result = TOOL_EXECUTORS["memory"](
            {"command": "save", "path": "unit_test_note.md", "content": "test content"}, self.ctx,
        )
        self.assertIn("Saved", result[0]["value"])
        self.assertTrue(os.path.exists(os.path.join(mem_dir, "unit_test_note.md")))

        # Read
        result = TOOL_EXECUTORS["memory"](
            {"command": "read", "path": "unit_test_note.md"}, self.ctx,
        )
        self.assertEqual(result[0]["value"], "test content")

        # List
        result = TOOL_EXECUTORS["memory"](
            {"command": "list"}, self.ctx,
        )
        self.assertIn("unit_test_note.md", result[0]["value"])

        # Delete
        result = TOOL_EXECUTORS["memory"](
            {"command": "delete", "path": "unit_test_note.md"}, self.ctx,
        )
        self.assertIn("Deleted", result[0]["value"])
        self.assertFalse(os.path.exists(os.path.join(mem_dir, "unit_test_note.md")))

    def test_memory_read_nonexistent(self):
        result = TOOL_EXECUTORS["memory"](
            {"command": "read", "path": "does_not_exist_xyz.md"}, self.ctx,
        )
        self.assertIn("not found", result[0]["value"])

    def test_memory_missing_path(self):
        result = TOOL_EXECUTORS["memory"](
            {"command": "save"}, self.ctx,
        )
        self.assertIn("required", result[0]["value"].lower())

    def test_get_project_setup_info(self):
        # Create a pyproject.toml to detect
        with open(os.path.join(self.tmpdir, "pyproject.toml"), "w") as f:
            f.write("[project]\nname = 'test'\n")
        result = TOOL_EXECUTORS["get_project_setup_info"](
            {"projectType": "auto"}, self.ctx,
        )
        self.assertIsInstance(result, list)
        self.assertIn("pyproject.toml", result[0]["value"])

    def test_get_project_setup_info_no_config(self):
        empty = tempfile.mkdtemp(prefix="test_empty_")
        try:
            ctx = _make_ctx(empty)
            result = TOOL_EXECUTORS["get_project_setup_info"](
                {"projectType": "auto"}, ctx,
            )
            self.assertIn("No standard project config", result[0]["value"])
        finally:
            shutil.rmtree(empty, ignore_errors=True)

    def test_get_doc_info_python(self):
        result = TOOL_EXECUTORS["get_doc_info"](
            {"filePaths": [self.py_file]}, self.ctx,
        )
        self.assertIsInstance(result, list)
        self.assertIn("Module docstring", result[0]["value"])
        self.assertIn("Greet someone", result[0]["value"])

    def test_get_doc_info_file_not_found(self):
        result = TOOL_EXECUTORS["get_doc_info"](
            {"filePaths": ["/nonexistent/file.py"]}, self.ctx,
        )
        self.assertIn("File not found", result[0]["value"])

    def test_get_doc_info_no_files(self):
        result = TOOL_EXECUTORS["get_doc_info"](
            {"filePaths": []}, self.ctx,
        )
        self.assertIn("No files provided", result[0]["value"])


class TestWrapRegisteredToolResult(unittest.TestCase):
    """Test _wrap_registered_tool_result response format."""

    def setUp(self):
        # Import the method from copilot_cli.client
        from copilot_cli.client import CopilotClient
        self.wrap = CopilotClient._wrap_registered_tool_result

    def test_list_result(self):
        result = self.wrap([{"type": "text", "value": "hello"}])
        self.assertIsInstance(result, list)
        self.assertEqual(len(result), 2)
        self.assertIsNone(result[1])
        self.assertEqual(result[0]["status"], "success")
        self.assertEqual(result[0]["content"], [{"value": "hello"}])

    def test_list_result_multiple_items(self):
        result = self.wrap([
            {"type": "text", "value": "one"},
            {"type": "text", "value": "two"},
        ])
        self.assertEqual(len(result[0]["content"]), 2)
        self.assertEqual(result[0]["content"][0]["value"], "one")
        self.assertEqual(result[0]["content"][1]["value"], "two")

    def test_dict_result_success(self):
        result = self.wrap({"result": "success", "output": "done"})
        self.assertEqual(result[0]["status"], "success")
        self.assertEqual(result[0]["content"][0]["value"], "done")
        self.assertIsNone(result[1])

    def test_dict_result_error(self):
        result = self.wrap({"result": "error", "message": "failed"})
        self.assertEqual(result[0]["status"], "error")
        self.assertEqual(result[0]["content"][0]["value"], "failed")

    def test_string_result(self):
        result = self.wrap("plain string")
        self.assertEqual(result[0]["content"][0]["value"], "plain string")
        self.assertEqual(result[0]["status"], "success")


if __name__ == "__main__":
    unittest.main()
