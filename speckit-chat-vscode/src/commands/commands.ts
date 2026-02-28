import * as vscode from 'vscode';
import { execSync } from 'child_process';
import { PipelineStepDef } from '../model/pipeline-models';
import * as resourceLoader from '../tools/resource-loader';

/**
 * Register all extension commands.
 */
export function registerCommands(
    context: vscode.ExtensionContext,
    refreshPipeline: () => void,
): void {
    // Refresh pipeline tree
    context.subscriptions.push(
        vscode.commands.registerCommand('speckit.refreshPipeline', () => {
            refreshPipeline();
        }),
    );

    // Run a pipeline step — triggered from step detail webview
    context.subscriptions.push(
        vscode.commands.registerCommand('speckit.runStep', (stepId: string, args: string) => {
            const basePath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
            if (!basePath) { return; }

            // Find the step by ID
            const { pipelineSteps } = require('../service/pipeline-step-registry');
            const step = (pipelineSteps as PipelineStepDef[]).find(s => s.id === stepId);
            if (!step) { return; }

            const agentContent = resourceLoader.readAgent(basePath, step.agentFileName);
            if (!agentContent) {
                vscode.window.showWarningMessage(`Agent file ${step.agentFileName} not found. Run "Speckit: Init" first.`);
                return;
            }

            // Open Copilot Chat with the command
            vscode.commands.executeCommand('workbench.action.chat.open', {
                query: `@speckit /${step.id} ${args}`,
            });
        }),
    );

    // Init Speckit — download agents
    context.subscriptions.push(
        vscode.commands.registerCommand('speckit.initSpeckit', () => {
            const basePath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
            if (!basePath) {
                vscode.window.showWarningMessage('No workspace folder open.');
                return;
            }

            const isWindows = process.platform === 'win32';
            const shellType = isWindows ? 'ps' : 'sh';

            let script: string;
            if (isWindows) {
                script = [
                    `$ErrorActionPreference = 'Stop'`,
                    `Write-Host 'Fetching latest spec-kit release...'`,
                    `$release = Invoke-RestMethod -Uri 'https://api.github.com/repos/github/spec-kit/releases/latest'`,
                    `$asset = $release.assets | Where-Object { $_.name -like '*copilot-${shellType}*' } | Select-Object -First 1`,
                    `if (-not $asset) { Write-Error 'No copilot-${shellType} asset found'; exit 1 }`,
                    `$tmpZip = Join-Path $env:TEMP ('speckit-' + [guid]::NewGuid().ToString('N') + '.zip')`,
                    `Write-Host "Downloading $($asset.browser_download_url)"`,
                    `Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $tmpZip`,
                    `Write-Host 'Extracting to ${basePath.replace(/\\/g, '\\\\')}...'`,
                    `Expand-Archive -Path $tmpZip -DestinationPath '${basePath.replace(/'/g, "''")}' -Force`,
                    `Remove-Item $tmpZip -Force`,
                    `Write-Host 'Done.'`,
                ].join('\n');
            } else {
                script = [
                    'set -e',
                    'TMPZIP=$(mktemp /tmp/speckit-XXXXXX.zip)',
                    'echo "Fetching latest spec-kit release..."',
                    `URL=$(curl -sL https://api.github.com/repos/github/spec-kit/releases/latest \\`,
                    `  | grep -o '"browser_download_url":[^,]*copilot-${shellType}[^"]*' \\`,
                    `  | cut -d'"' -f4)`,
                    'if [ -z "$URL" ]; then echo "ERROR: No copilot-sh asset found"; exit 1; fi',
                    'echo "Downloading $URL"',
                    'curl -Lo "$TMPZIP" "$URL"',
                    `echo "Extracting to ${basePath}..."`,
                    `unzip -o "$TMPZIP" -d "${basePath}"`,
                    'rm -f "$TMPZIP"',
                    'echo "Done."',
                ].join('\n');
            }

            const terminal = vscode.window.createTerminal({ name: 'Speckit Install', cwd: basePath });
            terminal.show();
            if (isWindows) {
                terminal.sendText(`powershell -NoProfile -Command "${script.replace(/"/g, '\\"')}"`);
            } else {
                terminal.sendText(`bash -c '${script.replace(/'/g, "'\\''")}'`);
            }
        }),
    );

    // New feature specification
    context.subscriptions.push(
        vscode.commands.registerCommand('speckit.newFeature', async () => {
            const description = await vscode.window.showInputBox({
                prompt: 'Describe the feature you want to build',
                placeHolder: 'e.g., User authentication with OAuth2 and JWT',
            });
            if (!description) { return; }

            vscode.commands.executeCommand('workbench.action.chat.open', {
                query: `@speckit /specify ${description}`,
            });
        }),
    );

    // Internal command for step selection (used by tree items)
    context.subscriptions.push(
        vscode.commands.registerCommand('speckit.selectStep', () => {
            // Handled by the tree provider's selection callback
        }),
    );
}
