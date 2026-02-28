import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import {
    PipelineStepDef,
    PipelineStepState,
    FeatureEntry,
    StepStatus,
} from '../../model/pipeline-models';
import { pipelineSteps } from '../../service/pipeline-step-registry';
import * as pipelineService from '../../service/pipeline-service';
import * as tasksParser from '../../tools/tasks-parser';
import { getWebviewHtml, escapeHtml, statusBadgeClass } from './webview-utils';

export class StepDetailViewProvider implements vscode.WebviewViewProvider {
    public static readonly viewType = 'speckit.stepDetail';
    private _view?: vscode.WebviewView;
    private _context: vscode.ExtensionContext;

    constructor(context: vscode.ExtensionContext) {
        this._context = context;
    }

    resolveWebviewView(webviewView: vscode.WebviewView): void {
        this._view = webviewView;
        webviewView.webview.options = { enableScripts: true };

        webviewView.webview.onDidReceiveMessage(msg => {
            if (msg.command === 'runStep') {
                vscode.commands.executeCommand('speckit.runStep', msg.stepId, msg.args);
            } else if (msg.command === 'openFile') {
                const uri = vscode.Uri.file(msg.filePath);
                vscode.commands.executeCommand('vscode.open', uri);
            } else if (msg.command === 'saveArgs') {
                this._context.workspaceState.update(`speckit.args.${msg.featureDir}.${msg.stepId}`, msg.args);
            }
        });

        this.showEmpty();
    }

    showStep(step: PipelineStepDef, state: PipelineStepState, featureEntry: FeatureEntry): void {
        if (!this._view) { return; }

        const basePath = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? '';
        const featureDir = featureEntry.path;
        const savedArgs = this._context.workspaceState.get<string>(`speckit.args.${featureEntry.dirName}.${step.id}`) ?? step.defaultArgs;
        const statusLabel = statusText(state.status);
        const badgeClass = statusBadgeClass(state.status);
        const displayNum = displayNumber(step);
        const isReadOnly = step.id === 'specify';

        let body = '';
        body += `<h1>Step ${escapeHtml(displayNum)}: ${escapeHtml(step.name)}</h1>`;
        if (step.isOptional) {
            body += `<p class="muted">(optional)</p>`;
        }
        body += `<p>${escapeHtml(step.description)}</p>`;
        body += `<p><span class="badge ${badgeClass}">${escapeHtml(statusLabel)}</span></p>`;

        // Clarification markers
        if (step.id === 'clarify') {
            const specFile = pipelineService.resolveArtifactFile('spec.md', false, basePath, featureDir);
            if (specFile && fs.existsSync(specFile)) {
                const specContent = fs.readFileSync(specFile, 'utf-8');
                const markerCount = (specContent.match(/\[NEEDS CLARIFICATION/g) || []).length;
                if (markerCount === 0) {
                    body += `<p class="check-pass">No clarification markers in spec.md</p>`;
                } else {
                    body += `<p class="check-fail">${markerCount} [NEEDS CLARIFICATION] marker(s) in spec.md</p>`;
                }
            }
        }

        // Prerequisites
        if (step.prerequisites.length > 0) {
            body += `<div class="section"><h3>Prerequisites:</h3>`;
            for (const result of state.prerequisiteResults) {
                const icon = result.exists ? '\u2713' : '\u2717';
                const cls = result.exists ? 'check-pass' : 'check-fail';
                const detail = result.detail ? ` (${escapeHtml(result.detail)})` : '';
                const fileLink = result.exists && result.resolvedFile && !result.artifact.isDirectory
                    ? ` <a href="#" onclick="openFile('${escapeHtml(result.resolvedFile.replace(/\\/g, '\\\\').replace(/'/g, "\\'"))}')">[open]</a>`
                    : '';
                body += `<div class="check-item"><span class="${cls}">${icon}</span> ${escapeHtml(result.artifact.label)}${detail}${fileLink}</div>`;
            }
            body += `</div>`;
        }

        // Outputs
        if (step.outputs.length > 0) {
            body += `<div class="section"><h3>Outputs:</h3>`;
            for (const result of state.outputResults) {
                const icon = result.exists ? '\u2713' : '\u2717';
                const cls = result.exists ? 'check-pass' : 'check-fail';
                const detail = result.detail ? ` (${escapeHtml(result.detail)})` : '';
                const fileLink = result.exists && result.resolvedFile && !result.artifact.isDirectory
                    ? ` <a href="#" onclick="openFile('${escapeHtml(result.resolvedFile.replace(/\\/g, '\\\\').replace(/'/g, "\\'"))}')">[open]</a>`
                    : '';
                body += `<div class="check-item"><span class="${cls}">${icon}</span> ${escapeHtml(result.artifact.label)}${detail}${fileLink}</div>`;

                // Checklist directory files
                if (step.id === 'checklist' && result.artifact.isDirectory && result.exists && result.resolvedFile) {
                    try {
                        const files = fs.readdirSync(result.resolvedFile)
                            .filter(n => n.endsWith('.md'))
                            .sort();
                        for (const f of files) {
                            const fp = path.join(result.resolvedFile, f);
                            body += `<div class="check-item" style="padding-left: 20px;"><span class="check-pass">\u2713</span> <a href="#" onclick="openFile('${escapeHtml(fp.replace(/\\/g, '\\\\').replace(/'/g, "\\'"))}')">${escapeHtml(f)}</a></div>`;
                        }
                    } catch { /* ignore */ }
                }
            }
            body += `</div>`;
        }

        // Checklists (implement step)
        if (step.id === 'implement' && featureDir) {
            const checklists = pipelineService.parseChecklists(featureDir);
            if (checklists.length > 0) {
                const totalItems = checklists.reduce((s, c) => s + c.total, 0);
                const completedItems = checklists.reduce((s, c) => s + c.completed, 0);
                body += `<div class="section"><h3>Checklists: ${completedItems}/${totalItems} complete</h3>`;
                for (const cl of checklists) {
                    const checkIcon = cl.isComplete ? '\u2713' : '\u25CB';
                    const cls = cl.isComplete ? 'check-pass' : 'check-fail';
                    body += `<div class="check-item"><span class="${cls}">${checkIcon}</span> <a href="#" onclick="openFile('${escapeHtml(cl.filePath.replace(/\\/g, '\\\\').replace(/'/g, "\\'"))}')">${escapeHtml(cl.fileName)}</a> (${cl.completed}/${cl.total})</div>`;
                }
                body += `</div>`;
            }
        }

        // Task list
        if (['tasks', 'implement', 'taskstoissues'].includes(step.id) && featureDir) {
            const tasksFilePath = path.join(featureDir, 'tasks.md');
            const parsed = tasksParser.parse(tasksFilePath);
            if (parsed) {
                body += `<div class="section"><h3>Tasks: ${parsed.completedTasks}/${parsed.totalTasks} complete</h3>`;
                for (const phase of parsed.phases) {
                    const phaseCompleted = phase.tasks.filter(t => t.checked).length;
                    const mvpTag = phase.isMvp ? ' MVP' : '';
                    const priorityTag = phase.priority ? ` (${phase.priority})` : '';
                    body += `<div class="phase-header">Phase ${phase.number}: ${escapeHtml(phase.name)}${escapeHtml(priorityTag)}${escapeHtml(mvpTag)} (${phaseCompleted}/${phase.tasks.length})</div>`;
                    for (const task of phase.tasks) {
                        const checkIcon = task.checked ? '\u2611' : '\u2610';
                        const cls = task.checked ? 'check-pass' : '';
                        const tags = [
                            task.parallel ? '[P]' : '',
                            task.story ? `[${task.story}]` : '',
                        ].filter(Boolean).join(' ');
                        body += `<div class="task-item"><span class="${cls}">${checkIcon}</span><span class="task-id">${escapeHtml(task.id)}</span>`;
                        if (tags) { body += `<span class="task-tags">${escapeHtml(tags)}</span>`; }
                        body += `<span>${escapeHtml(task.description)}</span></div>`;
                    }
                }
                body += `</div>`;
            }
        }

        // Hands off to
        if (step.handsOffTo.length > 0) {
            body += `<p class="muted">Hands off to \u2192 ${escapeHtml(step.handsOffTo.join(', '))}</p>`;
        }

        // Arguments
        body += `<div class="section">`;
        body += `<h3>Arguments:</h3>`;
        body += `<textarea id="argsField" ${isReadOnly ? 'readonly' : ''} rows="3">${escapeHtml(savedArgs)}</textarea>`;
        body += `</div>`;

        // Run button (hidden for Specify)
        if (!isReadOnly) {
            body += `<button onclick="runStep()">Run ${escapeHtml(step.name)} \u25B7</button>`;
        }

        const script = `
            const vscode = acquireVsCodeApi();
            function runStep() {
                const args = document.getElementById('argsField').value.trim();
                vscode.postMessage({ command: 'runStep', stepId: '${step.id}', args });
                vscode.postMessage({ command: 'saveArgs', featureDir: '${escapeHtml(featureEntry.dirName)}', stepId: '${step.id}', args });
            }
            function openFile(filePath) {
                vscode.postMessage({ command: 'openFile', filePath });
            }
        `;

        this._view.webview.html = getWebviewHtml(this._view.webview, `Step: ${step.name}`, body, script);
    }

    showFeature(entry: FeatureEntry, states: Map<PipelineStepDef, PipelineStepState> | undefined): void {
        if (!this._view) { return; }

        let body = '';
        body += `<h1>${escapeHtml(entry.dirName)}</h1>`;
        body += `<p>${entry.completedSteps}/${entry.totalOutputSteps} output steps completed</p>`;

        if (states) {
            body += `<div class="section"><h3>Steps:</h3>`;
            for (const step of pipelineSteps) {
                const state = states.get(step);
                const status = state?.status ?? StepStatus.NOT_STARTED;
                const icon = statusIcon(status);
                const cls = status === StepStatus.COMPLETED ? 'check-pass' :
                    status === StepStatus.BLOCKED ? 'check-fail' : '';
                body += `<div class="check-item"><span class="${cls}">${icon}</span> ${escapeHtml(displayNumber(step))}. ${escapeHtml(step.name)} \u2014 ${escapeHtml(statusText(status))}</div>`;
            }
            body += `</div>`;
        }

        this._view.webview.html = getWebviewHtml(this._view.webview, entry.dirName, body);
    }

    showEmpty(): void {
        if (!this._view) { return; }
        this._view.webview.html = getWebviewHtml(this._view.webview, 'Step Detail', '<p class="muted">Select a pipeline step to view details.</p>');
    }
}

function displayNumber(step: PipelineStepDef): string {
    if (!step.parentId) { return step.number.toString(); }
    const parent = pipelineSteps.find(s => s.id === step.parentId);
    if (!parent) { return step.number.toString(); }
    const siblingIndex = pipelineSteps
        .filter(s => s.parentId === step.parentId)
        .indexOf(step) + 1;
    return `${parent.number}.${siblingIndex}`;
}

function statusIcon(status: StepStatus): string {
    switch (status) {
        case StepStatus.COMPLETED: return '\u2713';
        case StepStatus.READY: return '\u25CB';
        case StepStatus.IN_PROGRESS: return '\u25D0';
        case StepStatus.BLOCKED: return '\u2717';
        default: return '\u25CB';
    }
}

function statusText(status: StepStatus): string {
    switch (status) {
        case StepStatus.COMPLETED: return 'Completed';
        case StepStatus.READY: return 'Ready';
        case StepStatus.IN_PROGRESS: return 'In Progress';
        case StepStatus.BLOCKED: return 'Blocked';
        default: return 'Not Started';
    }
}
