(function () {
    const logEl = document.getElementById('consoleContent');
    const logContainer = document.getElementById('logContainer');
    const consoleStatusText = document.getElementById('consoleStatusText');
    const refreshConsoleBtn = document.getElementById('refreshConsoleBtn');

    let flushScheduled = false;
    let snapshotInFlight = false;
    let remoteSnapshotInFlight = {};
    let activeNodeTab = 'local';
    let remoteLogBuffers = {};
    let remoteSnapshots = {};
    const REMOTE_MAX_BUFFER = 5000;

    let logBuffer = [];
    let currentFilter = '';
    let currentFilterNodeId = 'local';
    let snapshotText = '';
    let modelSnapshots = {};
    const MAX_BUFFER = 10000;
    const MAX_MODEL_SNAPSHOTS = 20;
    const MODEL_SNAPSHOT_TRIM_TO = 10;
    let consoleInitialized = false;

    function getValidNodeIds() {
        var ids = { local: true };
        (window.remoteNodes || []).forEach(function (n) {
            if (n && n.nodeId) ids[n.nodeId] = true;
        });
        return ids;
    }

    function cleanupStaleRemoteState() {
        var valid = getValidNodeIds();
        [remoteLogBuffers, remoteSnapshots, remoteSnapshotInFlight].forEach(function (map) {
            Object.keys(map).forEach(function (key) {
                if (!valid[key]) delete map[key];
            });
        });
        Object.keys(modelSnapshots).forEach(function (key) {
            var nodeId = key.indexOf('|||') >= 0 ? key.split('|||')[1] : 'local';
            if (!valid[nodeId]) delete modelSnapshots[key];
        });
    }

    function trimModelSnapshots() {
        var keys = Object.keys(modelSnapshots);
        if (keys.length <= MAX_MODEL_SNAPSHOTS) return;
        var removeCount = keys.length - MODEL_SNAPSHOT_TRIM_TO;
        for (var i = 0; i < removeCount; i++) {
            delete modelSnapshots[keys[i]];
        }
    }

    function matchFilter(modelId) {
        if (!currentFilter) return true;
        return (modelId || 'system') === currentFilter;
    }

    function getCacheKey() {
        if (!currentFilter) return '';
        if (currentFilter === 'system') return 'system';
        if (currentFilterNodeId && currentFilterNodeId !== 'local') return currentFilter + '|||' + currentFilterNodeId;
        return currentFilter;
    }

    function setLogFilter(filter, nodeId) {
        const plainModelId = filter && filter.includes('|||') ? filter.split('|||')[0] : (filter || '');
        currentFilter = plainModelId;
        currentFilterNodeId = nodeId || 'local';
        window._consoleCurrentFilter = filter || '';
        const container = document.getElementById('consoleModelListItems');
        if (container) {
            container.querySelectorAll('.console-model-item').forEach(function (item) {
                item.classList.toggle('active', item.dataset.filter === (filter || ''));
            });
        }
        const cacheKey = getCacheKey();
        if (currentFilter && currentFilter !== 'system' && !modelSnapshots[cacheKey]) {
            const url = currentFilterNodeId !== 'local'
                ? '/api/sys/model-log?modelId=' + encodeURIComponent(currentFilter) + '&nodeId=' + encodeURIComponent(currentFilterNodeId)
                : '/api/sys/model-log?modelId=' + encodeURIComponent(currentFilter);
            fetch(url)
                .then(function (r) { return r.text(); })
                .then(function (text) {
                    modelSnapshots[cacheKey] = text || '';
                    trimModelSnapshots();
                    renderFiltered();
                    if (activeNodeTab !== 'local') renderRemoteFiltered(activeNodeTab);
                })
                .catch(function () {
                    modelSnapshots[cacheKey] = '';
                    renderFiltered();
                    if (activeNodeTab !== 'local') renderRemoteFiltered(activeNodeTab);
                });
        }
        renderFiltered();
        if (activeNodeTab !== 'local') renderRemoteFiltered(activeNodeTab);
    }

    function renderFiltered() {
        if (!logEl) return;
        const atBottom = nearBottom();
        let chunk = '';
        let matched = 0;
        const cacheKey = getCacheKey();
        if (!currentFilter || currentFilter === 'system') {
            chunk = snapshotText || '';
        } else if (cacheKey && modelSnapshots[cacheKey]) {
            chunk = modelSnapshots[cacheKey];
        }
        for (let i = 0; i < logBuffer.length; i++) {
            if (matchFilter(logBuffer[i].modelId)) {
                chunk += logBuffer[i].text;
                matched++;
            }
        }
        logEl.textContent = chunk;
        if (consoleStatusText) {
            const label = currentFilter || 'All';
            consoleStatusText.textContent = 'Filter: ' + label + ' · Lines: ' + matched + '/' + logBuffer.length;
        }
        if (atBottom) scrollBottom();
    }

    function renderRemoteFiltered(nodeId) {
        if (!nodeId || nodeId === 'local') return;
        var pre = document.getElementById('remoteConsoleContent-' + nodeId);
        if (!pre) return;
        var container = document.getElementById('remoteLogContainer-' + nodeId);
        var atBottom = container ? Math.abs(container.scrollHeight - container.scrollTop - container.clientHeight) < 50 : true;
        var chunk = '';
        if (!currentFilter || currentFilter === 'system') {
            chunk = remoteSnapshots[nodeId] || '';
        } else {
            var cacheKey = getCacheKey();
            if (cacheKey && modelSnapshots[cacheKey]) {
                chunk = modelSnapshots[cacheKey];
            }
        }
        var buf = remoteLogBuffers[nodeId] || [];
        for (var i = 0; i < buf.length; i++) {
            if (matchFilter(buf[i].modelId)) {
                chunk += buf[i].text;
            }
        }
        pre.textContent = chunk;
        if (atBottom && container) {
            container.scrollTop = container.scrollHeight;
        }
    }

    function nearBottom() {
        if (!logContainer) return true;
        return Math.abs(logContainer.scrollHeight - logContainer.scrollTop - logContainer.clientHeight) < 50;
    }

    function scrollBottom() {
        if (!logContainer) return;
        logContainer.scrollTop = logContainer.scrollHeight;
    }

    async function fetchConsole() {
        if (consoleStatusText) consoleStatusText.textContent = t('page.console.status.loading', '加载中...');
        snapshotInFlight = true;
        try {
            const res = await fetch('/api/sys/console');
            const text = await res.text();
            snapshotText = text;
            snapshotInFlight = false;
            renderFiltered();
            if (consoleStatusText) {
                consoleStatusText.textContent = t('page.console.status.updated', '已更新 · ') + new Date().toLocaleTimeString() + ' · Size: ' + text.length;
            }
        } catch (e) {
            snapshotInFlight = false;
            if (consoleStatusText) consoleStatusText.textContent = t('page.console.status.load_failed', '加载失败');
        }
    }

    function openConsoleModal() {
        cleanupStaleRemoteState();
        if (!consoleInitialized) {
            activeNodeTab = 'local';
            if (typeof populateLogFilter === 'function') populateLogFilter('local');
            initConsoleTabs();
            consoleInitialized = true;
        }
        fetchConsole();
        var nodes = window.remoteNodes || [];
        nodes.forEach(function (n) {
            if (n.enabled !== false && n.nodeId) {
                fetchRemoteConsole(n.nodeId);
            }
        });
        setTimeout(function () {
            scrollBottom();
        }, 100);
    }

    function appendLogLine(line, timestamp, modelId) {
        if (!logEl) return;
        const clean = (line || '').replace(/\r/g, '');
        const withNl = clean.endsWith('\n') ? clean : clean + '\n';
        const entry = { text: withNl, ts: typeof timestamp === 'number' ? timestamp : 0, modelId: modelId || 'system' };
        logBuffer.push(entry);
        if (logBuffer.length > MAX_BUFFER) {
            logBuffer.splice(0, logBuffer.length - MAX_BUFFER);
        }
        if (snapshotInFlight) return;
        scheduleFlush();
    }

    function scheduleFlush() {
        if (!flushScheduled) {
            flushScheduled = true;
            requestAnimationFrame(function () {
                flushPendingLogs();
            });
        }
    }

    function flushPendingLogs() {
        flushScheduled = false;
        renderFiltered();
        if (activeNodeTab !== 'local') {
            renderRemoteFiltered(activeNodeTab);
        }
    }

    function startConsoleAuto() {
        stopConsoleAuto();
        const interval = Math.max(500, parseInt((intervalConsoleInput && intervalConsoleInput.value) || '2000', 10));
        consoleTimer = setInterval(fetchConsole, interval);
    }

    function stopConsoleAuto() {}

    function initConsoleTabs() {
        const tabBar = document.getElementById('consoleTabs');
        const container = document.getElementById('consoleTabContainer');
        if (!tabBar || !container) return;
        if (tabBar._initialized) return;
        tabBar._initialized = true;

        var nodes = window.remoteNodes || [];
        nodes.forEach(function (node) {
            if (node.enabled === false) return;
            var nid = node.nodeId;
            var tab = document.createElement('button');
            tab.className = 'console-tab-btn';
            tab.dataset.tab = nid;
            tab.textContent = node.name || nid;
            tabBar.appendChild(tab);

            var panel = document.createElement('div');
            panel.className = 'console-tab-panel';
            panel.dataset.panel = nid;
            panel.innerHTML = '<div id="remoteLogContainer-' + nid + '" style="width:100%;height:100%;overflow:auto;padding:16px;font-family:\'Menlo\',\'Monaco\',\'Courier New\',monospace;background:#0f172a;color:#e5e7eb;font-size:0.875rem;"><pre id="remoteConsoleContent-' + nid + '" style="margin:0;white-space:pre-wrap;word-break:break-word;overflow-wrap:break-word;"></pre></div>';
            container.appendChild(panel);
        });

        tabBar.addEventListener('click', function (e) {
            var btn = e.target.closest('.console-tab-btn');
            if (!btn) return;
            tabBar.querySelectorAll('.console-tab-btn').forEach(function (b) { b.classList.remove('active'); });
            btn.classList.add('active');
            var id = btn.dataset.tab;
            activeNodeTab = id;
            container.querySelectorAll('.console-tab-panel').forEach(function (p) {
                p.classList.toggle('active', p.dataset.panel === id);
            });
            if (typeof populateLogFilter === 'function') {
                populateLogFilter(id === 'local' ? 'local' : id);
            }
            currentFilter = '';
            currentFilterNodeId = 'local';
            window._consoleCurrentFilter = '';
            var listItems = document.getElementById('consoleModelListItems');
            if (listItems) {
                var allItems = listItems.querySelectorAll('.console-model-item');
                allItems.forEach(function (item, idx) {
                    item.classList.toggle('active', idx === 0);
                });
            }
            if (id === 'local') {
                renderFiltered();
            } else {
                renderRemoteFiltered(id);
            }
        });
    }

    async function fetchRemoteConsole(nodeId) {
        if (remoteSnapshotInFlight[nodeId]) return;
        remoteSnapshotInFlight[nodeId] = true;
        try {
            var resp = await fetch('/api/sys/console?nodeId=' + encodeURIComponent(nodeId));
            var text = await resp.text();
            remoteSnapshots[nodeId] = text;
            if (activeNodeTab === nodeId) {
                renderRemoteFiltered(nodeId);
            }
        } catch (e) {}
        delete remoteSnapshotInFlight[nodeId];
        scheduleFlush();
    }

    function appendRemoteLogLine(nodeId, text, modelId) {
        if (!remoteLogBuffers[nodeId]) remoteLogBuffers[nodeId] = [];
        var clean = (text || '').replace(/\r/g, '');
        var withNl = clean.endsWith('\n') ? clean : clean + '\n';
        remoteLogBuffers[nodeId].push({ text: withNl, modelId: modelId || 'system' });
        if (remoteLogBuffers[nodeId].length > REMOTE_MAX_BUFFER) {
            remoteLogBuffers[nodeId].splice(0, remoteLogBuffers[nodeId].length - REMOTE_MAX_BUFFER);
        }
        if (remoteSnapshotInFlight[nodeId]) return;
        scheduleFlush();
    }

    if (refreshConsoleBtn) {
        refreshConsoleBtn.addEventListener('click', function () {
            if (activeNodeTab === 'local') {
                fetchConsole();
            } else {
                fetchRemoteConsole(activeNodeTab);
            }
        });
    }

    window.openConsoleModal = openConsoleModal;
    window.appendLogLine = appendLogLine;
    window.setLogFilter = setLogFilter;
    window.stopConsoleAuto = stopConsoleAuto;
    window.initConsoleTabs = initConsoleTabs;
    window.appendRemoteLogLine = appendRemoteLogLine;
})();