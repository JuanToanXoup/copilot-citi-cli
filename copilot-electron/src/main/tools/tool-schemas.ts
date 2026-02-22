import type { ToolSchema } from '@shared/types'

/**
 * Tool schemas registered with the Copilot LSP server via conversation/registerTools.
 */
export const BUILT_IN_TOOL_SCHEMAS: ToolSchema[] = [
  {
    name: 'read_file',
    description: 'Read the contents of a file at the given path.',
    inputSchema: {
      type: 'object',
      properties: {
        filePath: { type: 'string', description: 'Absolute path to the file to read' },
        startLineNumberBaseOne: { type: 'number', description: 'Start line (1-based, optional)' },
        endLineNumberBaseOne: { type: 'number', description: 'End line (1-based, optional)' },
      },
      required: ['filePath'],
    },
  },
  {
    name: 'list_dir',
    description: 'List the contents of a directory.',
    inputSchema: {
      type: 'object',
      properties: {
        dirPath: { type: 'string', description: 'Absolute path to the directory' },
      },
      required: ['dirPath'],
    },
  },
  {
    name: 'grep_search',
    description: 'Search for a pattern in files using grep.',
    inputSchema: {
      type: 'object',
      properties: {
        pattern: { type: 'string', description: 'Regex pattern to search for' },
        path: { type: 'string', description: 'Directory or file to search in' },
        include: { type: 'string', description: 'Glob pattern to include files' },
      },
      required: ['pattern'],
    },
  },
  {
    name: 'file_search',
    description: 'Search for files by name pattern.',
    inputSchema: {
      type: 'object',
      properties: {
        pattern: { type: 'string', description: 'Glob pattern to match file names' },
        path: { type: 'string', description: 'Directory to search in' },
      },
      required: ['pattern'],
    },
  },
  {
    name: 'create_file',
    description: 'Create a new file with the given content.',
    inputSchema: {
      type: 'object',
      properties: {
        filePath: { type: 'string', description: 'Absolute path for the new file' },
        content: { type: 'string', description: 'Content to write' },
      },
      required: ['filePath', 'content'],
    },
  },
  {
    name: 'insert_edit_into_file',
    description: 'Insert or replace text in a file.',
    inputSchema: {
      type: 'object',
      properties: {
        filePath: { type: 'string', description: 'Absolute path to the file' },
        text: { type: 'string', description: 'Text to insert' },
        startLine: { type: 'number', description: 'Start line (1-based)' },
        endLine: { type: 'number', description: 'End line (1-based)' },
      },
      required: ['filePath', 'text'],
    },
  },
  {
    name: 'run_in_terminal',
    description: 'Run a command in the terminal.',
    inputSchema: {
      type: 'object',
      properties: {
        command: { type: 'string', description: 'Shell command to execute' },
        cwd: { type: 'string', description: 'Working directory' },
      },
      required: ['command'],
    },
  },
  {
    name: 'delegate_task',
    description: 'Delegate a task to a specialized sub-agent.',
    inputSchema: {
      type: 'object',
      properties: {
        description: { type: 'string', description: 'Short description of the task' },
        prompt: { type: 'string', description: 'Detailed task instructions' },
        subagent_type: { type: 'string', description: 'Agent type: Explore, Plan, Bash, or general-purpose' },
        model: { type: 'string', description: 'Optional model override' },
      },
      required: ['description', 'prompt', 'subagent_type'],
    },
  },
]
