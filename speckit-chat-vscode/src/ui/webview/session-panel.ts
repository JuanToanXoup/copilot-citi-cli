import * as vscode from 'vscode';
import { ChatRun, ChatRunStatus } from '../../model/chat-models';
import * as resourceLoader from '../../tools/resource-loader';
import * as gitHelper from '../../service/git-helper';
import { getRuns, onRunChanged } from '../../chat/speckit-participant';
import { getWebviewHtml, escapeHtml } from './webview-utils';

export class SessionViewProvider implements vscode.WebviewViewProvider {
    public static readonly viewType = 'speckit.sessions';
    private _view?: vscode.WebviewView;

    resolveWebviewView(webviewView: vscode.WebviewView): void {
        this._view = webviewView;
        webviewView.webview.options = { enableScripts: true };

        webviewView.webview.onDidReceiveMessage(msg => {
            if (msg.command === 'send') {
                this.sendMessage(msg.agentFileName, msg.args);
            }
        });

        // Re-render on run changes
        onRunChanged(() => this.render());

        this.render();
    }

    private render(): void {
        if (!this._view) { return; }

        const basePath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? '';
        const agents = resourceLoader.listAgents(basePath);

        let body = '';
        body += `<h1>Sessions</h1>`;
        body += `<p class="muted">Launch agents and track their sessions.</p>`;

        // Agent selector + prompt
        body += `<div class="section">`;
        body += `<div style="display: flex; gap: 4px; margin-bottom: 8px;">`;
        body += `<select id="agentSelect" style="flex: 1;">`;
        for (const a of agents) {
            const slug = a.replace('speckit.', '').replace('.agent.md', '');
            body += `<option value="${escapeHtml(a)}">${escapeHtml(slug)}</option>`;
        }
        body += `</select>`;
        body += `<button onclick="sendMessage()">Send</button>`;
        body += `</div>`;
        body += `<textarea id="promptField" rows="3" placeholder="Enter arguments..."></textarea>`;
        body += `</div>`;

        // Session history table
        const runs = getRuns();
        if (runs.length > 0) {
            body += `<table>`;
            body += `<tr><th>Time</th><th>Branch</th><th>Agent</th><th>Prompt</th><th>Status</th><th>Duration</th></tr>`;
            for (const run of runs) {
                const time = formatTime(run.startTimeMillis);
                const statusLabel = statusText(run.status);
                const statusCls = statusClass(run.status);
                const duration = run.status === ChatRunStatus.RUNNING ? '...' : `${(run.durationMs / 1000).toFixed(1)}s`;
                const promptShort = run.prompt.length > 60 ? run.prompt.substring(0, 60) + '...' : run.prompt;
                body += `<tr>`;
                body += `<td>${escapeHtml(time)}</td>`;
                body += `<td>${escapeHtml(run.branch)}</td>`;
                body += `<td>${escapeHtml(run.agent)}</td>`;
                body += `<td>${escapeHtml(promptShort)}</td>`;
                body += `<td><span class="${statusCls}">${escapeHtml(statusLabel)}</span></td>`;
                body += `<td>${escapeHtml(duration)}</td>`;
                body += `</tr>`;
            }
            body += `</table>`;
        } else {
            body += `<p class="muted">No sessions yet.</p>`;
        }

        const script = `
            const vscode = acquireVsCodeApi();
            function sendMessage() {
                const agentSelect = document.getElementById('agentSelect');
                const promptField = document.getElementById('promptField');
                vscode.postMessage({
                    command: 'send',
                    agentFileName: agentSelect.value,
                    args: promptField.value.trim()
                });
                promptField.value = '';
            }
            document.getElementById('promptField').addEventListener('keydown', function(e) {
                if (e.ctrlKey && e.key === 'Enter') { sendMessage(); }
            });
        `;

        this._view.webview.html = getWebviewHtml(this._view.webview, 'Sessions', body, script);
    }

    private sendMessage(agentFileName: string, args: string): void {
        const basePath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
        if (!basePath) { return; }

        const agentContent = resourceLoader.readAgent(basePath, agentFileName);
        if (!agentContent) { return; }

        const prompt = agentContent.replace('$ARGUMENTS', args);
        const slug = agentFileName.replace('speckit.', '').replace('.agent.md', '');

        // Open Copilot Chat with the agent prompt
        vscode.commands.executeCommand('workbench.action.chat.open', {
            query: `@speckit /${slug} ${args}`,
        });
    }
}

function formatTime(timestamp: number): string {
    const d = new Date(timestamp);
    const pad = (n: number) => n.toString().padStart(2, '0');
    const h = d.getHours() % 12 || 12;
    const ampm = d.getHours() >= 12 ? 'PM' : 'AM';
    return `${pad(d.getMonth() + 1)}/${pad(d.getDate())} ${pad(h)}:${pad(d.getMinutes())}:${pad(d.getSeconds())} ${ampm}`;
}

function statusText(status: ChatRunStatus): string {
    switch (status) {
        case ChatRunStatus.RUNNING: return 'Running...';
        case ChatRunStatus.COMPLETED: return 'Completed';
        case ChatRunStatus.FAILED: return 'Failed';
        case ChatRunStatus.CANCELLED: return 'Cancelled';
    }
}

function statusClass(status: ChatRunStatus): string {
    switch (status) {
        case ChatRunStatus.RUNNING: return '';
        case ChatRunStatus.COMPLETED: return 'check-pass';
        case ChatRunStatus.FAILED: return 'check-fail';
        case ChatRunStatus.CANCELLED: return 'muted';
    }
}
