export enum StepStatus {
    NOT_STARTED = 'NOT_STARTED',
    READY = 'READY',
    IN_PROGRESS = 'IN_PROGRESS',
    COMPLETED = 'COMPLETED',
    BLOCKED = 'BLOCKED',
}

export interface ArtifactCheck {
    relativePath: string;
    label: string;
    isDirectory?: boolean;
    requireNonEmpty?: boolean;
    isRepoRelative?: boolean;
    altRelativePath?: string;
}

export interface CheckResult {
    artifact: ArtifactCheck;
    exists: boolean;
    detail: string;
    resolvedFile?: string;
}

export interface ChecklistStatus {
    fileName: string;
    total: number;
    completed: number;
    filePath: string;
    isComplete: boolean;
}

export interface PipelineStepDef {
    number: number;
    id: string;
    name: string;
    description: string;
    isOptional: boolean;
    prerequisites: ArtifactCheck[];
    outputs: ArtifactCheck[];
    handsOffTo: string[];
    agentFileName: string;
    parentId?: string;
    defaultArgs: string;
}

export class PipelineStepState {
    status: StepStatus = StepStatus.NOT_STARTED;
    prerequisiteResults: CheckResult[] = [];
    outputResults: CheckResult[] = [];
}

export interface FeaturePaths {
    basePath: string;
    branch: string;
    isFeatureBranch: boolean;
    featureDir: string | undefined;
}

export interface FeatureEntry {
    dirName: string;
    path: string;
    completedSteps: number;
    totalOutputSteps: number;
}
