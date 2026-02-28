import { PipelineStepDef } from '../model/pipeline-models';

export const pipelineSteps: PipelineStepDef[] = [
    {
        number: 1, id: 'constitution', name: 'Constitution',
        description: 'Create or update project governance principles.',
        isOptional: false,
        prerequisites: [
            { relativePath: '.specify/templates/constitution-template.md', label: 'constitution-template.md', isRepoRelative: true },
        ],
        outputs: [
            { relativePath: '.specify/memory/constitution.md', label: 'constitution.md', isRepoRelative: true },
        ],
        handsOffTo: ['specify'],
        agentFileName: 'speckit.constitution.agent.md',
        defaultArgs: 'Refer to the `./specify/memory/discovery.md` for project properties',
    },
    {
        number: 2, id: 'specify', name: 'Specify',
        description: 'Feature spec from natural language description.',
        isOptional: false,
        prerequisites: [
            { relativePath: '.specify/scripts/bash/create-new-feature.sh', label: 'create-new-feature script', isRepoRelative: true, altRelativePath: '.specify/scripts/powershell/create-new-feature.ps1' },
            { relativePath: '.specify/templates/spec-template.md', label: 'spec-template.md', isRepoRelative: true },
        ],
        outputs: [
            { relativePath: 'spec.md', label: 'spec.md' },
            { relativePath: 'checklists/requirements.md', label: 'checklists/requirements.md' },
        ],
        handsOffTo: ['clarify', 'plan'],
        agentFileName: 'speckit.specify.agent.md',
        defaultArgs: '100% Unit Test Case coverage for all microservices code.',
    },
    {
        number: 3, id: 'clarify', name: 'Clarify',
        description: 'Identify and resolve underspecified areas in the spec.',
        isOptional: true,
        prerequisites: [
            { relativePath: 'spec.md', label: 'spec.md' },
        ],
        outputs: [],
        handsOffTo: ['plan'],
        agentFileName: 'speckit.clarify.agent.md',
        parentId: 'specify',
        defaultArgs: '',
    },
    {
        number: 4, id: 'plan', name: 'Plan',
        description: 'Generate the technical design \u2014 data models, contracts, research.',
        isOptional: false,
        prerequisites: [
            { relativePath: 'spec.md', label: 'spec.md' },
            { relativePath: '.specify/memory/constitution.md', label: 'constitution.md', isRepoRelative: true },
            { relativePath: '.specify/scripts/bash/setup-plan.sh', label: 'setup-plan script', isRepoRelative: true, altRelativePath: '.specify/scripts/powershell/setup-plan.ps1' },
            { relativePath: '.specify/templates/plan-template.md', label: 'plan-template.md', isRepoRelative: true },
        ],
        outputs: [
            { relativePath: 'plan.md', label: 'plan.md' },
            { relativePath: 'research.md', label: 'research.md' },
            { relativePath: 'data-model.md', label: 'data-model.md' },
            { relativePath: 'contracts', label: 'contracts/', isDirectory: true },
            { relativePath: 'quickstart.md', label: 'quickstart.md' },
        ],
        handsOffTo: ['tasks', 'checklist'],
        agentFileName: 'speckit.plan.agent.md',
        defaultArgs: '',
    },
    {
        number: 5, id: 'tasks', name: 'Tasks',
        description: 'Generate an actionable, dependency-ordered task list.',
        isOptional: false,
        prerequisites: [
            { relativePath: 'plan.md', label: 'plan.md' },
            { relativePath: 'spec.md', label: 'spec.md' },
            { relativePath: '.specify/templates/tasks-template.md', label: 'tasks-template.md', isRepoRelative: true },
        ],
        outputs: [
            { relativePath: 'tasks.md', label: 'tasks.md' },
        ],
        handsOffTo: ['analyze', 'implement'],
        agentFileName: 'speckit.tasks.agent.md',
        defaultArgs: '',
    },
    {
        number: 6, id: 'checklist', name: 'Checklist',
        description: 'Validate requirement quality \u2014 completeness, clarity, consistency.',
        isOptional: true,
        prerequisites: [
            { relativePath: 'spec.md', label: 'spec.md' },
        ],
        outputs: [
            { relativePath: 'checklists', label: 'checklists/', isDirectory: true, requireNonEmpty: true },
        ],
        handsOffTo: [],
        agentFileName: 'speckit.checklist.agent.md',
        defaultArgs: '',
    },
    {
        number: 7, id: 'analyze', name: 'Analyze',
        description: 'Non-destructive cross-artifact consistency analysis (read-only).',
        isOptional: false,
        prerequisites: [
            { relativePath: 'tasks.md', label: 'tasks.md' },
            { relativePath: 'spec.md', label: 'spec.md' },
            { relativePath: 'plan.md', label: 'plan.md' },
            { relativePath: '.specify/memory/constitution.md', label: 'constitution.md', isRepoRelative: true },
        ],
        outputs: [],
        handsOffTo: [],
        agentFileName: 'speckit.analyze.agent.md',
        defaultArgs: '',
    },
    {
        number: 8, id: 'implement', name: 'Implement',
        description: 'Execute the implementation plan \u2014 TDD, checklist gating, progress tracking.',
        isOptional: false,
        prerequisites: [
            { relativePath: 'tasks.md', label: 'tasks.md' },
            { relativePath: 'plan.md', label: 'plan.md' },
        ],
        outputs: [],
        handsOffTo: ['taskstoissues'],
        agentFileName: 'speckit.implement.agent.md',
        defaultArgs: 'all remaining tasks',
    },
    {
        number: 9, id: 'taskstoissues', name: 'Tasks \u2192 Issues',
        description: 'Convert tasks into issues (GitHub, Bitbucket, or GitLab).',
        isOptional: false,
        prerequisites: [
            { relativePath: 'tasks.md', label: 'tasks.md' },
        ],
        outputs: [],
        handsOffTo: [],
        agentFileName: 'speckit.taskstoissues.agent.md',
        defaultArgs: 'all tasks',
    },
];
