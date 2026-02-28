import * as vscode from 'vscode';

/**
 * Generate a nonce for Content Security Policy in webviews.
 */
export function getNonce(): string {
    let text = '';
    const possible = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    for (let i = 0; i < 32; i++) {
        text += possible.charAt(Math.floor(Math.random() * possible.length));
    }
    return text;
}

/**
 * Build a basic HTML wrapper with VS Code webview toolkit styling.
 */
export function getWebviewHtml(
    webview: vscode.Webview,
    title: string,
    body: string,
    script?: string,
): string {
    const nonce = getNonce();
    return `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';">
    <title>${escapeHtml(title)}</title>
    <style>
        body {
            font-family: var(--vscode-font-family);
            font-size: var(--vscode-font-size);
            color: var(--vscode-foreground);
            background: var(--vscode-sideBar-background);
            padding: 12px 16px;
            margin: 0;
        }
        h1, h2, h3 { margin-top: 0; font-weight: 600; }
        h1 { font-size: 1.4em; }
        h2 { font-size: 1.2em; }
        h3 { font-size: 1.05em; }
        .section { margin-bottom: 16px; }
        .badge {
            display: inline-block;
            padding: 2px 8px;
            border-radius: 4px;
            font-size: 0.85em;
            font-weight: 600;
        }
        .badge-completed { background: var(--vscode-testing-iconPassed); color: #fff; }
        .badge-ready { background: var(--vscode-charts-blue, #007acc); color: #fff; }
        .badge-blocked { background: var(--vscode-testing-iconFailed); color: #fff; }
        .badge-in-progress { background: var(--vscode-charts-blue, #007acc); color: #fff; }
        .badge-not-started { background: var(--vscode-descriptionForeground); color: #fff; }
        .check-item { margin: 2px 0; }
        .check-pass { color: var(--vscode-testing-iconPassed); }
        .check-fail { color: var(--vscode-testing-iconFailed); }
        .muted { color: var(--vscode-descriptionForeground); }
        button {
            background: var(--vscode-button-background);
            color: var(--vscode-button-foreground);
            border: none;
            padding: 6px 14px;
            border-radius: 2px;
            cursor: pointer;
            font-size: var(--vscode-font-size);
        }
        button:hover { background: var(--vscode-button-hoverBackground); }
        textarea, select, input {
            background: var(--vscode-input-background);
            color: var(--vscode-input-foreground);
            border: 1px solid var(--vscode-input-border);
            padding: 6px 8px;
            font-family: var(--vscode-font-family);
            font-size: var(--vscode-font-size);
            border-radius: 2px;
            width: 100%;
            box-sizing: border-box;
        }
        textarea { resize: vertical; min-height: 60px; }
        table {
            width: 100%;
            border-collapse: collapse;
            margin: 8px 0;
        }
        th, td {
            padding: 6px 10px;
            text-align: left;
            border-bottom: 1px solid var(--vscode-widget-border, #333);
        }
        th { font-weight: 600; }
        a { color: var(--vscode-textLink-foreground); text-decoration: none; }
        a:hover { text-decoration: underline; }
        .task-item { display: flex; align-items: center; gap: 6px; padding: 2px 0; }
        .task-id { font-weight: 600; min-width: 40px; }
        .task-tags { color: var(--vscode-descriptionForeground); font-size: 0.9em; }
        .phase-header {
            font-weight: 600;
            margin-top: 8px;
            margin-bottom: 4px;
        }
    </style>
</head>
<body>
${body}
${script ? `<script nonce="${nonce}">${script}</script>` : ''}
</body>
</html>`;
}

export function escapeHtml(text: string): string {
    return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

export function statusBadgeClass(status: string): string {
    switch (status) {
        case 'COMPLETED': return 'badge-completed';
        case 'READY': return 'badge-ready';
        case 'BLOCKED': return 'badge-blocked';
        case 'IN_PROGRESS': return 'badge-in-progress';
        default: return 'badge-not-started';
    }
}
