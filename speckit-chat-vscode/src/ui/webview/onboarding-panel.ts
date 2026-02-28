import * as vscode from 'vscode';
import { getWebviewHtml, escapeHtml } from './webview-utils';

// ── Feature descriptors ─────────────────────────────────────────────────────

interface FeatureDescriptor {
    id: string;
    icon: string;
    title: string;
    beforeSteps: string;
    steps: string;
    afterSteps: string;
    slashCommand: string;
}

const gettingStarted: FeatureDescriptor[] = [
    {
        id: 'Discovery', icon: '\uD83D\uDD0D', title: 'Discover project properties',
        beforeSteps: 'Answer project property questions to establish context for all downstream agents:',
        steps: 'Select a discovery template and click <b>Load</b> to populate the categories\n' +
            'Select a category and click <b>Ask Copilot</b> to auto-fill answers from your codebase\n' +
            'Review and edit answers manually as needed\n' +
            'Click <b>Generate Constitution</b> to create the constitution from your answers',
        afterSteps: 'Discovery answers are saved to <code>.specify/memory/discovery.md</code> and feed into the Constitution agent.',
        slashCommand: 'discover',
    },
    {
        id: 'Constitution', icon: '\uD83C\uDFE0', title: 'Define project principles',
        beforeSteps: 'The constitution is the foundation of the Speckit pipeline:',
        steps: 'Select the <b>Speckit Constitution</b> agent\n' +
            'Provide engineering principles interactively\n' +
            'Speckit generates <code>constitution.md</code> with semver versioning',
        afterSteps: 'The constitution is referenced by Plan and Analyze to enforce alignment across all artifacts.',
        slashCommand: 'constitution',
    },
];

const specifyDesign: FeatureDescriptor[] = [
    {
        id: 'Specify', icon: '\u270F\uFE0F', title: 'Create feature specifications',
        beforeSteps: 'Describe any feature in natural language and Speckit generates a structured spec:',
        steps: 'Select the <b>Speckit Specify</b> agent\n' +
            'Enter a feature description in the prompt field\n' +
            'Speckit creates a git branch, feature directory, and <code>spec.md</code>',
        afterSteps: 'The generated spec becomes the source of truth for all downstream artifacts.',
        slashCommand: 'specify',
    },
    {
        id: 'Clarify', icon: '\u2753', title: 'Clarify underspecified requirements',
        beforeSteps: 'Speckit identifies gaps in your spec across 9 categories:',
        steps: 'Select the <b>Speckit Clarify</b> agent\n' +
            'Speckit scans the spec and scores each area\n' +
            'Answer up to 5 prioritized clarification questions\n' +
            'Answers are integrated back into the spec automatically',
        afterSteps: 'Optional \u2014 run before Plan to resolve ambiguities.',
        slashCommand: 'clarify',
    },
    {
        id: 'Plan', icon: '\uD83D\uDCC4', title: 'Generate implementation plans',
        beforeSteps: 'Create a technical design from your feature spec:',
        steps: 'Select the <b>Speckit Plan</b> agent\n' +
            'Speckit resolves unknowns into <code>research.md</code>\n' +
            'Produces <code>plan.md</code>, <code>data-model.md</code>, <code>contracts/</code>, and <code>quickstart.md</code>',
        afterSteps: 'Plan auto-hands off to Tasks and Checklist when complete.',
        slashCommand: 'plan',
    },
];

const tasksValidation: FeatureDescriptor[] = [
    {
        id: 'Tasks', icon: '\uD83D\uDCCB', title: 'Break plans into ordered tasks',
        beforeSteps: 'Generate dependency-ordered implementation tasks:',
        steps: 'Select the <b>Speckit Tasks</b> agent\n' +
            'Speckit maps entities and contracts to user stories\n' +
            'Generates <code>tasks.md</code> with phased, dependency-ordered task IDs',
        afterSteps: 'Tasks are grouped into phases with parallelizable tasks marked.',
        slashCommand: 'tasks',
    },
    {
        id: 'Checklist', icon: '\u2611\uFE0F', title: 'Generate requirement checklists',
        beforeSteps: 'Create domain-specific checklists that gate implementation:',
        steps: 'Select the <b>Speckit Checklist</b> agent\n' +
            'Speckit asks up to 5 clarifying questions, then generates checklist items\n' +
            'Each item traces back to a spec section',
        afterSteps: 'Incomplete checklists block the Implement agent.',
        slashCommand: 'checklist',
    },
    {
        id: 'Analyze', icon: '\uD83D\uDD0E', title: 'Analyze artifact consistency',
        beforeSteps: 'Read-only consistency check across all artifacts:',
        steps: 'Select the <b>Speckit Analyze</b> agent\n' +
            'Speckit runs 6 detection passes\n' +
            'Get a severity-ranked findings report',
        afterSteps: 'Analyze never writes files \u2014 it produces a report to stdout only.',
        slashCommand: 'analyze',
    },
];

const implementation: FeatureDescriptor[] = [
    {
        id: 'Implement', icon: '\u25B6\uFE0F', title: 'Execute implementation tasks',
        beforeSteps: 'Execute the full implementation plan with TDD and checklist gating:',
        steps: 'Select the <b>Speckit Implement</b> agent\n' +
            'Speckit checks all checklists \u2014 stops if any are incomplete\n' +
            'Tasks execute in order: tests before code',
        afterSteps: 'Completed tasks are marked [X] in tasks.md.',
        slashCommand: 'implement',
    },
    {
        id: 'Coverage', icon: '\uD83C\uDFC3', title: 'Drive test coverage to target',
        beforeSteps: 'Autonomous coverage orchestrator:',
        steps: 'Select the <b>Speckit Coverage</b> agent\n' +
            'Speckit discovers the project and measures baseline coverage\n' +
            'Drives the full speckit pipeline to reach target coverage',
        afterSteps: '',
        slashCommand: 'coverage',
    },
    {
        id: 'Issues', icon: '\uD83D\uDD00', title: 'Create GitHub issues from tasks',
        beforeSteps: 'Convert tasks into actionable issues:',
        steps: 'Select the <b>Speckit Issues</b> agent\n' +
            'Speckit reads tasks.md and creates one issue per task\n' +
            'Issues include task IDs, descriptions, labels',
        afterSteps: 'This is the terminal step in the pipeline.',
        slashCommand: 'taskstoissues',
    },
];

const allDescriptors = [...gettingStarted, ...specifyDesign, ...tasksValidation, ...implementation];

const welcomeDescriptors = [
    ...gettingStarted,
    specifyDesign[0], // Specify
    specifyDesign[2], // Plan
    tasksValidation[0], // Tasks
    implementation[0], // Implement
];

export class OnboardingViewProvider implements vscode.WebviewViewProvider {
    public static readonly viewType = 'speckit.onboarding';
    private _view?: vscode.WebviewView;

    resolveWebviewView(webviewView: vscode.WebviewView): void {
        this._view = webviewView;
        webviewView.webview.options = { enableScripts: true };

        webviewView.webview.onDidReceiveMessage(msg => {
            switch (msg.command) {
                case 'showWelcome': this.showWelcome(); break;
                case 'showDiscoverAll': this.showDiscoverAll(); break;
                case 'showFeature': this.showFeature(msg.index); break;
                case 'tryFeature': this.tryFeature(msg.slashCommand); break;
                case 'initSpeckit': vscode.commands.executeCommand('speckit.initSpeckit'); break;
                case 'openGithub': vscode.env.openExternal(vscode.Uri.parse('https://github.com/github/spec-kit')); break;
            }
        });

        this.showWelcome();
    }

    private showWelcome(): void {
        if (!this._view) { return; }

        let body = '';
        body += `<h1>Welcome to Speckit</h1>`;
        body += `<p>Here is how Speckit can help you:</p>`;

        // Setup callout
        body += `<div class="section">`;
        body += `<p>Download the latest Speckit agents into your project to get started.</p>`;
        body += `<button onclick="msg('initSpeckit')">Init Speckit</button> `;
        body += `<a href="#" onclick="msg('openGithub')">View on GitHub</a>`;
        body += `</div>`;

        // Feature buttons
        for (let i = 0; i < welcomeDescriptors.length; i++) {
            const desc = welcomeDescriptors[i];
            const globalIndex = allDescriptors.indexOf(desc);
            body += `<div style="padding: 6px 0; cursor: pointer; border-bottom: 1px solid var(--vscode-widget-border, #333);" onclick="msg('showFeature', ${globalIndex})">`;
            body += `<span>${desc.icon}</span> <b>${escapeHtml(desc.title)}</b>`;
            body += `</div>`;
        }

        body += `<div style="margin-top: 12px;"><a href="#" onclick="msg('showDiscoverAll')">Discover all features</a></div>`;

        const script = `
            const vscode = acquireVsCodeApi();
            function msg(command, index) {
                vscode.postMessage({ command, index });
            }
        `;

        this._view.webview.html = getWebviewHtml(this._view.webview, 'Onboarding', body, script);
    }

    private showDiscoverAll(): void {
        if (!this._view) { return; }

        let body = '';
        body += `<div style="display: flex; justify-content: space-between; align-items: center;">`;
        body += `<h2>Discover Features</h2>`;
        body += `<a href="#" onclick="msg('showWelcome')">Close</a>`;
        body += `</div>`;

        const categories: Array<{ name: string; icon: string; features: FeatureDescriptor[] }> = [
            { name: 'Getting started', icon: '\uD83C\uDFE0', features: gettingStarted },
            { name: 'Specify & design', icon: '\u270F\uFE0F', features: specifyDesign },
            { name: 'Tasks & validation', icon: '\uD83D\uDCCB', features: tasksValidation },
            { name: 'Implementation', icon: '\u25B6\uFE0F', features: implementation },
        ];

        for (const cat of categories) {
            body += `<h3>${cat.icon} ${escapeHtml(cat.name)}</h3>`;
            for (const desc of cat.features) {
                const globalIndex = allDescriptors.indexOf(desc);
                body += `<div style="padding: 4px 0 4px 8px; cursor: pointer;" onclick="msg('showFeature', ${globalIndex})">`;
                body += `${desc.icon} ${escapeHtml(desc.title)}`;
                body += `</div>`;
            }
        }

        const script = `
            const vscode = acquireVsCodeApi();
            function msg(command, index) {
                vscode.postMessage({ command, index });
            }
        `;

        this._view.webview.html = getWebviewHtml(this._view.webview, 'Discover Features', body, script);
    }

    private showFeature(index: number): void {
        if (!this._view || index < 0 || index >= allDescriptors.length) { return; }

        const desc = allDescriptors[index];
        let body = '';

        // Header
        body += `<div style="display: flex; justify-content: space-between; align-items: center;">`;
        body += `<a href="#" onclick="msg('showDiscoverAll')">Discover all features</a>`;
        body += `<a href="#" onclick="msg('showWelcome')">Close</a>`;
        body += `</div>`;
        body += `<hr/>`;

        // Title + description
        body += `<h1>${desc.icon} ${escapeHtml(desc.title)}</h1>`;
        if (desc.beforeSteps) {
            body += `<p>${desc.beforeSteps}</p>`;
        }
        if (desc.steps) {
            const items = desc.steps.split('\n').filter(s => s.trim());
            body += `<ol>`;
            for (const item of items) {
                body += `<li>${item}</li>`;
            }
            body += `</ol>`;
        }
        if (desc.afterSteps) {
            body += `<p class="muted">${desc.afterSteps}</p>`;
        }

        // Try button
        body += `<div style="margin-top: 12px;">`;
        body += `<button onclick="msg('tryFeature', '${desc.slashCommand}')">Try ${escapeHtml(desc.title)}</button>`;
        body += `</div>`;

        // Navigation
        body += `<div style="margin-top: 16px; display: flex; gap: 8px;">`;
        if (index > 0) {
            body += `<button onclick="msg('showFeature', ${index - 1})">Previous</button>`;
        }
        if (index < allDescriptors.length - 1) {
            const next = allDescriptors[index + 1];
            body += `<button onclick="msg('showFeature', ${index + 1})">Next: ${escapeHtml(next.title)}</button>`;
        }
        body += `</div>`;

        const script = `
            const vscode = acquireVsCodeApi();
            function msg(command, data) {
                if (command === 'tryFeature') {
                    vscode.postMessage({ command, slashCommand: data });
                } else {
                    vscode.postMessage({ command, index: data });
                }
            }
        `;

        this._view.webview.html = getWebviewHtml(this._view.webview, desc.title, body, script);
    }

    private tryFeature(slashCommand: string): void {
        vscode.commands.executeCommand('workbench.action.chat.open', {
            query: `@speckit /${slashCommand} `,
        });
    }
}
