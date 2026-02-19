/* Main state management + event wiring for Agent Builder */

const KNOWN_MCP_SERVERS = {
    playwright: { command: "npx", args: ["-y", "@playwright/mcp@latest"], env: {}, description: "Playwright (browser automation)" },
    mermaid:    { command: "npx", args: ["-y", "@peng-shawn/mermaid-mcp-server"], env: {}, description: "Mermaid (diagram generation)" },
    qdrant:     { command: "npx", args: ["-y", "@qdrant/mcp-server-qdrant"], env: {}, description: "Qdrant (vector search)" },
    context7:   { command: "npx", args: ["-y", "@upstash/context7-mcp"], env: {}, description: "Context7 (up-to-date library docs)" },
    n8n:        { command: "npx", args: ["-y", "n8n-mcp"], env: {}, description: "n8n (workflow automation)" },
};

const DEFAULT_MCP = ["playwright"];

const KNOWN_LSP_SERVERS = {
    python:   { command: "pyright-langserver", args: ["--stdio"], description: "Python (Pyright)" },
    java:     { command: "jdtls", args: [], description: "Java (Eclipse JDT)" },
    kotlin:   { command: "kotlin-language-server", args: [], description: "Kotlin" },
    gherkin:  { command: "npx", args: ["-y", "@cucumber/language-server", "--stdio"], description: "Gherkin / Cucumber" },
    typescript: { command: "typescript-language-server", args: ["--stdio"], description: "TypeScript" },
    go:       { command: "gopls", args: ["serve"], description: "Go (gopls)" },
    rust:     { command: "rust-analyzer", args: [], description: "Rust (rust-analyzer)" },
};

const DEFAULT_LSP = ["python", "java", "kotlin", "gherkin"];

const App = window.App = {
    state: {
        config: {
            name: '',
            description: '',
            system_prompt: '',
            model: 'gpt-4.1',
            agent_mode: true,
            workspace_root: '',
            tools: { enabled: '__ALL__', disabled: [] },
            mcp_servers: {
                "playwright": { command: "npx", args: ["-y", "@playwright/mcp@latest"], env: {} },
            },
            lsp_servers: {},
            proxy: { url: '', no_ssl_verify: false },
            transport: 'mcp',
            workers: [],
        },
        session: { id: null, conversationId: null },
        templates: [],
        tools: {},       // name → schema (with _builtin flag)
        models: [],
        chatAbort: null,
        startAbort: null,
        sessionStarting: false,
    },

    /* ── Initialization ──────────────────────────────────── */

    async init() {
        // Load data in parallel
        const [tools, models, templates] = await Promise.all([
            API.getTools(),
            API.getModels(),
            API.getTemplates(),
        ]);

        this.state.tools = tools;
        this.state.models = models;
        this.state.templates = templates;

        // Set default LSP servers
        for (const lang of DEFAULT_LSP) {
            if (!this.state.config.lsp_servers[lang]) {
                const srv = KNOWN_LSP_SERVERS[lang];
                this.state.config.lsp_servers[lang] = { command: srv.command, args: [...srv.args] };
            }
        }

        this._populateModels();
        this._populateTemplates();
        this._renderTools();
        this._renderServers();
        this._renderWorkers();
        this._bindEvents();
        this._bindTabs();
        this._bindResize();
        this._updateToolCount();
        this._updateServerCounts();
        this._updateSessionUI();
    },

    /* ── Session UI ───────────────────────────────────────── */

    _updateSessionUI() {
        const hasSession = !!this.state.session.id;
        const isStarting = this.state.sessionStarting;
        const chatInput = document.getElementById('chat-input');
        const btnSend = document.getElementById('btn-send');
        const btnEnd = document.getElementById('btn-end-session');

        // Chat input: enabled only when session is active
        chatInput.disabled = !hasSession;
        btnSend.disabled = !hasSession;
        chatInput.placeholder = hasSession
            ? 'Type a message...'
            : 'Start a session to begin chatting...';

        // End Session button visibility (show during startup too)
        btnEnd.style.display = (hasSession || isStarting) ? '' : 'none';

        // Sidebar config lock: read-only text fields, disabled controls
        document.querySelectorAll('.sidebar-lockable').forEach(el => {
            el.classList.toggle('sidebar-locked', hasSession);
            el.querySelectorAll('input[type="text"], textarea').forEach(f => {
                f.readOnly = hasSession;
            });
            el.querySelectorAll('select, input[type="checkbox"], button').forEach(f => {
                if (!f.classList.contains('sidebar-tab')) f.disabled = hasSession;
            });
        });
    },

    async stopSession() {
        // Abort startup SSE stream if still connecting
        if (this.state.startAbort) {
            this.state.startAbort();
            this.state.startAbort = null;
        }
        this.state.sessionStarting = false;

        if (this.state.chatAbort) {
            this.state.chatAbort();
            this.state.chatAbort = null;
        }
        if (this.state.session.id) {
            await API.stopPreview(this.state.session.id).catch(() => {});
        }
        this.state.session.id = null;
        this.state.session.conversationId = null;
        document.getElementById('chat-messages').innerHTML = '';
        document.getElementById('session-status').textContent = 'No session';
        this._updateSessionUI();
    },

    /* ── Data population ─────────────────────────────────── */

    _populateModels() {
        const sel = document.getElementById('agent-model');
        sel.innerHTML = '';
        for (const m of this.state.models) {
            const opt = document.createElement('option');
            opt.value = m.id || m;
            opt.textContent = m.name || m.id || m;
            sel.appendChild(opt);
        }
        sel.value = this.state.config.model;
    },

    _populateTemplates() {
        const sel = document.getElementById('agent-template');
        sel.innerHTML = '<option value="">— None —</option>';
        for (const t of this.state.templates) {
            const opt = document.createElement('option');
            opt.value = t.id;
            opt.textContent = t.name;
            sel.appendChild(opt);
        }
    },

    _renderTools() {
        const enabled = this.state.config.tools.enabled;
        const set = enabled === '__ALL__' ? '__ALL__' : new Set(enabled);
        const filter = (document.getElementById('tool-search').value || '').toLowerCase();
        Components.renderToolList(
            this.state.tools, set, filter,
            document.getElementById('tool-list')
        );
    },

    _renderServers() {
        Components.renderMcpCheckboxes(
            KNOWN_MCP_SERVERS,
            this.state.config.mcp_servers,
            document.getElementById('mcp-server-list')
        );
        Components.renderLspCheckboxes(
            KNOWN_LSP_SERVERS,
            this.state.config.lsp_servers,
            document.getElementById('lsp-server-list')
        );
        this._updateServerCounts();
    },

    toggleMcp(name, checked) {
        if (checked) {
            const known = KNOWN_MCP_SERVERS[name];
            if (known) {
                this.state.config.mcp_servers[name] = { command: known.command, args: [...known.args], env: {} };
            }
        } else {
            delete this.state.config.mcp_servers[name];
        }
        this._updateServerCounts();
    },

    toggleLsp(lang, checked) {
        if (checked) {
            const known = KNOWN_LSP_SERVERS[lang];
            if (known) {
                this.state.config.lsp_servers[lang] = { command: known.command, args: [...known.args] };
            }
        } else {
            delete this.state.config.lsp_servers[lang];
        }
        this._updateServerCounts();
    },

    _updateToolCount() {
        const total = Object.keys(this.state.tools).length;
        const enabled = this.state.config.tools.enabled;
        const count = enabled === '__ALL__' ? total : enabled.length;
        document.getElementById('tool-count').textContent = `${count} / ${total}`;
    },

    _updateServerCounts() {
        const mcpCount = Object.keys(this.state.config.mcp_servers).length;
        const lspCount = Object.keys(this.state.config.lsp_servers).length;
        const workerCount = (this.state.config.workers || []).length;
        document.getElementById('mcp-count').textContent = String(mcpCount);
        document.getElementById('lsp-count').textContent = String(lspCount);
        document.getElementById('worker-count').textContent = String(workerCount);
    },

    _bindTabs() {
        document.querySelectorAll('.sidebar-tab[data-tab]').forEach(tab => {
            tab.addEventListener('click', () => {
                const target = tab.dataset.tab;
                document.querySelectorAll('.sidebar-tab').forEach(t => t.classList.remove('active'));
                document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
                tab.classList.add('active');
                document.querySelector(`.tab-panel[data-panel="${target}"]`).classList.add('active');
            });
        });
    },

    _bindResize() {
        const handle = document.getElementById('resize-handle');
        const sidebar = document.querySelector('.sidebar');
        let dragging = false;

        handle.addEventListener('mousedown', (e) => {
            e.preventDefault();
            dragging = true;
            handle.classList.add('dragging');
            document.body.style.cursor = 'col-resize';
            document.body.style.userSelect = 'none';
        });

        document.addEventListener('mousemove', (e) => {
            if (!dragging) return;
            const newWidth = e.clientX;
            const min = 240;
            const max = window.innerWidth * 0.6;
            sidebar.style.width = Math.max(min, Math.min(max, newWidth)) + 'px';
        });

        document.addEventListener('mouseup', () => {
            if (!dragging) return;
            dragging = false;
            handle.classList.remove('dragging');
            document.body.style.cursor = '';
            document.body.style.userSelect = '';
        });
    },

    /* ── Event binding ───────────────────────────────────── */

    _bindEvents() {
        // Sidebar fields → config sync
        const nameEl = document.getElementById('agent-name');
        const descEl = document.getElementById('agent-desc');
        const modelEl = document.getElementById('agent-model');
        const wsEl = document.getElementById('agent-workspace');
        const promptEl = document.getElementById('system-prompt');
        const proxyUrlEl = document.getElementById('proxy-url');
        const proxySslEl = document.getElementById('proxy-no-ssl');

        nameEl.addEventListener('input', () => { this.state.config.name = nameEl.value; });
        descEl.addEventListener('input', () => { this.state.config.description = descEl.value; });
        modelEl.addEventListener('change', () => { this.state.config.model = modelEl.value; });
        wsEl.addEventListener('input', () => { this.state.config.workspace_root = wsEl.value; });
        proxyUrlEl.addEventListener('input', () => { this.state.config.proxy.url = proxyUrlEl.value; });
        proxySslEl.addEventListener('change', () => { this.state.config.proxy.no_ssl_verify = proxySslEl.checked; });
        promptEl.addEventListener('input', () => { this.state.config.system_prompt = promptEl.value; });

        // Template
        document.getElementById('agent-template').addEventListener('change', (e) => {
            if (e.target.value) this.applyTemplate(e.target.value);
        });

        // Tool search
        document.getElementById('tool-search').addEventListener('input', () => {
            this._renderTools();
        });

        // Select/clear all
        document.getElementById('btn-select-all').addEventListener('click', () => {
            this.state.config.tools.enabled = '__ALL__';
            this._renderTools();
            this._updateToolCount();
        });
        document.getElementById('btn-clear-all').addEventListener('click', () => {
            this.state.config.tools.enabled = [];
            this._renderTools();
            this._updateToolCount();
        });

        // MCP transport toggle
        document.getElementById('mcp-transport').addEventListener('change', (e) => {
            document.getElementById('mcp-stdio-fields').style.display = e.target.value === 'stdio' ? '' : 'none';
            document.getElementById('mcp-sse-fields').style.display = e.target.value === 'sse' ? '' : 'none';
        });
        // MCP add
        document.getElementById('btn-add-mcp').addEventListener('click', () => this._addMcpServer());
        // LSP add
        document.getElementById('btn-add-lsp').addEventListener('click', () => this._addLspServer());
        // Worker add
        document.getElementById('btn-add-worker').addEventListener('click', () => this.addWorker());

        // Save / Load / Build
        document.getElementById('btn-save').addEventListener('click', () => this.saveConfig());
        document.getElementById('btn-load').addEventListener('click', () => this.showLoadDialog());
        document.getElementById('btn-build').addEventListener('click', () => this.buildAgent());
        document.getElementById('btn-export-script').addEventListener('click', () => this.exportScript());

        // Load modal cancel
        document.getElementById('btn-load-cancel').addEventListener('click', () => {
            document.getElementById('load-modal').classList.remove('active');
        });

        // Build close
        document.getElementById('btn-build-close').addEventListener('click', () => {
            document.getElementById('build-overlay').classList.remove('active');
        });

        // Chat
        document.getElementById('btn-new-session').addEventListener('click', () => this.startPreview());
        document.getElementById('btn-end-session').addEventListener('click', () => this.stopSession());
        document.getElementById('btn-send').addEventListener('click', () => this.sendMessage());
        document.getElementById('chat-input').addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                this.sendMessage();
            }
        });
    },

    /* ── Template application ────────────────────────────── */

    async applyTemplate(templateId) {
        const tpl = this.state.templates.find(t => t.id === templateId);
        if (!tpl) return;

        const r = await fetch(`/api/templates/${encodeURIComponent(templateId)}`);
        const full = await r.json();

        // Merge template into config (preserve name/workspace)
        const prevName = this.state.config.name;
        const prevWs = this.state.config.workspace_root;
        Object.assign(this.state.config, full);
        if (prevName) this.state.config.name = prevName;
        if (prevWs) this.state.config.workspace_root = prevWs;

        this._syncConfigToUI();
    },

    _syncConfigToUI() {
        const c = this.state.config;
        // Ensure workers/transport defaults
        if (!c.workers) c.workers = [];
        if (!c.transport) c.transport = 'mcp';
        if (!c.mcp_servers) c.mcp_servers = {};
        if (!c.lsp_servers) c.lsp_servers = {};

        document.getElementById('agent-name').value = c.name || '';
        document.getElementById('agent-desc').value = c.description || '';
        document.getElementById('agent-model').value = c.model || 'gpt-4.1';
        document.getElementById('agent-workspace').value = c.workspace_root || '';
        document.getElementById('system-prompt').value = c.system_prompt || '';
        const proxy = c.proxy || {};
        document.getElementById('proxy-url').value = proxy.url || '';
        document.getElementById('proxy-no-ssl').checked = !!proxy.no_ssl_verify;
        this._renderTools();
        this._renderServers();
        this._renderWorkers();
        this._updateToolCount();
        this._updateServerCounts();
    },

    /* ── Tool toggling ───────────────────────────────────── */

    toggleTool(name, checked) {
        let enabled = this.state.config.tools.enabled;
        if (enabled === '__ALL__') {
            // Convert to explicit list minus this one
            enabled = Object.keys(this.state.tools).filter(n => n !== name);
            if (checked) enabled.push(name);
        } else {
            enabled = enabled.filter(n => n !== name);
            if (checked) enabled.push(name);
        }
        this.state.config.tools.enabled = enabled;
        this._updateToolCount();
    },

    /* ── Server management ───────────────────────────────── */

    _addMcpServer() {
        const name = document.getElementById('mcp-name').value.trim();
        const transport = document.getElementById('mcp-transport').value;
        if (!name) return;

        if (transport === 'sse') {
            const url = document.getElementById('mcp-url').value.trim();
            if (!url) return;
            this.state.config.mcp_servers[name] = { url };
            document.getElementById('mcp-url').value = '';
        } else {
            const cmd = document.getElementById('mcp-command').value.trim();
            const argsStr = document.getElementById('mcp-args').value.trim();
            if (!cmd) return;
            const args = argsStr ? argsStr.split(',').map(a => a.trim()).filter(Boolean) : [];
            this.state.config.mcp_servers[name] = { command: cmd, args, env: {} };
            document.getElementById('mcp-command').value = '';
            document.getElementById('mcp-args').value = '';
        }

        document.getElementById('mcp-name').value = '';
        document.getElementById('mcp-transport').value = 'stdio';
        document.getElementById('mcp-stdio-fields').style.display = '';
        document.getElementById('mcp-sse-fields').style.display = 'none';
        this._renderServers();
    },

    removeMcpServer(name) {
        delete this.state.config.mcp_servers[name];
        this._renderServers();
    },

    _addLspServer() {
        const lang = document.getElementById('lsp-lang').value.trim();
        const cmd = document.getElementById('lsp-command').value.trim();
        const argsStr = document.getElementById('lsp-args').value.trim();
        if (!lang || !cmd) return;

        const args = argsStr ? argsStr.split(',').map(a => a.trim()).filter(Boolean) : [];
        this.state.config.lsp_servers[lang] = { command: cmd, args };

        document.getElementById('lsp-lang').value = '';
        document.getElementById('lsp-command').value = '';
        document.getElementById('lsp-args').value = '';
        this._renderServers();
    },

    removeLspServer(lang) {
        delete this.state.config.lsp_servers[lang];
        this._renderServers();
    },

    /* ── Worker management ──────────────────────────────── */

    _renderWorkers() {
        const container = document.getElementById('worker-list');
        if (!container) return;
        Components.renderWorkerList(
            this.state.config.workers || [],
            container,
            this.state.models,
        );
        this._updateServerCounts();
    },

    addWorker() {
        if (!this.state.config.workers) this.state.config.workers = [];
        this.state.config.workers.push({
            role: '',
            system_prompt: '',
            agent_mode: true,
            tools_enabled: '__ALL__',
        });
        this._renderWorkers();
    },

    updateWorker(idx, field, value) {
        const w = this.state.config.workers[idx];
        if (!w) return;
        w[field] = value;
    },

    removeWorker(idx) {
        this.state.config.workers.splice(idx, 1);
        this._renderWorkers();
    },

    /* ── Config persistence ──────────────────────────────── */

    async saveConfig() {
        const name = this.state.config.name.trim();
        if (!name) {
            alert('Please enter an agent name first.');
            return;
        }
        const result = await API.saveConfig(this.state.config);
        if (result.ok) {
            document.getElementById('btn-save').textContent = 'Saved!';
            setTimeout(() => {
                document.getElementById('btn-save').textContent = 'Save';
            }, 1500);
        }
    },

    async showLoadDialog() {
        const configs = await API.listConfigs();
        const container = document.getElementById('config-list');
        Components.renderConfigList(
            configs,
            container,
            async (name) => {
                const config = await API.getConfig(name);
                this.state.config = config;
                this._syncConfigToUI();
                document.getElementById('load-modal').classList.remove('active');
            },
            async (name) => {
                if (confirm(`Delete "${name}"?`)) {
                    await API.deleteConfig(name);
                    this.showLoadDialog(); // refresh
                }
            }
        );
        document.getElementById('load-modal').classList.add('active');
    },

    /* ── Live preview ────────────────────────────────────── */

    async startPreview() {
        if (this.state.sessionStarting) return;
        this.state.sessionStarting = true;

        // Abort any in-flight streaming request
        if (this.state.chatAbort) {
            this.state.chatAbort();
            this.state.chatAbort = null;
        }

        // Stop existing session
        if (this.state.session.id) {
            await API.stopPreview(this.state.session.id).catch(() => {});
            this.state.session.id = null;
            this.state.session.conversationId = null;
        }

        // Lock sidebar immediately
        document.querySelectorAll('.sidebar-lockable').forEach(el => {
            el.classList.add('sidebar-locked');
            el.querySelectorAll('input[type="text"], textarea').forEach(f => {
                f.readOnly = true;
            });
            el.querySelectorAll('select, input[type="checkbox"], button').forEach(f => {
                if (!f.classList.contains('sidebar-tab')) f.disabled = true;
            });
        });

        // Reset UI
        const msgs = document.getElementById('chat-messages');
        msgs.innerHTML = '';
        document.getElementById('session-status').textContent = 'Starting...';
        this._updateSessionUI();

        const { promise, abort } = API.startPreview(this.state.config, (evt) => {
            if (evt.type === 'progress') {
                document.getElementById('session-status').textContent = evt.message;
                Components.renderChatMessage(
                    { type: 'status', text: evt.message }, msgs
                );
            }
        });
        this.state.startAbort = abort;

        try {
            const result = await promise;

            // Remove status messages once connected
            msgs.querySelectorAll('.msg-status').forEach(el => el.remove());

            this.state.session.id = result.session_id;
            this.state.session.conversationId = null;
            document.getElementById('session-status').textContent = 'Connected';
            this._updateSessionUI();
            document.getElementById('chat-input').focus();
        } catch (e) {
            // Ignore AbortError — user cancelled via stopSession
            if (e.name === 'AbortError') return;
            document.getElementById('session-status').textContent = 'Error';
            this._updateSessionUI();
            Components.renderChatMessage(
                { type: 'error', text: e.message }, msgs
            );
        } finally {
            this.state.startAbort = null;
            this.state.sessionStarting = false;
        }
    },

    async sendMessage() {
        const input = document.getElementById('chat-input');
        const text = input.value.trim();
        if (!text || !this.state.session.id) return;

        input.value = '';
        const msgs = document.getElementById('chat-messages');

        // Render user message
        Components.renderChatMessage({ type: 'user', text }, msgs);

        // Show spinner
        Components.renderChatMessage({ type: 'spinner' }, msgs);

        // Disable input while streaming
        input.disabled = true;
        document.getElementById('btn-send').disabled = true;

        let assistantDiv = null;
        let replyText = '';

        this.state.chatAbort = API.streamChat(
            this.state.session.id,
            text,
            this.state.session.conversationId,
            (evt) => {
                if (evt.type === 'delta') {
                    Components.removeSpinner();
                    if (!assistantDiv) {
                        assistantDiv = Components.renderChatMessage(
                            { type: 'assistant', text: '' }, msgs
                        );
                    }
                    replyText += evt.data;
                    Components.updateAssistantMessage(assistantDiv, replyText);
                } else if (evt.type === 'tool_call') {
                    Components.removeSpinner();
                    Components.renderChatMessage(
                        { type: 'tool_call', name: evt.name }, msgs
                    );
                } else if (evt.type === 'done') {
                    Components.removeSpinner();
                    if (evt.conversation_id) {
                        this.state.session.conversationId = evt.conversation_id;
                    }
                    input.disabled = false;
                    document.getElementById('btn-send').disabled = false;
                    input.focus();
                } else if (evt.type === 'error') {
                    Components.removeSpinner();
                    Components.renderChatMessage(
                        { type: 'error', text: evt.data || evt.message }, msgs
                    );
                    input.disabled = false;
                    document.getElementById('btn-send').disabled = false;
                }
            }
        );
    },

    /* ── Build / Export ───────────────────────────────────── */

    buildAgent() {
        const name = this.state.config.name.trim();
        if (!name) {
            alert('Please enter an agent name first.');
            return;
        }

        const overlay = document.getElementById('build-overlay');
        const log = document.getElementById('build-log');
        const closeBtn = document.getElementById('btn-build-close');
        const title = document.getElementById('build-title');

        log.innerHTML = '';
        title.textContent = `Building "${name}"...`;
        closeBtn.disabled = true;
        overlay.classList.add('active');

        API.streamBuild(this.state.config, (evt) => {
            if (evt.type === 'step') {
                Components.appendBuildLog(evt.message, 'log-step', log);
            } else if (evt.type === 'log') {
                Components.appendBuildLog(evt.message, null, log);
            } else if (evt.type === 'done') {
                Components.appendBuildLog(
                    `\nBuild complete: ${evt.path}`, 'log-ok', log
                );
                title.textContent = 'Build Complete';
                closeBtn.disabled = false;
            } else if (evt.type === 'error') {
                Components.appendBuildLog(
                    `\nError: ${evt.message}`, 'log-err', log
                );
                title.textContent = 'Build Failed';
                closeBtn.disabled = false;
            }
        });
    },

    async exportScript() {
        const name = this.state.config.name.trim();
        if (!name) {
            alert('Please enter an agent name first.');
            return;
        }
        try {
            const result = await API.exportScript(this.state.config);
            if (result.error) {
                alert('Export failed: ' + result.error);
                return;
            }
            alert(`Script exported to:\n${result.entry_point}\n${result.config_path}`);
        } catch (e) {
            alert('Export failed: ' + e.message);
        }
    },
};

// Boot
document.addEventListener('DOMContentLoaded', () => App.init());
