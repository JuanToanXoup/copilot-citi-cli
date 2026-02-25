"""Entry point for ``python -m speckit_mcp``.

Starts the SpecKit MCP server on stdio.
"""

from speckit_mcp.server import MCPServer
from speckit_mcp.tools import register_tools
from speckit_mcp.resources import register_resources


def main():
    server = MCPServer()
    register_tools(server)
    register_resources(server)
    server.run_stdio()


if __name__ == "__main__":
    main()
