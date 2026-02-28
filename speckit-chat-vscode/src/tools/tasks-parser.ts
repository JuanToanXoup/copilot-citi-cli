import * as fs from 'fs';

export interface TaskItem {
    id: string;
    checked: boolean;
    parallel: boolean;
    story: string | undefined;
    description: string;
    rawLine: string;
    lineNumber: number;
}

export interface TaskPhase {
    number: number;
    name: string;
    priority: string | undefined;
    isMvp: boolean;
    goal: string | undefined;
    tasks: TaskItem[];
}

export interface TasksFile {
    featureName: string;
    phases: TaskPhase[];
    filePath: string;
    totalTasks: number;
    completedTasks: number;
}

const featureNamePattern = /^#\s+Tasks:\s+(.+)/;
const phasePattern = /^##\s+Phase\s+(\d+):\s+(.+)/;
const priorityPattern = /\(Priority:\s*(P\d)\)/;
const goalPattern = /^\*\*Goal\*\*:\s*(.+)/;
const taskPattern = /^-\s+\[([ xX])]\s+(T\d{3,4})\s*(\[P])?\s*(\[US\d+])?\s*(.+)$/;

export function parse(filePath: string): TasksFile | undefined {
    try {
        const stat = fs.statSync(filePath);
        if (!stat.isFile()) { return undefined; }
    } catch { return undefined; }
    const content = fs.readFileSync(filePath, 'utf-8');
    const lines = content.split('\n');
    if (lines.length === 0) { return undefined; }

    let featureName = '';
    const phases: TaskPhase[] = [];
    let currentPhaseNumber = 0;
    let currentPhaseName = '';
    let currentPriority: string | undefined;
    let currentIsMvp = false;
    let currentGoal: string | undefined;
    let currentTasks: TaskItem[] = [];

    function flushPhase(): void {
        if (currentPhaseNumber > 0) {
            phases.push({
                number: currentPhaseNumber,
                name: currentPhaseName,
                priority: currentPriority,
                isMvp: currentIsMvp,
                goal: currentGoal,
                tasks: [...currentTasks],
            });
        }
    }

    for (let i = 0; i < lines.length; i++) {
        const trimmed = lines[i].trimStart();

        const featureMatch = featureNamePattern.exec(trimmed);
        if (featureMatch) {
            featureName = featureMatch[1].trim();
            continue;
        }

        const phaseMatch = phasePattern.exec(trimmed);
        if (phaseMatch) {
            flushPhase();
            currentPhaseNumber = parseInt(phaseMatch[1], 10);
            const headerText = phaseMatch[2].trim();
            const pm = priorityPattern.exec(headerText);
            currentPriority = pm ? pm[1] : undefined;
            currentIsMvp = /MVP/i.test(headerText);
            currentPhaseName = headerText
                .replace(priorityPattern, '')
                .replace(/MVP/i, '')
                .replace(/\(\s*,?\s*\)/, '')
                .trim()
                .replace(/[, ]+$/, '');
            currentGoal = undefined;
            currentTasks = [];
            continue;
        }

        const goalMatch = goalPattern.exec(trimmed);
        if (goalMatch && currentPhaseNumber > 0) {
            currentGoal = goalMatch[1].trim();
            continue;
        }

        const taskMatch = taskPattern.exec(trimmed);
        if (taskMatch && currentPhaseNumber > 0) {
            const checked = taskMatch[1].toLowerCase() === 'x';
            const id = taskMatch[2];
            const parallel = !!taskMatch[3];
            let story: string | undefined = taskMatch[4] || undefined;
            if (story) { story = story.slice(1, -1); } // Remove surrounding [ ]
            const description = taskMatch[5].trim();
            currentTasks.push({
                id, checked, parallel, story, description,
                rawLine: lines[i], lineNumber: i + 1,
            });
        }
    }
    flushPhase();

    if (phases.length === 0) { return undefined; }

    const totalTasks = phases.reduce((sum, p) => sum + p.tasks.length, 0);
    const completedTasks = phases.reduce((sum, p) => sum + p.tasks.filter(t => t.checked).length, 0);

    return { featureName, phases, filePath, totalTasks, completedTasks };
}
