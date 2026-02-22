import { executeBuiltInTool } from './built-in-tools'

/**
 * Routes tool calls to the appropriate executor.
 */
export class ToolRouter {
  /**
   * Execute a tool call and return the result in Copilot format.
   * Returns: [{ content: [{ value: string }], status: string }, null]
   */
  executeTool(name: string, input: any): any {
    try {
      const result = executeBuiltInTool(name, input)
      return [
        { content: [{ value: result }], status: 'success' },
        null,
      ]
    } catch (e: any) {
      return [
        { content: [{ value: `Error: ${e.message}` }], status: 'error' },
        null,
      ]
    }
  }
}
