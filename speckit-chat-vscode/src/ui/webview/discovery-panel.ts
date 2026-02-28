import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as discoveryService from '../../service/discovery-service';
import * as resourceLoader from '../../tools/resource-loader';
import * as gitHelper from '../../service/git-helper';
import { TableRow } from '../../model/discovery-models';
import { getWebviewHtml, escapeHtml } from './webview-utils';

export class DiscoveryViewProvider implements vscode.WebviewViewProvider {
    public static readonly viewType = 'speckit.discovery';
    private _view?: vscode.WebviewView;
    private _watcher?: vscode.FileSystemWatcher;
    private _debounceTimer?: ReturnType<typeof setTimeout>;
    private _syncing = false;

    resolveWebviewView(webviewView: vscode.WebviewView): void {
        this._view = webviewView;
        webviewView.webview.options = { enableScripts: true };

        webviewView.webview.onDidReceiveMessage(msg => {
            switch (msg.command) {
                case 'loadTemplate': this.loadTemplate(msg.fileName); break;
                case 'updateAnswer': this.updateAnswer(msg.category, msg.attribute, msg.answer); break;
                case 'askCopilotCategory': this.askCopilotCategory(msg.category); break;
                case 'askCopilotAll': this.askCopilotAll(); break;
                case 'generateConstitution': this.generateConstitution(); break;
            }
        });

        // Watch the discovery.md file for external changes
        const basePath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
        if (basePath) {
            const memPattern = new vscode.RelativePattern(basePath, '.specify/memory/discovery.md');
            this._watcher = vscode.workspace.createFileSystemWatcher(memPattern);
            this._watcher.onDidChange(() => this.reloadFromDisk());
            this._watcher.onDidCreate(() => this.reloadFromDisk());
            this._watcher.onDidDelete(() => this.render([]));
        }

        // Initial load
        this.reloadFromDisk();
    }

    private get memoryFilePath(): string {
        const basePath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? '';
        return path.join(basePath, '.specify', 'memory', 'discovery.md');
    }

    private reloadFromDisk(): void {
        if (this._syncing) { return; }
        const filePath = this.memoryFilePath;
        let rows: TableRow[] = [];
        if (fs.existsSync(filePath)) {
            const content = fs.readFileSync(filePath, 'utf-8');
            rows = discoveryService.parseDiscovery(content);
        }
        this.render(rows);
    }

    private render(rows: TableRow[]): void {
        if (!this._view) { return; }

        const basePath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? '';
        const templates = resourceLoader.listDiscoveries(basePath);
        const grouped = new Map<string, Array<{ attribute: string; answer: string }>>();
        for (const row of rows) {
            if (!grouped.has(row.category)) { grouped.set(row.category, []); }
            grouped.get(row.category)!.push({ attribute: row.attribute, answer: row.answer });
        }

        const categories = [...grouped.keys()];

        // Build template selector
        let body = '';
        body += `<h1>Discovery</h1>`;
        body += `<p class="muted">Answer project properties to generate your project constitution.</p>`;

        // Template controls
        body += `<div class="section" style="display: flex; gap: 4px; align-items: center;">`;
        body += `<select id="templateSelect" style="flex: 1;">`;
        for (const t of templates) {
            body += `<option value="${escapeHtml(t)}">${escapeHtml(t)}</option>`;
        }
        body += `</select>`;
        body += `<button onclick="loadTemplate()">Load</button>`;
        body += `<button onclick="askCopilotAll()">Ask All</button>`;
        body += `<button onclick="generateConstitution()">Constitution</button>`;
        body += `</div>`;

        // Category navigation + tables
        if (categories.length > 0) {
            body += `<div style="display: flex; gap: 12px;">`;

            // Category list
            body += `<div style="min-width: 140px;">`;
            for (const cat of categories) {
                const attrs = grouped.get(cat) || [];
                const hasAnswers = attrs.some(a => a.answer.trim() !== '');
                const icon = hasAnswers ? '\u2713' : '\u25CB';
                const cls = hasAnswers ? 'check-pass' : 'muted';
                body += `<div style="padding: 4px 0; cursor: pointer;" onclick="selectCategory('${escapeHtml(cat)}')"><span class="${cls}">${icon}</span> ${escapeHtml(cat)}</div>`;
            }
            body += `</div>`;

            // Detail area
            body += `<div id="categoryDetail" style="flex: 1;"></div>`;
            body += `</div>`;

            // Hidden data for JavaScript
            const dataJson = JSON.stringify(Object.fromEntries(grouped));
            body += `<script type="application/json" id="discoveryData">${dataJson}</script>`;
        } else {
            body += `<p class="muted">No discovery data loaded. Select a template and click <b>Load</b>.</p>`;
        }

        const script = `
            const vscode = acquireVsCodeApi();
            let discoveryData = {};
            try { discoveryData = JSON.parse(document.getElementById('discoveryData')?.textContent || '{}'); } catch {}
            let currentCategory = Object.keys(discoveryData)[0] || '';

            if (currentCategory) selectCategory(currentCategory);

            function selectCategory(cat) {
                currentCategory = cat;
                const detail = document.getElementById('categoryDetail');
                if (!detail) return;
                const attrs = discoveryData[cat] || [];
                let html = '<h2>' + cat + '</h2>';
                html += '<table>';
                attrs.forEach(function(a, i) {
                    html += '<tr><td style="color: var(--vscode-descriptionForeground); white-space: nowrap;">' + a.attribute + '</td>';
                    html += '<td><input type="text" value="' + escapeAttr(a.answer) + '" onchange="updateAnswer(\\'' + escapeAttr(cat) + '\\', \\'' + escapeAttr(a.attribute) + '\\', this.value)" /></td></tr>';
                });
                html += '</table>';
                html += '<button onclick="askCopilotCategory(\\'' + escapeAttr(cat) + '\\')">Ask Copilot \\u25B7</button>';
                detail.innerHTML = html;
            }

            function escapeAttr(s) { return s.replace(/&/g,'&amp;').replace(/'/g,"\\\\'").replace(/"/g,'&quot;').replace(/</g,'&lt;'); }

            function loadTemplate() {
                const sel = document.getElementById('templateSelect');
                vscode.postMessage({ command: 'loadTemplate', fileName: sel.value });
            }

            function updateAnswer(category, attribute, answer) {
                if (discoveryData[category]) {
                    const item = discoveryData[category].find(function(a) { return a.attribute === attribute; });
                    if (item) item.answer = answer;
                }
                vscode.postMessage({ command: 'updateAnswer', category, attribute, answer });
            }

            function askCopilotCategory(category) {
                vscode.postMessage({ command: 'askCopilotCategory', category });
            }

            function askCopilotAll() {
                vscode.postMessage({ command: 'askCopilotAll' });
            }

            function generateConstitution() {
                vscode.postMessage({ command: 'generateConstitution' });
            }
        `;

        this._view.webview.html = getWebviewHtml(this._view.webview, 'Discovery', body, script);
    }

    private loadTemplate(fileName: string): void {
        const basePath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
        if (!basePath) { return; }
        const content = resourceLoader.readDiscovery(basePath, fileName);
        if (!content) { return; }
        const rows = discoveryService.parseDiscovery(discoveryService.extractBody(content));
        this.writeMemoryFile(rows);
        this.render(rows);
    }

    private updateAnswer(category: string, attribute: string, answer: string): void {
        // Debounce writes
        if (this._debounceTimer) { clearTimeout(this._debounceTimer); }
        this._debounceTimer = setTimeout(() => {
            const filePath = this.memoryFilePath;
            let rows: TableRow[] = [];
            if (fs.existsSync(filePath)) {
                rows = discoveryService.parseDiscovery(fs.readFileSync(filePath, 'utf-8'));
            }
            const row = rows.find(r => r.category === category && r.attribute === attribute);
            if (row) {
                row.answer = answer;
            }
            this.writeMemoryFile(rows);
        }, 300);
    }

    private writeMemoryFile(rows: TableRow[]): void {
        const basePath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
        if (!basePath) { return; }

        const grouped = new Map<string, Array<{ attribute: string; answer: string }>>();
        for (const row of rows) {
            if (!grouped.has(row.category)) { grouped.set(row.category, []); }
            grouped.get(row.category)!.push({ attribute: row.attribute, answer: row.answer });
        }

        const categories = [...grouped.entries()].map(([category, attrs]) => ({
            category,
            attributes: attrs,
        }));

        const text = discoveryService.serializeToMarkdown(categories);

        this._syncing = true;
        try {
            const dir = path.dirname(this.memoryFilePath);
            fs.mkdirSync(dir, { recursive: true });
            const isNew = !fs.existsSync(this.memoryFilePath);
            fs.writeFileSync(this.memoryFilePath, text, 'utf-8');
            if (isNew) {
                gitHelper.gitAdd(basePath, path.relative(basePath, this.memoryFilePath));
            }
        } finally {
            setTimeout(() => { this._syncing = false; }, 100);
        }
    }

    private askCopilotCategory(category: string): void {
        const prompt =
            `Using your tools and this project as your source of truth, ` +
            `update only the "${category}" section in the \`.specify/memory/discovery.md\` file ` +
            `with your answers. The file uses \`## Category\` headings and \`- Attribute = Answer\` bullet lines. ` +
            `Keep this exact format — do not change delimiters or structure. Example: \`- Service name = order-service\`. ` +
            `If you cannot find concrete evidence for an attribute, leave the value empty after the \`=\`. ` +
            `Do not write "Unknown" or guess.`;
        vscode.commands.executeCommand('workbench.action.chat.open', { query: `@speckit ${prompt}` });
    }

    private askCopilotAll(): void {
        const prompt =
            `Using your tools and this project as your source of truth, ` +
            `update the \`.specify/memory/discovery.md\` file ` +
            `with your answers. The file uses \`## Category\` headings and \`- Attribute = Answer\` bullet lines. ` +
            `Keep this exact format — do not change delimiters or structure. Example: \`- Service name = order-service\`. ` +
            `If you cannot find concrete evidence for an attribute, leave the value empty after the \`=\`. ` +
            `Do not write "Unknown" or guess.`;
        vscode.commands.executeCommand('workbench.action.chat.open', { query: `@speckit ${prompt}` });
    }

    private generateConstitution(): void {
        const basePath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
        if (!basePath) { return; }

        const filePath = this.memoryFilePath;
        if (!fs.existsSync(filePath)) { return; }
        const rows = discoveryService.parseDiscovery(fs.readFileSync(filePath, 'utf-8'));
        const args = rows.map(r => `${r.category} / ${r.attribute}: ${r.answer || '(unanswered)'}`).join('\n');

        const agentContent = resourceLoader.readAgent(basePath, 'speckit.constitution.agent.md');
        if (!agentContent) { return; }
        const prompt = agentContent.replace('$ARGUMENTS', args);
        vscode.commands.executeCommand('workbench.action.chat.open', { query: `@speckit /constitution ${prompt}` });
    }
}
