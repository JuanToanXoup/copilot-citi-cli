"""Entry point for ``python -m agent_builder``."""

import argparse

from agent_builder.server import start_server


def main():
    parser = argparse.ArgumentParser(description="Agent Builder web UI")
    parser.add_argument("-p", "--port", type=int, default=8420,
                        help="Server port (default: 8420)")
    parser.add_argument("--no-open", action="store_true",
                        help="Don't auto-open browser")
    args = parser.parse_args()
    start_server(port=args.port, open_browser=not args.no_open)


if __name__ == "__main__":
    main()
