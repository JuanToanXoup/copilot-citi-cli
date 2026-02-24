# Tools Catalog

Reference of all available tools for use in `*.agent.md` files.
Add tool names to the `tools:` frontmatter field to grant an agent access.

---

## IDE Code Intelligence

| Tool | Description |
|------|-------------|
| `ide` | Compound PSI tool — grants access to all `ide_*` sub-actions below |

### Navigation (via `ide`)
- `ide_find_usages` — Find all references/usages of a symbol
- `ide_find_definition` — Navigate to symbol definition
- `ide_search_text` — Fast word-index search (exact matches)
- `ide_find_class` — Search classes/interfaces by name
- `ide_find_file` — Search files by name
- `ide_find_symbol` — Search symbols (classes, methods, fields) by name
- `ide_type_hierarchy` — Get inheritance hierarchy for a class
- `ide_call_hierarchy` — Build call hierarchy for a method
- `ide_find_implementations` — Find implementations of interface/abstract class
- `ide_find_super_methods` — Find parent methods that a method overrides
- `ide_file_structure` — Get hierarchical structure of a source file

### Intelligence (via `ide`)
- `ide_diagnostics` — Get code problems (errors, warnings) and quick fixes
- `ide_quick_doc` — Get rendered documentation for a symbol
- `ide_type_info` — Get the type of an expression/variable
- `ide_parameter_info` — Get parameter signatures for a method call
- `ide_structural_search` — Search for code patterns using structural search

### Refactoring (via `ide`)
- `ide_rename_symbol` — Rename a symbol and update all references
- `ide_safe_delete` — Delete a symbol safely, checking for usages first

### Project (via `ide`)
- `ide_get_index_status` — Check if IDE indexing is ready

---

## File Operations

| Tool | Description |
|------|-------------|
| `read_file` | Read file contents, optionally with line range |
| `list_dir` | List directory contents |
| `create_file` | Create a new file with content |
| `create_directory` | Create a directory (and parents) |
| `insert_edit_into_file` | Insert or replace text in a file |
| `replace_string_in_file` | Replace exact string match in a file |
| `multi_replace_string` | Apply multiple replacements across files |
| `apply_patch` | Apply a unified diff patch |

---

## Search

| Tool | Description |
|------|-------------|
| `grep_search` | Search for text pattern/regex in files |
| `file_search` | Search for files by name or glob |
| `list_code_usages` | Find usages of a symbol name in files |
| `search_workspace_symbols` | Search for symbol definitions by name |
| `find_test_files` | Find test files for given source files |
| `get_changed_files` | Get changed/staged/untracked files from git |

---

## Execution

| Tool | Description |
|------|-------------|
| `run_in_terminal` | Run a shell command in the terminal |
| `run_tests` | Run tests using the project's test framework |

---

## Information

| Tool | Description |
|------|-------------|
| `get_errors` | Check files for syntax/compile errors |
| `get_doc_info` | Extract documentation from source files |
| `get_project_setup_info` | Return project setup info (frameworks, config, commands) |
| `get_library_docs` | Fetch up-to-date library documentation |
| `resolve_library_id` | Resolve library name to ID (required before get_library_docs) |

---

## Memory & Knowledge

| Tool | Description |
|------|-------------|
| `semantic_search` | Search project code index by semantic similarity |
| `remember` | Store a fact for persistent cross-session recall |
| `recall` | Retrieve stored knowledge by topic |

---

## Web

| Tool | Description |
|------|-------------|
| `fetch_web_page` | Fetch content from URLs |
| `github_repo` | Search code in a GitHub repository |

---

## Agent Collaboration

| Tool | Description |
|------|-------------|
| `delegate_task` | Delegate a task to a sub-agent |
| `create_team` | Create a team of persistent agents |
| `send_message` | Send a message to a teammate |
| `delete_team` | Disband the active team |

---

## Notes

- **Compound `ide` tool**: Adding `ide` to an agent's tools grants all `ide_*` sub-actions.
  Individual `ide_*` actions cannot be granted independently.
- **PSI supersession**: When `ide` is available, it supersedes `grep_search`,
  `file_search`, `list_code_usages`, `search_workspace_symbols`, `get_errors`,
  and `get_doc_info` with faster PSI-powered equivalents.
- **MCP tools**: MCP server tools are configured via the `mcp-servers:` frontmatter
  field, not the `tools:` field. See the agent.md spec for details.
