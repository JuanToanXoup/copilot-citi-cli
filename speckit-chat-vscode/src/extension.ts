import * as vscode from 'vscode';
import { setExtensionPath } from './tools/resource-loader';
import { registerChatParticipant } from './chat/speckit-participant';
import { PipelineTreeDataProvider, StepTreeItem, FeatureTreeItem } from './ui/pipeline/pipeline-tree-provider';
import { StepDetailViewProvider } from './ui/webview/step-detail-panel';
import { DiscoveryViewProvider } from './ui/webview/discovery-panel';
import { SessionViewProvider } from './ui/webview/session-panel';
import { OnboardingViewProvider } from './ui/webview/onboarding-panel';
import { registerCommands } from './commands/commands';

export function activate(context: vscode.ExtensionContext): void {
    // Set extension path for resource loader
    setExtensionPath(context.extensionPath);

    // Register @speckit chat participant
    registerChatParticipant(context);

    // Pipeline tree view
    const pipelineTreeProvider = new PipelineTreeDataProvider();
    const treeView = vscode.window.createTreeView('speckit.pipeline', {
        treeDataProvider: pipelineTreeProvider,
        showCollapseAll: true,
    });
    context.subscriptions.push(treeView);

    // Step detail webview
    const stepDetailProvider = new StepDetailViewProvider(context);
    context.subscriptions.push(
        vscode.window.registerWebviewViewProvider(StepDetailViewProvider.viewType, stepDetailProvider),
    );

    // Discovery webview
    const discoveryProvider = new DiscoveryViewProvider();
    context.subscriptions.push(
        vscode.window.registerWebviewViewProvider(DiscoveryViewProvider.viewType, discoveryProvider),
    );

    // Sessions webview
    const sessionProvider = new SessionViewProvider();
    context.subscriptions.push(
        vscode.window.registerWebviewViewProvider(SessionViewProvider.viewType, sessionProvider),
    );

    // Onboarding webview
    const onboardingProvider = new OnboardingViewProvider();
    context.subscriptions.push(
        vscode.window.registerWebviewViewProvider(OnboardingViewProvider.viewType, onboardingProvider),
    );

    // Wire tree selection to step detail
    treeView.onDidChangeSelection(e => {
        const item = e.selection[0];
        if (item instanceof StepTreeItem) {
            stepDetailProvider.showStep(item.step, item.state, item.featureEntry);
        } else if (item instanceof FeatureTreeItem) {
            const states = pipelineTreeProvider.getStepStates(item.entry.dirName);
            stepDetailProvider.showFeature(item.entry, states);
        }
    });

    // Register commands
    registerCommands(context, () => pipelineTreeProvider.refresh());

    // File system watcher — auto-refresh pipeline on file changes
    const basePath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    if (basePath) {
        const specsWatcher = vscode.workspace.createFileSystemWatcher(
            new vscode.RelativePattern(basePath, '{specs/**,**/.specify/**}'),
        );
        specsWatcher.onDidChange(() => pipelineTreeProvider.refresh());
        specsWatcher.onDidCreate(() => pipelineTreeProvider.refresh());
        specsWatcher.onDidDelete(() => pipelineTreeProvider.refresh());
        context.subscriptions.push(specsWatcher);
    }

    // Window focus — refresh on regain focus (catches branch switches)
    vscode.window.onDidChangeWindowState(e => {
        if (e.focused) { pipelineTreeProvider.refresh(); }
    }, null, context.subscriptions);

    // Initial refresh
    pipelineTreeProvider.refresh();
}

export function deactivate(): void {
    // All disposables are registered with context.subscriptions
}
