import * as vscode from 'vscode';
import {
    FeatureEntry,
    PipelineStepDef,
    PipelineStepState,
    StepStatus,
} from '../../model/pipeline-models';
import { pipelineSteps } from '../../service/pipeline-step-registry';
import * as pipelineService from '../../service/pipeline-service';
import * as gitHelper from '../../service/git-helper';

// ── Tree Item Types ─────────────────────────────────────────────────────────

export class FeatureTreeItem extends vscode.TreeItem {
    constructor(public readonly entry: FeatureEntry, branch: string) {
        super(entry.dirName, vscode.TreeItemCollapsibleState.Collapsed);

        // Bold the feature that matches current branch
        const branchPrefix = /^(\d{3})-/.exec(branch)?.[1];
        const isBranchMatch = branchPrefix != null && entry.dirName.startsWith(`${branchPrefix}-`);

        // Icon based on progress
        let icon: string;
        let color: vscode.ThemeColor;
        if (entry.totalOutputSteps > 0 && entry.completedSteps >= entry.totalOutputSteps) {
            icon = 'check';
            color = new vscode.ThemeColor('testing.iconPassed');
        } else if (entry.completedSteps > 0) {
            icon = 'circle-half-full';
            color = new vscode.ThemeColor('charts.blue');
        } else {
            icon = 'circle-outline';
            color = new vscode.ThemeColor('descriptionForeground');
        }
        this.iconPath = new vscode.ThemeIcon(icon, color);
        this.description = `${entry.completedSteps}/${entry.totalOutputSteps}`;
        if (isBranchMatch) {
            this.description = `${this.description} (current)`;
        }
        this.contextValue = 'feature';
    }
}

export class StepTreeItem extends vscode.TreeItem {
    constructor(
        public readonly step: PipelineStepDef,
        public readonly featureEntry: FeatureEntry,
        public readonly state: PipelineStepState,
    ) {
        const displayNum = displayNumber(step);
        super(`${displayNum}. ${step.name}`, vscode.TreeItemCollapsibleState.None);

        const status = state.status;
        let icon: string;
        let color: vscode.ThemeColor;
        switch (status) {
            case StepStatus.COMPLETED:
                icon = 'check';
                color = new vscode.ThemeColor('testing.iconPassed');
                break;
            case StepStatus.READY:
                icon = 'circle-outline';
                color = new vscode.ThemeColor('charts.blue');
                break;
            case StepStatus.IN_PROGRESS:
                icon = 'circle-half-full';
                color = new vscode.ThemeColor('charts.blue');
                break;
            case StepStatus.BLOCKED:
                icon = 'error';
                color = new vscode.ThemeColor('testing.iconFailed');
                break;
            default:
                icon = 'circle-outline';
                color = new vscode.ThemeColor('descriptionForeground');
        }
        this.iconPath = new vscode.ThemeIcon(icon, color);
        this.description = statusText(status);
        if (step.isOptional) {
            this.description += ' (optional)';
        }
        this.contextValue = 'step';
        this.command = {
            command: 'speckit.selectStep',
            title: 'Select Step',
            arguments: [step, featureEntry, state],
        };
    }
}

// ── TreeDataProvider ────────────────────────────────────────────────────────

export class PipelineTreeDataProvider implements vscode.TreeDataProvider<vscode.TreeItem> {
    private _onDidChangeTreeData = new vscode.EventEmitter<vscode.TreeItem | undefined>();
    readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

    private features: FeatureEntry[] = [];
    private featureStepStates = new Map<string, Map<PipelineStepDef, PipelineStepState>>();
    private currentBranch = '';

    refresh(): void {
        const basePath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
        if (!basePath) { return; }

        this.currentBranch = gitHelper.currentBranch(basePath, 'main');
        this.features = pipelineService.scanFeatures(basePath, pipelineSteps);

        this.featureStepStates.clear();
        for (const entry of this.features) {
            const stateMap = new Map<PipelineStepDef, PipelineStepState>();
            for (const step of pipelineSteps) {
                const state = new PipelineStepState();
                state.status = pipelineService.deriveStatus(step, basePath, entry.path, state);
                stateMap.set(step, state);
            }
            this.featureStepStates.set(entry.dirName, stateMap);
        }

        this._onDidChangeTreeData.fire(undefined);
    }

    getTreeItem(element: vscode.TreeItem): vscode.TreeItem {
        return element;
    }

    getChildren(element?: vscode.TreeItem): vscode.TreeItem[] {
        if (!element) {
            // Root — return features
            return this.features.map(entry =>
                new FeatureTreeItem(entry, this.currentBranch),
            );
        }

        if (element instanceof FeatureTreeItem) {
            // Feature children — return steps
            const stateMap = this.featureStepStates.get(element.entry.dirName);
            return pipelineSteps.map(step => {
                const state = stateMap?.get(step) ?? new PipelineStepState();
                return new StepTreeItem(step, element.entry, state);
            });
        }

        return [];
    }

    getStepStates(featureDirName: string): Map<PipelineStepDef, PipelineStepState> | undefined {
        return this.featureStepStates.get(featureDirName);
    }

    getCurrentBranch(): string {
        return this.currentBranch;
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

function displayNumber(step: PipelineStepDef): string {
    if (!step.parentId) { return step.number.toString(); }
    const parent = pipelineSteps.find(s => s.id === step.parentId);
    if (!parent) { return step.number.toString(); }
    const siblingIndex = pipelineSteps
        .filter(s => s.parentId === step.parentId)
        .indexOf(step) + 1;
    return `${parent.number}.${siblingIndex}`;
}

function statusText(status: StepStatus): string {
    switch (status) {
        case StepStatus.COMPLETED: return 'Completed';
        case StepStatus.READY: return 'Ready';
        case StepStatus.IN_PROGRESS: return 'In Progress';
        case StepStatus.BLOCKED: return 'Blocked';
        case StepStatus.NOT_STARTED: return 'Not Started';
    }
}
