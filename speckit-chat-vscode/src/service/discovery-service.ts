import { TableRow } from '../model/discovery-models';

/**
 * Pure parsing and serialization logic for discovery.md files.
 */
export function extractBody(content: string): string {
    const match = content.match(/^---\s*\n[\s\S]*?\n---\s*\n?/);
    if (!match) { return content; }
    return content.substring(match[0].length);
}

export function parseDiscovery(content: string): TableRow[] {
    const rows: TableRow[] = [];
    let currentCategory = '';
    for (const line of content.split('\n')) {
        const trimmed = line.trim();
        if (!trimmed) { continue; }
        if (trimmed.startsWith('## ')) {
            currentCategory = trimmed.substring(3).trim();
            continue;
        }
        if (trimmed.startsWith('- ') && currentCategory) {
            const eqIdx = trimmed.indexOf('=');
            if (eqIdx > 0) {
                const attribute = trimmed.substring(2, eqIdx).trim();
                const answer = trimmed.substring(eqIdx + 1).trim();
                rows.push({ category: currentCategory, attribute, answer });
            }
        }
    }
    return rows;
}

export function serializeToMarkdown(
    categories: Array<{ category: string; attributes: Array<{ attribute: string; answer: string }> }>,
): string {
    const parts: string[] = [];
    for (const { category, attributes } of categories) {
        if (parts.length > 0) { parts.push(''); }
        parts.push(`## ${category}`);
        for (const { attribute, answer } of attributes) {
            parts.push(`- ${attribute} = ${answer}`);
        }
    }
    return parts.join('\n') + '\n';
}
