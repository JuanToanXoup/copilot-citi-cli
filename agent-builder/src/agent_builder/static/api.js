/* Fetch / SSE wrappers for Agent Builder API */

const API = {
    async getTools() {
        const r = await fetch('/api/tools');
        return r.json();
    },

    async getModels() {
        const r = await fetch('/api/models');
        return r.json();
    },

    async getTemplates() {
        const r = await fetch('/api/templates');
        return r.json();
    },

    /* ── Config persistence ──────────────────────────────── */

    async listConfigs() {
        const r = await fetch('/api/configs');
        return r.json();
    },

    async getConfig(name) {
        const r = await fetch(`/api/configs/${encodeURIComponent(name)}`);
        return r.json();
    },

    async saveConfig(config) {
        const r = await fetch('/api/configs', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(config),
        });
        return r.json();
    },

    async deleteConfig(name) {
        const r = await fetch(`/api/configs/${encodeURIComponent(name)}`, {
            method: 'DELETE',
        });
        return r.json();
    },

    /* ── Live preview ────────────────────────────────────── */

    /**
     * Start a preview session via SSE stream.
     * Calls onEvent({ type, message|session_id }) for each event.
     * Resolves with { session_id } on success, rejects on error.
     */
    startPreview(config, onEvent) {
        const ctrl = new AbortController();
        const promise = new Promise((resolve, reject) => {
            fetch('/api/preview/start', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(config),
                signal: ctrl.signal,
            }).then(async (res) => {
                const reader = res.body.getReader();
                const decoder = new TextDecoder();
                let buffer = '';

                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split('\n');
                    buffer = lines.pop();
                    for (const line of lines) {
                        if (!line.startsWith('data: ')) continue;
                        let evt;
                        try { evt = JSON.parse(line.slice(6)); } catch (_) { continue; }
                        if (onEvent) onEvent(evt);
                        if (evt.type === 'done') {
                            resolve({ session_id: evt.session_id });
                            return;
                        }
                        if (evt.type === 'error') {
                            reject(new Error(evt.message || 'Start failed'));
                            return;
                        }
                    }
                }
                // Process remaining buffer
                if (buffer.startsWith('data: ')) {
                    try {
                        const evt = JSON.parse(buffer.slice(6));
                        if (onEvent) onEvent(evt);
                        if (evt.type === 'done') {
                            resolve({ session_id: evt.session_id });
                            return;
                        }
                        if (evt.type === 'error') {
                            reject(new Error(evt.message || 'Start failed'));
                            return;
                        }
                    } catch (_) {}
                }
                reject(new Error('Stream ended without done/error'));
            }).catch(reject);
        });
        return { promise, abort: () => ctrl.abort() };
    },

    async pingPreview(sessionId) {
        const r = await fetch('/api/preview/ping', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ session_id: sessionId }),
        });
        return r.json();
    },

    async stopPreview(sessionId) {
        const r = await fetch('/api/preview/stop', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ session_id: sessionId }),
        });
        return r.json();
    },

    /**
     * Stream chat via SSE (fetch-based for POST support).
     * Calls onEvent({ type, data }) for each event.
     * Returns a function to abort.
     */
    streamChat(sessionId, message, conversationId, onEvent) {
        const ctrl = new AbortController();
        const body = {
            session_id: sessionId,
            message: message,
        };
        if (conversationId) body.conversation_id = conversationId;

        fetch('/api/preview/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
            signal: ctrl.signal,
        }).then(async (res) => {
            const reader = res.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';
            let gotDoneOrError = false;

            while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                buffer += decoder.decode(value, { stream: true });
                const lines = buffer.split('\n');
                buffer = lines.pop();
                for (const line of lines) {
                    if (line.startsWith('data: ')) {
                        try {
                            const evt = JSON.parse(line.slice(6));
                            if (evt.type === 'done' || evt.type === 'error') gotDoneOrError = true;
                            onEvent(evt);
                        } catch (_) { /* skip malformed */ }
                    }
                }
            }
            // process remaining
            if (buffer.startsWith('data: ')) {
                try {
                    const evt = JSON.parse(buffer.slice(6));
                    if (evt.type === 'done' || evt.type === 'error') gotDoneOrError = true;
                    onEvent(evt);
                } catch (_) {}
            }
            // Stream ended without a done/error event — notify the UI
            if (!gotDoneOrError) {
                onEvent({ type: 'error', data: 'Connection lost — no response received. Check your network/proxy settings.' });
            }
        }).catch((err) => {
            if (err.name !== 'AbortError') {
                onEvent({ type: 'error', data: err.message });
            }
        });

        return () => ctrl.abort();
    },

    /* ── Build ───────────────────────────────────────────── */

    streamBuild(config, onEvent) {
        const ctrl = new AbortController();

        fetch('/api/build', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(config),
            signal: ctrl.signal,
        }).then(async (res) => {
            const reader = res.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';

            while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                buffer += decoder.decode(value, { stream: true });
                const lines = buffer.split('\n');
                buffer = lines.pop();
                for (const line of lines) {
                    if (line.startsWith('data: ')) {
                        try {
                            onEvent(JSON.parse(line.slice(6)));
                        } catch (_) {}
                    }
                }
            }
        }).catch((err) => {
            if (err.name !== 'AbortError') {
                onEvent({ type: 'error', message: err.message });
            }
        });

        return () => ctrl.abort();
    },

    async exportScript(config) {
        const r = await fetch('/api/export-script', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(config),
        });
        return r.json();
    },
};
