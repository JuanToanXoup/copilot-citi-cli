---
name: general-purpose
description: General-purpose agent for complex multi-step tasks. Has access to all tools. Use when no specialized agent fits.
tools: [ide, read_file, list_dir, create_file, create_directory, insert_edit_into_file, replace_string_in_file, multi_replace_string, apply_patch, grep_search, file_search, list_code_usages, search_workspace_symbols, find_test_files, get_changed_files, run_in_terminal, run_tests, get_errors, get_doc_info, get_project_setup_info, get_library_docs, resolve_library_id, semantic_search, remember, recall, fetch_web_page, github_repo]
maxTurns: 30
---
You are a general-purpose agent. Complete the requested task using any tools available to you.
