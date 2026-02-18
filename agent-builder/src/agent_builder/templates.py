"""Preset agent templates for the Agent Builder."""

TEMPLATES = {
    "code_reviewer": {
        "name": "Code Reviewer",
        "description": "Reviews code for bugs, style issues, and best practices. Read-only — never modifies files.",
        "system_prompt": (
            "You are a senior code reviewer. Analyze code for bugs, security issues, "
            "performance problems, and style violations. Provide actionable feedback "
            "with specific line references. Never modify files directly — only suggest changes."
        ),
        "model": "gpt-4.1",
        "agent_mode": True,
        "tools": {
            "enabled": [
                "read_file", "list_dir", "file_search", "grep_search",
                "get_errors", "get_doc_info", "search_workspace_symbols",
                "list_code_usages", "get_changed_files", "get_project_setup_info",
                "find_test_files",
            ],
            "disabled": [],
        },
        "mcp_servers": {"playwright": {"command": "npx", "args": ["-y", "@playwright/mcp@latest"], "env": {}}, "mermaid": {"command": "npx", "args": ["-y", "@peng-shawn/mermaid-mcp-server"], "env": {}}, "qdrant": {"command": "npx", "args": ["-y", "@qdrant/mcp-server-qdrant"], "env": {}}, "context7": {"command": "npx", "args": ["-y", "@upstash/context7-mcp"], "env": {}}, "n8n": {"command": "npx", "args": ["-y", "n8n-mcp"], "env": {}}},
        "lsp_servers": {"python": {"command": "pyright-langserver", "args": ["--stdio"]}, "java": {"command": "jdtls", "args": []}, "kotlin": {"command": "kotlin-language-server", "args": []}, "gherkin": {"command": "npx", "args": ["-y", "@cucumber/language-server", "--stdio"]}},
    },
    "bug_fixer": {
        "name": "Bug Fixer",
        "description": "Diagnoses and fixes bugs with read, edit, and test capabilities.",
        "system_prompt": (
            "You are a debugging expert. Diagnose bugs by reading code, checking errors, "
            "and tracing execution. Fix issues by editing files, then run tests to verify. "
            "Always explain the root cause before applying a fix."
        ),
        "model": "gpt-4.1",
        "agent_mode": True,
        "tools": {
            "enabled": [
                "read_file", "list_dir", "file_search", "grep_search",
                "get_errors", "search_workspace_symbols", "list_code_usages",
                "get_changed_files", "get_project_setup_info", "find_test_files",
                "insert_edit_into_file", "replace_string_in_file",
                "multi_replace_string", "apply_patch",
                "run_in_terminal", "run_tests",
            ],
            "disabled": [],
        },
        "mcp_servers": {"playwright": {"command": "npx", "args": ["-y", "@playwright/mcp@latest"], "env": {}}, "mermaid": {"command": "npx", "args": ["-y", "@peng-shawn/mermaid-mcp-server"], "env": {}}, "qdrant": {"command": "npx", "args": ["-y", "@qdrant/mcp-server-qdrant"], "env": {}}, "context7": {"command": "npx", "args": ["-y", "@upstash/context7-mcp"], "env": {}}, "n8n": {"command": "npx", "args": ["-y", "n8n-mcp"], "env": {}}},
        "lsp_servers": {"python": {"command": "pyright-langserver", "args": ["--stdio"]}, "java": {"command": "jdtls", "args": []}, "kotlin": {"command": "kotlin-language-server", "args": []}, "gherkin": {"command": "npx", "args": ["-y", "@cucumber/language-server", "--stdio"]}},
    },
    "documentation_writer": {
        "name": "Documentation Writer",
        "description": "Generates and updates documentation, READMEs, and docstrings.",
        "system_prompt": (
            "You are a technical writer. Generate clear, comprehensive documentation "
            "including READMEs, API docs, and inline docstrings. Read existing code to "
            "understand functionality before writing. Match the project's existing doc style."
        ),
        "model": "gpt-4.1",
        "agent_mode": True,
        "tools": {
            "enabled": [
                "read_file", "list_dir", "file_search", "grep_search",
                "get_doc_info", "search_workspace_symbols", "get_project_setup_info",
                "create_file", "insert_edit_into_file", "replace_string_in_file",
            ],
            "disabled": [],
        },
        "mcp_servers": {"playwright": {"command": "npx", "args": ["-y", "@playwright/mcp@latest"], "env": {}}, "mermaid": {"command": "npx", "args": ["-y", "@peng-shawn/mermaid-mcp-server"], "env": {}}, "qdrant": {"command": "npx", "args": ["-y", "@qdrant/mcp-server-qdrant"], "env": {}}, "context7": {"command": "npx", "args": ["-y", "@upstash/context7-mcp"], "env": {}}, "n8n": {"command": "npx", "args": ["-y", "n8n-mcp"], "env": {}}},
        "lsp_servers": {"python": {"command": "pyright-langserver", "args": ["--stdio"]}, "java": {"command": "jdtls", "args": []}, "kotlin": {"command": "kotlin-language-server", "args": []}, "gherkin": {"command": "npx", "args": ["-y", "@cucumber/language-server", "--stdio"]}},
    },
    "research_agent": {
        "name": "Research Agent",
        "description": "Researches codebases, repos, and the web to answer questions.",
        "system_prompt": (
            "You are a research assistant. Gather information from the codebase, "
            "git history, web pages, and documentation to answer questions thoroughly. "
            "Cite sources and provide links when available."
        ),
        "model": "gpt-4.1",
        "agent_mode": True,
        "tools": {
            "enabled": [
                "read_file", "list_dir", "file_search", "grep_search",
                "get_doc_info", "search_workspace_symbols", "list_code_usages",
                "get_changed_files", "get_project_setup_info",
                "fetch_web_page", "github_repo", "memory",
            ],
            "disabled": [],
        },
        "mcp_servers": {"playwright": {"command": "npx", "args": ["-y", "@playwright/mcp@latest"], "env": {}}, "mermaid": {"command": "npx", "args": ["-y", "@peng-shawn/mermaid-mcp-server"], "env": {}}, "qdrant": {"command": "npx", "args": ["-y", "@qdrant/mcp-server-qdrant"], "env": {}}, "context7": {"command": "npx", "args": ["-y", "@upstash/context7-mcp"], "env": {}}, "n8n": {"command": "npx", "args": ["-y", "n8n-mcp"], "env": {}}},
        "lsp_servers": {"python": {"command": "pyright-langserver", "args": ["--stdio"]}, "java": {"command": "jdtls", "args": []}, "kotlin": {"command": "kotlin-language-server", "args": []}, "gherkin": {"command": "npx", "args": ["-y", "@cucumber/language-server", "--stdio"]}},
    },
    "bdd_qa_tester": {
        "name": "BDD QA Tester",
        "description": "Writes and maintains Gherkin feature files, step definitions, and BDD test suites.",
        "system_prompt": (
            "You are a BDD/QA specialist expert in Gherkin and Cucumber. Your role is to:\n"
            "1. Read source code, APIs, and requirements to understand application behavior.\n"
            "2. Write clear, well-structured Gherkin .feature files using Given/When/Then syntax.\n"
            "3. Create and update step definitions that map to the feature scenarios.\n"
            "4. Organize features with Background, Scenario Outline, Examples tables, and tags.\n"
            "5. Run the test suite and diagnose failures, fixing step definitions or flagging bugs.\n"
            "6. Follow BDD best practices: declarative scenarios, single responsibility per scenario, "
            "reusable steps, and business-readable language.\n"
            "Always search existing feature files and step definitions before creating new ones "
            "to avoid duplication. Use tags (@smoke, @regression, @wip) to categorize scenarios."
        ),
        "model": "gpt-4.1",
        "agent_mode": True,
        "tools": {
            "enabled": [
                "read_file", "list_dir", "file_search", "grep_search",
                "get_errors", "get_doc_info", "search_workspace_symbols",
                "list_code_usages", "get_changed_files", "get_project_setup_info",
                "find_test_files",
                "create_file", "insert_edit_into_file", "replace_string_in_file",
                "multi_replace_string", "apply_patch",
                "run_in_terminal", "run_tests",
            ],
            "disabled": [],
        },
        "mcp_servers": {"playwright": {"command": "npx", "args": ["-y", "@playwright/mcp@latest"], "env": {}}, "mermaid": {"command": "npx", "args": ["-y", "@peng-shawn/mermaid-mcp-server"], "env": {}}, "qdrant": {"command": "npx", "args": ["-y", "@qdrant/mcp-server-qdrant"], "env": {}}, "context7": {"command": "npx", "args": ["-y", "@upstash/context7-mcp"], "env": {}}, "n8n": {"command": "npx", "args": ["-y", "n8n-mcp"], "env": {}}},
        "lsp_servers": {"python": {"command": "pyright-langserver", "args": ["--stdio"]}, "java": {"command": "jdtls", "args": []}, "kotlin": {"command": "kotlin-language-server", "args": []}, "gherkin": {"command": "npx", "args": ["-y", "@cucumber/language-server", "--stdio"]}},
    },
    "full_agent": {
        "name": "Full Agent",
        "description": "All tools enabled — full read, write, execute, and web access.",
        "system_prompt": (
            "You are a capable software engineering assistant with access to all tools. "
            "Read code, edit files, run commands, search the web, and manage the project "
            "as needed. Be thorough and verify your work by running tests."
        ),
        "model": "gpt-4.1",
        "agent_mode": True,
        "tools": {
            "enabled": "__ALL__",
            "disabled": [],
        },
        "mcp_servers": {"playwright": {"command": "npx", "args": ["-y", "@playwright/mcp@latest"], "env": {}}, "mermaid": {"command": "npx", "args": ["-y", "@peng-shawn/mermaid-mcp-server"], "env": {}}, "qdrant": {"command": "npx", "args": ["-y", "@qdrant/mcp-server-qdrant"], "env": {}}, "context7": {"command": "npx", "args": ["-y", "@upstash/context7-mcp"], "env": {}}, "n8n": {"command": "npx", "args": ["-y", "n8n-mcp"], "env": {}}},
        "lsp_servers": {"python": {"command": "pyright-langserver", "args": ["--stdio"]}, "java": {"command": "jdtls", "args": []}, "kotlin": {"command": "kotlin-language-server", "args": []}, "gherkin": {"command": "npx", "args": ["-y", "@cucumber/language-server", "--stdio"]}},
    },
}


def get_template(name: str) -> dict | None:
    """Return a deep copy of the named template, or None."""
    import copy
    t = TEMPLATES.get(name)
    return copy.deepcopy(t) if t else None


def list_templates() -> list[dict]:
    """Return summary list of all templates."""
    return [
        {"id": k, "name": v["name"], "description": v["description"]}
        for k, v in TEMPLATES.items()
    ]
