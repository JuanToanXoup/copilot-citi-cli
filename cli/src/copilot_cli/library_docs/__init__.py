"""Local library documentation index.

Maps library names/aliases to their local doc directories and metadata.
Used by resolve_library_id and get_library_docs tools.
"""

import os
import re

_DOCS_DIR = os.path.dirname(__file__)

# Library registry: maps canonical ID to metadata
LIBRARIES = {
    "playwright": {
        "id": "playwright",
        "title": "Playwright",
        "description": "Browser automation library for end-to-end testing (Java, Python, Node.js, .NET)",
        "aliases": ["playwright", "playwright-java", "microsoft/playwright", "pw"],
        "docs_dir": os.path.join(_DOCS_DIR, "playwright"),
    },
    "selenium": {
        "id": "selenium",
        "title": "Selenium WebDriver",
        "description": "Browser automation framework for web testing (Java, Python, C#, Ruby, JS)",
        "aliases": ["selenium", "selenium-java", "seleniumhq", "webdriver", "selenium-webdriver"],
        "docs_dir": os.path.join(_DOCS_DIR, "selenium"),
    },
    "cucumber": {
        "id": "cucumber",
        "title": "Cucumber",
        "description": "BDD testing framework — runs Gherkin scenarios as automated tests (Java, JS, Ruby)",
        "aliases": ["cucumber", "cucumber-java", "cucumber-jvm", "cucumber-junit"],
        "docs_dir": os.path.join(_DOCS_DIR, "cucumber"),
    },
    "gherkin": {
        "id": "gherkin",
        "title": "Gherkin",
        "description": "Plain-text language for writing BDD scenarios (Given/When/Then syntax)",
        "aliases": ["gherkin", "feature-file", "bdd", "given-when-then"],
        "docs_dir": os.path.join(_DOCS_DIR, "gherkin"),
    },
    "java": {
        "id": "java",
        "title": "Java SE",
        "description": "Java Standard Edition API — collections, streams, strings, IO, concurrency",
        "aliases": ["java", "java-se", "jdk", "java-api", "java-lang"],
        "docs_dir": os.path.join(_DOCS_DIR, "java"),
    },
}


def resolve(name: str) -> list[dict]:
    """Find libraries matching a name/alias. Returns list of matches."""
    name_lower = name.lower().strip()
    results = []
    for lib_id, meta in LIBRARIES.items():
        # Exact match on ID or alias
        if name_lower == lib_id or name_lower in meta["aliases"]:
            results.insert(0, meta)  # exact matches first
        # Partial match
        elif name_lower in lib_id or any(name_lower in a for a in meta["aliases"]):
            results.append(meta)
        elif lib_id in name_lower or any(a in name_lower for a in meta["aliases"]):
            results.append(meta)
    return results


def search_docs(library_id: str, query: str, max_chars: int = 4000) -> str:
    """Search local docs for a library by query. Returns matching sections."""
    meta = LIBRARIES.get(library_id)
    if not meta:
        return ""

    docs_dir = meta["docs_dir"]
    if not os.path.isdir(docs_dir):
        return ""

    # Load all markdown files in the library's docs dir
    all_sections = []
    for fname in sorted(os.listdir(docs_dir)):
        if not fname.endswith(".md"):
            continue
        fpath = os.path.join(docs_dir, fname)
        with open(fpath, "r", errors="replace") as f:
            content = f.read()
        # Split by ## headings into sections
        sections = re.split(r'(?=^## )', content, flags=re.MULTILINE)
        for section in sections:
            if section.strip():
                all_sections.append((fname, section.strip()))

    if not all_sections:
        return ""

    # Score sections by query relevance
    query_terms = set(query.lower().split())
    scored = []
    for fname, section in all_sections:
        section_lower = section.lower()
        # Count how many query terms appear in this section
        score = sum(1 for t in query_terms if t in section_lower)
        # Bonus for terms in first line (heading)
        first_line = section.split("\n")[0].lower()
        score += sum(2 for t in query_terms if t in first_line)
        if score > 0:
            scored.append((score, fname, section))

    if not scored:
        # No matches — return first few sections as overview
        result = []
        total = 0
        for fname, section in all_sections[:5]:
            chunk = section[:800]
            if total + len(chunk) > max_chars:
                break
            result.append(chunk)
            total += len(chunk)
        return "\n\n---\n\n".join(result)

    # Return top-scoring sections
    scored.sort(key=lambda x: x[0], reverse=True)
    result = []
    total = 0
    for score, fname, section in scored[:8]:
        chunk = section[:1500]
        if total + len(chunk) > max_chars:
            remaining = len(scored) - len(result)
            if remaining > 0:
                result.append(f"... {remaining} more matching sections. Narrow your query.")
            break
        result.append(chunk)
        total += len(chunk)

    return "\n\n---\n\n".join(result)
