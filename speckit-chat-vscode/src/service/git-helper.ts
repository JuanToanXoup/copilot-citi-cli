import { execFileSync, execSync } from 'child_process';

export function currentBranch(basePath: string, fallback = 'main'): string {
    try {
        const output = execSync('git rev-parse --abbrev-ref HEAD', {
            cwd: basePath,
            encoding: 'utf-8',
            timeout: 10_000,
        }).trim();
        return output || fallback;
    } catch {
        return fallback;
    }
}

export function gitAdd(basePath: string, relativePath: string): void {
    try {
        execFileSync('git', ['add', relativePath], {
            cwd: basePath,
            encoding: 'utf-8',
            timeout: 5_000,
        });
    } catch {
        // Best-effort — don't block if git isn't available
    }
}
