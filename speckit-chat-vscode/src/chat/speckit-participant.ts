import * as vscode from 'vscode';
import { ChatRun, ChatRunStatus } from '../model/chat-models';
import { pipelineSteps } from '../service/pipeline-step-registry';
import * as resourceLoader from '../tools/resource-loader';
import * as gitHelper from '../service/git-helper';

/** Maps slash command names to agent file names */
const COMMAND_TO_AGENT: Record<string, string> = {};
for (const step of pipelineSteps) {
    COMMAND_TO_AGENT[step.id] = step.agentFileName;
}
// 'discover' maps to the discovery prompt (no agent file - handled specially)
COMMAND_TO_AGENT['discover'] = '';

/** In-memory run history for the current workspace session */
const runs: ChatRun[] = [];

/** Event emitter for run changes */
const _onRunChanged = new vscode.EventEmitter<void>();
export const onRunChanged = _onRunChanged.event;

export function getRuns(): readonly ChatRun[] {
    return runs;
}

export function registerRun(run: ChatRun): void {
    runs.unshift(run);
    _onRunChanged.fire();
}

export function notifyRunChanged(): void {
    _onRunChanged.fire();
}

export function registerChatParticipant(context: vscode.ExtensionContext): vscode.ChatParticipant {
    const handler: vscode.ChatRequestHandler = async (
        request: vscode.ChatRequest,
        chatContext: vscode.ChatContext,
        stream: vscode.ChatResponseStream,
        token: vscode.CancellationToken,
    ): Promise<vscode.ChatResult> => {
        const basePath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
        if (!basePath) {
            stream.markdown('No workspace folder open. Please open a project first.');
            return {};
        }

        const command = request.command;
        const userArgs = request.prompt;

        // Handle discover command specially (no agent file)
        if (command === 'discover') {
            const prompt =
                'Using your tools and this project as your source of truth, ' +
                'update the `.specify/memory/discovery.md` file ' +
                'with your answers. The file uses `## Category` headings and `- Attribute = Answer` bullet lines. ' +
                'Keep this exact format — do not change delimiters or structure. Example: `- Service name = order-service`. ' +
                'If you cannot find concrete evidence for an attribute, leave the value empty after the `=`. ' +
                'Do not write "Unknown" or guess.';
            return await sendAgentPrompt(stream, token, prompt, 'discover', userArgs, basePath);
        }

        // Map command to agent file
        const agentFileName = command ? COMMAND_TO_AGENT[command] : undefined;
        if (!agentFileName) {
            // No command — use the prompt directly with the default model
            stream.markdown('Use a slash command like `/specify`, `/plan`, `/implement`, etc. to run a pipeline agent.\n\n');
            stream.markdown('Available commands:\n');
            for (const step of pipelineSteps) {
                stream.markdown(`- \`/${step.id}\` — ${step.description}\n`);
            }
            return {};
        }

        // Load agent content
        const agentContent = resourceLoader.readAgent(basePath, agentFileName);
        if (!agentContent) {
            stream.markdown(`Agent file \`${agentFileName}\` not found. Run **Speckit: Init** to download agents.`);
            return {};
        }

        const prompt = agentContent.replace('$ARGUMENTS', userArgs);
        return await sendAgentPrompt(stream, token, prompt, command!, userArgs, basePath);
    };

    const participant = vscode.chat.createChatParticipant('speckit.chat', handler);
    participant.iconPath = vscode.Uri.joinPath(context.extensionUri, 'resources', 'icons', 'speckit.svg');
    context.subscriptions.push(participant);
    return participant;
}

async function sendAgentPrompt(
    stream: vscode.ChatResponseStream,
    token: vscode.CancellationToken,
    prompt: string,
    agent: string,
    userArgs: string,
    basePath: string,
): Promise<vscode.ChatResult> {
    const branch = gitHelper.currentBranch(basePath, '');
    const run = new ChatRun(agent, userArgs || '(no arguments)', branch);
    registerRun(run);

    try {
        const models = await vscode.lm.selectChatModels({ family: 'gpt-4o' });
        const model = models[0];
        if (!model) {
            stream.markdown('No language model available. Ensure GitHub Copilot Chat is active.');
            run.status = ChatRunStatus.FAILED;
            run.errorMessage = 'No language model available';
            run.durationMs = Date.now() - run.startTimeMillis;
            notifyRunChanged();
            return {};
        }

        const messages = [
            vscode.LanguageModelChatMessage.User(prompt),
        ];

        const response = await model.sendRequest(messages, {}, token);

        for await (const chunk of response.text) {
            if (token.isCancellationRequested) {
                run.status = ChatRunStatus.CANCELLED;
                run.durationMs = Date.now() - run.startTimeMillis;
                notifyRunChanged();
                return {};
            }
            stream.markdown(chunk);
        }

        run.status = ChatRunStatus.COMPLETED;
        run.durationMs = Date.now() - run.startTimeMillis;
        notifyRunChanged();
        return {};
    } catch (err) {
        run.status = ChatRunStatus.FAILED;
        run.errorMessage = err instanceof Error ? err.message : String(err);
        run.durationMs = Date.now() - run.startTimeMillis;
        notifyRunChanged();

        if (err instanceof vscode.LanguageModelError) {
            stream.markdown(`Language model error: ${err.message}`);
        }
        return {};
    }
}
