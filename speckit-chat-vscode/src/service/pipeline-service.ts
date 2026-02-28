import * as fs from 'fs';
import * as path from 'path';
import {
    ArtifactCheck,
    CheckResult,
    ChecklistStatus,
    FeatureEntry,
    PipelineStepDef,
    PipelineStepState,
    StepStatus,
} from '../model/pipeline-models';
import { pipelineSteps } from './pipeline-step-registry';

export function resolveArtifactFile(
    relativePath: string,
    isRepoRelative: boolean,
    basePath: string,
    featureDir: string | undefined,
): string | undefined {
    if (isRepoRelative) { return path.join(basePath, relativePath); }
    return featureDir ? path.join(featureDir, relativePath) : undefined;
}

export function checkArtifact(
    artifact: ArtifactCheck,
    basePath: string,
    featureDir: string | undefined,
): CheckResult {
    let filePath = resolveArtifactFile(
        artifact.relativePath, !!artifact.isRepoRelative, basePath, featureDir,
    );

    if (filePath && !fs.existsSync(filePath) && artifact.altRelativePath) {
        filePath = resolveArtifactFile(
            artifact.altRelativePath, !!artifact.isRepoRelative, basePath, featureDir,
        ) ?? filePath;
    }

    if (!filePath) { return { artifact, exists: false, detail: 'no feature dir' }; }

    if (artifact.isDirectory) {
        try {
            const stat = fs.statSync(filePath);
            if (!stat.isDirectory()) { return { artifact, exists: false, detail: 'missing' }; }
            const entries = fs.readdirSync(filePath);
            if (artifact.requireNonEmpty && entries.length === 0) {
                return { artifact, exists: false, detail: 'empty dir' };
            }
            return { artifact, exists: true, detail: `${entries.length} file(s)`, resolvedFile: filePath };
        } catch {
            return { artifact, exists: false, detail: 'missing' };
        }
    }

    try {
        const stat = fs.statSync(filePath);
        if (!stat.isFile()) { return { artifact, exists: false, detail: 'missing' }; }
        const size = stat.size;
        const detail = size < 1024 ? `${size} B` : `${(size / 1024).toFixed(1)} KB`;
        return { artifact, exists: true, detail, resolvedFile: filePath };
    } catch {
        return { artifact, exists: false, detail: 'missing' };
    }
}

export function scanFeatures(
    basePath: string,
    steps: PipelineStepDef[] = pipelineSteps,
): FeatureEntry[] {
    const specsDir = path.join(basePath, 'specs');
    if (!fs.existsSync(specsDir) || !fs.statSync(specsDir).isDirectory()) { return []; }

    const dirEntries = fs.readdirSync(specsDir, { withFileTypes: true });
    return dirEntries
        .filter(d => d.isDirectory() && /^\d{3}-/.test(d.name))
        .sort((a, b) => a.name.localeCompare(b.name))
        .map(d => {
            const entry: FeatureEntry = {
                dirName: d.name,
                path: path.join(specsDir, d.name),
                completedSteps: 0,
                totalOutputSteps: 0,
            };
            computeFeatureStatus(entry, basePath, steps);
            return entry;
        });
}

export function computeFeatureStatus(
    entry: FeatureEntry,
    basePath: string,
    steps: PipelineStepDef[] = pipelineSteps,
): void {
    const outputSteps = steps.filter(s => s.outputs.length > 0);
    let completed = 0;
    for (const step of outputSteps) {
        if (step.outputs.every(o => checkArtifact(o, basePath, entry.path).exists)) {
            completed++;
        }
    }
    entry.completedSteps = completed;
    entry.totalOutputSteps = outputSteps.length;
}

export function parseChecklists(featureDir: string): ChecklistStatus[] {
    const checklistsDir = path.join(featureDir, 'checklists');
    if (!fs.existsSync(checklistsDir) || !fs.statSync(checklistsDir).isDirectory()) { return []; }

    const completedPattern = /^- \[[xX]]/;
    const incompletePattern = /^- \[ ]/;

    return fs.readdirSync(checklistsDir)
        .filter(name => name.endsWith('.md'))
        .sort()
        .map(name => {
            const filePath = path.join(checklistsDir, name);
            const content = fs.readFileSync(filePath, 'utf-8');
            let completed = 0;
            let total = 0;
            for (const line of content.split('\n')) {
                const trimmed = line.trimStart();
                if (completedPattern.test(trimmed)) { completed++; total++; }
                else if (incompletePattern.test(trimmed)) { total++; }
            }
            return {
                fileName: name,
                total,
                completed,
                filePath,
                isComplete: completed >= total,
            };
        });
}

export function deriveStatus(
    step: PipelineStepDef,
    basePath: string,
    featureDir: string | undefined,
    state: PipelineStepState,
): StepStatus {
    const prereqResults = step.prerequisites.map(p => checkArtifact(p, basePath, featureDir));
    state.prerequisiteResults = prereqResults;

    const outputResults = step.outputs.map(o => checkArtifact(o, basePath, featureDir));
    state.outputResults = outputResults;

    const allPrereqsMet = prereqResults.every(r => r.exists);
    const allOutputsExist = outputResults.every(r => r.exists);
    const someOutputsExist = outputResults.some(r => r.exists);

    // Clarify: complete when spec.md has no [NEEDS CLARIFICATION] markers
    if (step.id === 'clarify' && allPrereqsMet) {
        const specFile = resolveArtifactFile('spec.md', false, basePath, featureDir);
        if (specFile && fs.existsSync(specFile)) {
            try {
                const specContent = fs.readFileSync(specFile, 'utf-8');
                const hasMarkers = specContent.includes('[NEEDS CLARIFICATION');
                return hasMarkers ? StepStatus.READY : StepStatus.COMPLETED;
            } catch { /* fall through */ }
        }
    }

    if (step.outputs.length === 0 && allPrereqsMet) { return StepStatus.READY; }
    if (allOutputsExist && step.outputs.length > 0) { return StepStatus.COMPLETED; }
    if (!allPrereqsMet) { return StepStatus.BLOCKED; }
    if (someOutputsExist) { return StepStatus.IN_PROGRESS; }
    if (allPrereqsMet) { return StepStatus.READY; }
    return StepStatus.NOT_STARTED;
}
