/* DOM rendering functions for Agent Builder */

const Components = {

    renderToolList(tools, enabledSet, filter, container) {
        container.innerHTML = '';
        const groups = { builtin: [], registered: [] };

        for (const [name, schema] of Object.entries(tools)) {
            if (filter && !name.includes(filter) &&
                !(schema.description || '').toLowerCase().includes(filter)) {
                continue;
            }
            const group = schema._builtin ? 'builtin' : 'registered';
            groups[group].push({ name, schema });
        }

        for (const [groupKey, items] of Object.entries(groups)) {
            if (items.length === 0) continue;
            const groupDiv = document.createElement('div');
            groupDiv.className = 'tool-group';

            const header = document.createElement('div');
            header.className = 'tool-group-header';
            header.textContent = groupKey === 'builtin'
                ? `Built-in Tools (${items.length})`
                : `Registered Tools (${items.length})`;
            groupDiv.appendChild(header);

            for (const { name, schema } of items) {
                const item = document.createElement('div');
                item.className = 'tool-item';

                const cb = document.createElement('input');
                cb.type = 'checkbox';
                cb.checked = enabledSet === '__ALL__' || enabledSet.has(name);
                cb.dataset.tool = name;
                cb.addEventListener('change', () => {
                    window.App.toggleTool(name, cb.checked);
                });

                const info = document.createElement('div');
                info.className = 'tool-item-info';

                const nameSpan = document.createElement('div');
                nameSpan.className = 'tool-item-name';
                nameSpan.textContent = name;

                const desc = document.createElement('div');
                desc.className = 'tool-item-desc';
                desc.textContent = schema.description || '';
                desc.title = schema.description || '';

                info.appendChild(nameSpan);
                info.appendChild(desc);
                item.appendChild(cb);
                item.appendChild(info);
                groupDiv.appendChild(item);
            }

            container.appendChild(groupDiv);
        }
    },

    renderMcpCheckboxes(knownServers, enabledServers, container) {
        container.innerHTML = '';

        // Known MCP servers as checkboxes
        for (const [name, info] of Object.entries(knownServers)) {
            const checked = name in enabledServers;
            const item = document.createElement('div');
            item.className = 'tool-item';

            const cb = document.createElement('input');
            cb.type = 'checkbox';
            cb.checked = checked;
            cb.dataset.mcp = name;
            cb.addEventListener('change', () => {
                window.App.toggleMcp(name, cb.checked);
            });

            const infoDiv = document.createElement('div');
            infoDiv.className = 'tool-item-info';

            const nameSpan = document.createElement('div');
            nameSpan.className = 'tool-item-name';
            nameSpan.textContent = info.description || name;

            const desc = document.createElement('div');
            desc.className = 'tool-item-desc';
            desc.textContent = `${info.command} ${(info.args || []).join(' ')}`;

            infoDiv.appendChild(nameSpan);
            infoDiv.appendChild(desc);
            item.appendChild(cb);
            item.appendChild(infoDiv);
            container.appendChild(item);
        }

        // Custom (non-known) MCP servers as removable cards
        for (const [name, config] of Object.entries(enabledServers)) {
            if (name in knownServers) continue;
            const detail = config.url
                ? 'SSE: ' + this._esc(config.url)
                : this._esc(config.command || '') + ' ' + (config.args || []).join(' ');
            const card = document.createElement('div');
            card.className = 'server-card';
            card.style.marginTop = '8px';
            card.innerHTML = `
                <div class="server-card-header">
                    <span class="server-card-name">${this._esc(name)}</span>
                    <button class="btn btn-sm btn-danger" data-remove-mcp="${this._esc(name)}">Remove</button>
                </div>
                <div class="server-card-detail">${detail}</div>
            `;
            card.querySelector('[data-remove-mcp]').addEventListener('click', () => {
                window.App.removeMcpServer(name);
            });
            container.appendChild(card);
        }
    },

    renderLspCheckboxes(knownServers, enabledServers, container) {
        container.innerHTML = '';

        // Known LSP servers as checkboxes
        for (const [lang, info] of Object.entries(knownServers)) {
            const checked = lang in enabledServers;
            const item = document.createElement('div');
            item.className = 'tool-item';

            const cb = document.createElement('input');
            cb.type = 'checkbox';
            cb.checked = checked;
            cb.dataset.lsp = lang;
            cb.addEventListener('change', () => {
                window.App.toggleLsp(lang, cb.checked);
            });

            const infoDiv = document.createElement('div');
            infoDiv.className = 'tool-item-info';

            const nameSpan = document.createElement('div');
            nameSpan.className = 'tool-item-name';
            nameSpan.textContent = info.description || lang;

            const desc = document.createElement('div');
            desc.className = 'tool-item-desc';
            desc.textContent = `${info.command} ${(info.args || []).join(' ')}`;

            infoDiv.appendChild(nameSpan);
            infoDiv.appendChild(desc);
            item.appendChild(cb);
            item.appendChild(infoDiv);
            container.appendChild(item);
        }

        // Custom (non-known) LSP servers as removable cards
        for (const [lang, config] of Object.entries(enabledServers)) {
            if (lang in knownServers) continue;
            const card = document.createElement('div');
            card.className = 'server-card';
            card.style.marginTop = '8px';
            card.innerHTML = `
                <div class="server-card-header">
                    <span class="server-card-name">${this._esc(lang)}</span>
                    <button class="btn btn-sm btn-danger" data-remove-lsp="${this._esc(lang)}">Remove</button>
                </div>
                <div class="server-card-detail">${this._esc(config.command)} ${(config.args || []).join(' ')}</div>
            `;
            card.querySelector('[data-remove-lsp]').addEventListener('click', () => {
                window.App.removeLspServer(lang);
            });
            container.appendChild(card);
        }
    },

    renderWorkerList(workers, container, models, knownMcp, knownLsp) {
        container.innerHTML = '';
        if (!workers || workers.length === 0) {
            container.innerHTML = '<div class="config-list-empty">No workers defined</div>';
            return;
        }

        workers.forEach((w, idx) => {
            const card = document.createElement('div');
            card.className = 'worker-card';

            // Header with role and remove button
            const header = document.createElement('div');
            header.className = 'worker-card-header';
            header.innerHTML = `
                <span class="worker-card-index">#${idx + 1}</span>
                <button class="btn btn-sm btn-danger" data-remove-worker="${idx}">Remove</button>
            `;
            header.querySelector('[data-remove-worker]').addEventListener('click', () => {
                window.App.removeWorker(idx);
            });
            card.appendChild(header);

            // Role
            const roleField = document.createElement('div');
            roleField.className = 'field';
            roleField.innerHTML = `<label>Role</label>`;
            const roleInput = document.createElement('input');
            roleInput.type = 'text';
            roleInput.value = w.role || '';
            roleInput.placeholder = 'coder, reviewer, tester...';
            roleInput.addEventListener('input', () => {
                window.App.updateWorker(idx, 'role', roleInput.value);
            });
            roleField.appendChild(roleInput);
            card.appendChild(roleField);

            // Model (optional override)
            const modelField = document.createElement('div');
            modelField.className = 'field';
            modelField.innerHTML = `<label>Model (blank = inherit)</label>`;
            const modelSel = document.createElement('select');
            const blankOpt = document.createElement('option');
            blankOpt.value = '';
            blankOpt.textContent = '\u2014 Inherit from orchestrator \u2014';
            modelSel.appendChild(blankOpt);
            for (const m of (models || [])) {
                const opt = document.createElement('option');
                opt.value = m.id || m;
                opt.textContent = m.name || m.id || m;
                modelSel.appendChild(opt);
            }
            modelSel.value = w.model || '';
            modelSel.addEventListener('change', () => {
                window.App.updateWorker(idx, 'model', modelSel.value || undefined);
            });
            modelField.appendChild(modelSel);
            card.appendChild(modelField);

            // Agent mode
            const modeField = document.createElement('div');
            modeField.className = 'field field-inline';
            const modeCb = document.createElement('input');
            modeCb.type = 'checkbox';
            modeCb.checked = w.agent_mode !== false;
            modeCb.addEventListener('change', () => {
                window.App.updateWorker(idx, 'agent_mode', modeCb.checked);
            });
            const modeLabel = document.createElement('label');
            modeLabel.appendChild(modeCb);
            modeLabel.appendChild(document.createTextNode(' Agent mode'));
            modeField.appendChild(modeLabel);
            card.appendChild(modeField);

            // Workspace (per-worker override)
            const wsField = document.createElement('div');
            wsField.className = 'field';
            wsField.innerHTML = `<label>Workspace (blank = inherit)</label>`;
            const wsInput = document.createElement('input');
            wsInput.type = 'text';
            wsInput.value = w.workspace_root || '';
            wsInput.placeholder = 'Inherits from orchestrator';
            wsInput.addEventListener('input', () => {
                window.App.updateWorker(idx, 'workspace_root', wsInput.value || undefined);
            });
            wsField.appendChild(wsInput);
            card.appendChild(wsField);

            // Proxy URL
            const proxyField = document.createElement('div');
            proxyField.className = 'field';
            proxyField.innerHTML = `<label>Proxy URL (blank = inherit)</label>`;
            const proxyInput = document.createElement('input');
            proxyInput.type = 'text';
            proxyInput.value = w.proxy_url || '';
            proxyInput.placeholder = 'Inherits from orchestrator';
            proxyInput.addEventListener('input', () => {
                window.App.updateWorker(idx, 'proxy_url', proxyInput.value || undefined);
            });
            proxyField.appendChild(proxyInput);
            card.appendChild(proxyField);

            // No SSL verify
            const sslField = document.createElement('div');
            sslField.className = 'field field-inline';
            const sslCb = document.createElement('input');
            sslCb.type = 'checkbox';
            sslCb.checked = !!w.no_ssl_verify;
            sslCb.addEventListener('change', () => {
                window.App.updateWorker(idx, 'no_ssl_verify', sslCb.checked || undefined);
            });
            const sslLabel = document.createElement('label');
            sslLabel.appendChild(sslCb);
            sslLabel.appendChild(document.createTextNode(' Skip SSL verification'));
            sslField.appendChild(sslLabel);
            card.appendChild(sslField);

            // Tools
            const toolsField = document.createElement('div');
            toolsField.className = 'field';
            const isAllTools = !w.tools_enabled || w.tools_enabled === '__ALL__';
            toolsField.innerHTML = `<label>Tools</label>`;
            const toolsSel = document.createElement('select');
            toolsSel.className = 'worker-tools-mode';
            toolsSel.innerHTML = `
                <option value="__ALL__">All tools</option>
                <option value="custom">Custom list...</option>
            `;
            toolsSel.value = isAllTools ? '__ALL__' : 'custom';
            toolsField.appendChild(toolsSel);

            const toolsTa = document.createElement('textarea');
            toolsTa.className = 'worker-tools-list';
            toolsTa.placeholder = 'read_file, list_dir, grep_search, ...';
            toolsTa.style.display = isAllTools ? 'none' : '';
            toolsTa.style.marginTop = '6px';
            if (!isAllTools && Array.isArray(w.tools_enabled)) {
                toolsTa.value = w.tools_enabled.join(', ');
            }
            toolsTa.addEventListener('input', () => {
                const list = toolsTa.value.split(',').map(s => s.trim()).filter(Boolean);
                window.App.updateWorker(idx, 'tools_enabled', list);
            });
            toolsField.appendChild(toolsTa);

            toolsSel.addEventListener('change', () => {
                if (toolsSel.value === '__ALL__') {
                    toolsTa.style.display = 'none';
                    window.App.updateWorker(idx, 'tools_enabled', '__ALL__');
                } else {
                    toolsTa.style.display = '';
                    toolsTa.focus();
                }
            });
            card.appendChild(toolsField);

            // MCP servers (per-worker checkboxes)
            if (knownMcp && Object.keys(knownMcp).length > 0) {
                const mcpSection = document.createElement('div');
                mcpSection.className = 'field';
                mcpSection.innerHTML = `<label>MCP Servers (unchecked = inherit)</label>`;
                const mcpEnabled = w.mcp_servers || {};
                for (const [name, info] of Object.entries(knownMcp)) {
                    const item = document.createElement('div');
                    item.className = 'tool-item';
                    const cb = document.createElement('input');
                    cb.type = 'checkbox';
                    cb.checked = name in mcpEnabled;
                    cb.addEventListener('change', () => {
                        window.App.toggleWorkerMcp(idx, name, cb.checked, info);
                    });
                    const nameSpan = document.createElement('span');
                    nameSpan.className = 'tool-item-name';
                    nameSpan.textContent = info.description || name;
                    item.appendChild(cb);
                    item.appendChild(nameSpan);
                    mcpSection.appendChild(item);
                }
                card.appendChild(mcpSection);
            }

            // LSP servers (per-worker checkboxes)
            if (knownLsp && Object.keys(knownLsp).length > 0) {
                const lspSection = document.createElement('div');
                lspSection.className = 'field';
                lspSection.innerHTML = `<label>LSP Servers (unchecked = inherit)</label>`;
                const lspEnabled = w.lsp_servers || {};
                for (const [lang, info] of Object.entries(knownLsp)) {
                    const item = document.createElement('div');
                    item.className = 'tool-item';
                    const cb = document.createElement('input');
                    cb.type = 'checkbox';
                    cb.checked = lang in lspEnabled;
                    cb.addEventListener('change', () => {
                        window.App.toggleWorkerLsp(idx, lang, cb.checked, info);
                    });
                    const nameSpan = document.createElement('span');
                    nameSpan.className = 'tool-item-name';
                    nameSpan.textContent = info.description || lang;
                    item.appendChild(cb);
                    item.appendChild(nameSpan);
                    lspSection.appendChild(item);
                }
                card.appendChild(lspSection);
            }

            // System prompt
            const promptField = document.createElement('div');
            promptField.className = 'field';
            promptField.innerHTML = `<label>System Prompt</label>`;
            const promptTa = document.createElement('textarea');
            promptTa.className = 'worker-prompt';
            promptTa.value = w.system_prompt || '';
            promptTa.placeholder = 'Instructions for this worker...';
            promptTa.addEventListener('input', () => {
                window.App.updateWorker(idx, 'system_prompt', promptTa.value);
            });
            promptField.appendChild(promptTa);
            card.appendChild(promptField);

            container.appendChild(card);
        });
    },

    renderChatMessage(msg, container) {
        const div = document.createElement('div');
        div.className = 'msg';

        if (msg.type === 'user') {
            div.classList.add('msg-user');
            div.innerHTML = `<span class="prompt-marker">&#x276F;</span>${this._esc(msg.text)}`;
        } else if (msg.type === 'assistant') {
            div.classList.add('msg-assistant');
            div.innerHTML = `<span class="reply-marker">&#x23FA;</span>${this._esc(msg.text)}`;
        } else if (msg.type === 'tool_call') {
            div.classList.add('msg-tool');
            div.innerHTML = `&#x2502; <span class="tool-name">${this._esc(msg.name)}</span>`;
        } else if (msg.type === 'error') {
            div.classList.add('msg-error');
            div.textContent = msg.text;
        } else if (msg.type === 'status') {
            div.classList.add('msg-status');
            div.textContent = msg.text;
        } else if (msg.type === 'spinner') {
            div.classList.add('msg-spinner');
            div.id = 'chat-spinner';
            div.textContent = 'Thinking';
        }

        container.appendChild(div);
        container.scrollTop = container.scrollHeight;
        return div;
    },

    updateAssistantMessage(div, text) {
        div.innerHTML = `<span class="reply-marker">&#x23FA;</span>${this._esc(text)}`;
    },

    removeSpinner() {
        const el = document.getElementById('chat-spinner');
        if (el) el.remove();
    },

    renderConfigList(configs, container, onSelect, onDelete) {
        container.innerHTML = '';
        if (configs.length === 0) {
            container.innerHTML = '<div class="config-list-empty">No saved configs</div>';
            return;
        }
        for (const name of configs) {
            const item = document.createElement('div');
            item.className = 'config-list-item';
            item.innerHTML = `
                <span class="config-list-item-name">${this._esc(name)}</span>
                <button class="btn btn-sm btn-danger" data-delete="${this._esc(name)}">Delete</button>
            `;
            item.addEventListener('click', (e) => {
                if (e.target.dataset.delete) return;
                onSelect(name);
            });
            item.querySelector('[data-delete]').addEventListener('click', (e) => {
                e.stopPropagation();
                onDelete(name);
            });
            container.appendChild(item);
        }
    },

    appendBuildLog(text, cls, container) {
        const span = document.createElement('span');
        if (cls) span.className = cls;
        span.textContent = text + '\n';
        container.appendChild(span);
        container.scrollTop = container.scrollHeight;
    },

    _esc(s) {
        const d = document.createElement('div');
        d.textContent = s || '';
        return d.innerHTML;
    },
};
