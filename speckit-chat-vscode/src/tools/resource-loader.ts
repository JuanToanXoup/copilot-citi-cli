import * as fs from 'fs';
import * as path from 'path';

const BUNDLED_AGENTS = [
    'speckit.analyze.agent.md',
    'speckit.checklist.agent.md',
    'speckit.clarify.agent.md',
    'speckit.constitution.agent.md',
    'speckit.coverage.agent.md',
    'speckit.implement.agent.md',
    'speckit.plan.agent.md',
    'speckit.specify.agent.md',
    'speckit.tasks.agent.md',
    'speckit.taskstoissues.agent.md',
];

const BUNDLED_DISCOVERIES = [
    'constitution.discovery.md',
];

let extensionPath = '';

export function setExtensionPath(p: string): void {
    extensionPath = p;
}

/**
 * Read an agent definition file.
 * Checks .github/agents/ in the project first, then falls back to bundled.
 */
export function readAgent(basePath: string, agentFileName: string): string | undefined {
    const projectPath = path.join(basePath, '.github', 'agents', agentFileName);
    if (fs.existsSync(projectPath)) {
        try { return fs.readFileSync(projectPath, 'utf-8'); } catch { /* fall through */ }
    }
    return readBundledResource(path.join('speckit', 'agents', agentFileName));
}

/**
 * List all available agent file names.
 * Merges project .github/agents/ with bundled agents (project files take priority).
 */
export function listAgents(basePath: string): string[] {
    const agentsDir = path.join(basePath, '.github', 'agents');
    let projectAgents: string[] = [];
    if (fs.existsSync(agentsDir)) {
        try {
            projectAgents = fs.readdirSync(agentsDir)
                .filter(name => name.endsWith('.agent.md'));
        } catch { /* ignore */ }
    }
    const all = new Set([...projectAgents, ...BUNDLED_AGENTS]);
    return [...all].sort();
}

export function readDiscovery(basePath: string, fileName: string): string | undefined {
    const projectPath = path.join(basePath, '.github', 'discoveries', fileName);
    if (fs.existsSync(projectPath)) {
        try { return fs.readFileSync(projectPath, 'utf-8'); } catch { /* fall through */ }
    }
    return readBundledResource(path.join('speckit', 'discoveries', fileName));
}

export function listDiscoveries(basePath: string): string[] {
    const discDir = path.join(basePath, '.github', 'discoveries');
    let projectDiscoveries: string[] = [];
    if (fs.existsSync(discDir)) {
        try {
            projectDiscoveries = fs.readdirSync(discDir)
                .filter(name => name.endsWith('.discovery.md'));
        } catch { /* ignore */ }
    }
    const all = new Set([...projectDiscoveries, ...BUNDLED_DISCOVERIES]);
    return [...all].sort();
}

function readBundledResource(relativePath: string): string | undefined {
    const bundledPath = path.join(extensionPath, 'resources', relativePath);
    if (fs.existsSync(bundledPath)) {
        try { return fs.readFileSync(bundledPath, 'utf-8'); } catch { /* fall through */ }
    }
    return undefined;
}
