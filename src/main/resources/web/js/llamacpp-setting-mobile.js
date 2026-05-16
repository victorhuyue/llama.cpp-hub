(function () {
    const byId = (id) => document.getElementById(id);

    function toast(title, msg, type) {
        if (typeof window.showToast === 'function') window.showToast(title, msg, type);
    }

    function escapeHtml(v) {
        const s = v == null ? '' : String(v);
        return s.replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function safeText(v) {
        return v == null ? '' : String(v);
    }

    let rows = [];
    let editingOriginalPath = '';

    function setListHtml(html) {
        const el = byId('mobileLlamaCppList');
        if (el) el.innerHTML = html;
    }

    function setCount(n) {
        const el = byId('mobileLlamaCppCount');
        if (el) el.textContent = String(n == null ? 0 : n);
    }

    function renderEmpty(icon, title, text) {
        return [
            '<div class="empty-state">',
            `<div class="empty-state-icon"><i class="fas ${icon}"></i></div>`,
            `<div class="empty-state-title">${escapeHtml(title)}</div>`,
            `<div class="empty-state-text">${escapeHtml(text)}</div>`,
            '</div>'
        ].join('');
    }

    function renderList() {
        if (!Array.isArray(rows) || rows.length === 0) {
            setListHtml(renderEmpty('fa-folder-open', '暂无配置', '还没有添加任何 Llama.cpp 路径'));
            return;
        }

        const cards = rows.map((it) => {
            const path = safeText(it && it.path).trim();
            const name = safeText(it && it.name).trim();
            const desc = safeText(it && it.description).trim();
            const source = safeText(it && it.source).trim() || 'configured';
            const isScanned = source === 'scanned';
            const title = name || path || '未命名';
            const subtitle = path ? path : '未提供目录路径';
            const tag = encodeURIComponent(path);

            const descHtml = desc
                ? `<div style="margin-top: 0.35rem; font-size: 0.875rem; color: var(--text-secondary); line-height: 1.35;">${escapeHtml(desc)}</div>`
                : '';

            const editBtn = isScanned ? '' : '<button class="btn btn-secondary btn-sm" data-llama-act="edit"><i class="fas fa-edit"></i> 编辑</button>';

            return [
                `<div class="model-item" data-llama-item="${tag}" data-llama-scanned="${isScanned}">`,
                '<div style="display:flex; align-items:flex-start; gap: 0.75rem; width:100%;">',
                '<div style="width: 40px; height: 40px; border-radius: 0.75rem; display:flex; align-items:center; justify-content:center; background: rgba(37, 99, 235, 0.12); flex: 0 0 auto;">',
                '<i class="fas fa-microchip" style="color: var(--primary-color);"></i>',
                '</div>',
                '<div style="flex:1; min-width:0;">',
                `<div style="font-weight: 750; overflow:hidden; text-overflow: ellipsis; white-space: nowrap;" title="${escapeHtml(title)}">${escapeHtml(title)}</div>`,
                `<div style="margin-top: 0.15rem; font-size: 0.85rem; color: var(--text-secondary); word-break: break-all;">${escapeHtml(subtitle)}</div>`,
                descHtml,
                '</div>',
                '</div>',
                '<div style="display:flex; gap: 0.5rem; margin-top: 0.75rem; width:100%; justify-content:flex-end; flex-wrap: wrap;">',
                '<button class="btn btn-secondary btn-sm" data-llama-act="test"><i class="fas fa-vial"></i> 测试</button>',
                editBtn,
                '<button class="btn btn-secondary btn-sm" data-llama-act="delete" style="border-color: rgba(239,68,68,0.35); color: rgb(239,68,68);"><i class="fas fa-trash"></i> 删除</button>',
                '</div>',
                '</div>'
            ].join('');
        });

        setListHtml(cards.join(''));
    }

    async function refresh() {
        setListHtml('<div class="loading-spinner"><div class="spinner"></div></div>');
        try {
            const resp = await fetch('/api/llamacpp/list');
            const data = await resp.json();
            if (!(data && data.success)) {
                toast('错误', (data && data.error) ? data.error : '加载失败', 'error');
                setListHtml(renderEmpty('fa-exclamation-triangle', '加载失败', '无法获取 Llama.cpp 列表'));
                setCount(0);
                rows = [];
                return;
            }
            const list = data && data.data && Array.isArray(data.data.items) ? data.data.items : [];
            rows = list;
            setCount(list.length);
            renderList();
        } catch (e) {
            toast('错误', '网络请求失败', 'error');
            setListHtml(renderEmpty('fa-wifi', '网络错误', '无法连接到服务器'));
            setCount(0);
            rows = [];
        }
    }

    function openEditor(mode, item) {
        const modal = byId('mobileLlamaCppEditModal');
        if (!modal) return;

        const titleEl = byId('mobileLlamaCppEditTitle');
        const pathEl = byId('mobileLlamaCppPathInput');
        const nameEl = byId('mobileLlamaCppNameInput');
        const descEl = byId('mobileLlamaCppDescInput');

        const p = safeText(item && item.path).trim();
        const n = safeText(item && item.name).trim();
        const d = safeText(item && item.description).trim();

        editingOriginalPath = mode === 'edit' ? p : '';
        if (pathEl) pathEl.value = mode === 'edit' ? p : '';
        if (nameEl) nameEl.value = mode === 'edit' ? n : '';
        if (descEl) descEl.value = mode === 'edit' ? d : '';

        if (titleEl) titleEl.textContent = mode === 'edit' ? '编辑路径' : '添加路径';
        modal.classList.add('show');
    }

    async function saveEditor() {
        const pathEl = byId('mobileLlamaCppPathInput');
        const nameEl = byId('mobileLlamaCppNameInput');
        const descEl = byId('mobileLlamaCppDescInput');
        const saveBtn = byId('mobileLlamaCppSaveBtn');

        const path = safeText(pathEl && pathEl.value).trim();
        const name = safeText(nameEl && nameEl.value).trim();
        const description = safeText(descEl && descEl.value).trim();

        if (!path) {
            toast('错误', '请输入目录路径', 'error');
            return;
        }

        if (saveBtn) saveBtn.disabled = true;
        try {
            if (editingOriginalPath) {
                await fetch('/api/llamacpp/remove', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ path: editingOriginalPath })
                }).catch(() => null);
            }

            const payload = { path };
            if (name) payload.name = name;
            if (description) payload.description = description;

            const resp = await fetch('/api/llamacpp/add', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await resp.json();

            if (!(data && data.success)) {
                toast('错误', (data && data.error) ? data.error : '保存失败', 'error');
                return;
            }

            toast('成功', editingOriginalPath ? '已更新' : '已添加', 'success');
            editingOriginalPath = '';
            if (typeof window.closeModal === 'function') window.closeModal('mobileLlamaCppEditModal');
            await refresh();
        } catch (e) {
            toast('错误', '网络请求失败', 'error');
        } finally {
            if (saveBtn) saveBtn.disabled = false;
        }
    }

    async function removeOne(path, isScanned) {
        const p = safeText(path).trim();
        if (!p) return;
        if (isScanned) {
            showMobileDeleteConfirm(p, function() {
                doMobileRemove(p);
            });
        } else {
            if (!confirm('确定要删除这条路径吗？')) return;
            doMobileRemove(p);
        }
    }

    async function doMobileRemove(path) {
        try {
            const resp = await fetch('/api/llamacpp/remove', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ path: path })
            });
            const data = await resp.json();
            if (data && data.success) {
                toast('成功', '已删除', 'success');
                await refresh();
            } else {
                toast('错误', (data && data.error) ? data.error : '删除失败', 'error');
            }
        } catch (e) {
            toast('错误', '网络请求失败', 'error');
        }
    }

    function showMobileDeleteConfirm(path, onConfirm) {
        const existingModal = byId('mobileLlamaCppDeleteConfirmModal');
        if (existingModal) existingModal.remove();

        const modal = document.createElement('div');
        modal.id = 'mobileLlamaCppDeleteConfirmModal';
        modal.className = 'modal';
        modal.innerHTML = [
            '<div class="modal-content" style="max-width: 520px;">',
            '<div class="modal-header" style="border-bottom: 1px solid rgba(239,68,68,0.2);">',
            '<h3 class="modal-title" style="color: rgb(239,68,68);"><i class="fas fa-exclamation-triangle"></i> 删除磁盘目录</h3>',
            '<button class="modal-close" onclick="closeModal(\'mobileLlamaCppDeleteConfirmModal\')">&times;</button>',
            '</div>',
            '<div class="modal-body">',
            '<p style="margin-bottom: 0.75rem;">此操作将永久删除以下磁盘目录及其所有内容，且不可恢复：</p>',
            `<div style="background: rgba(239,68,68,0.08); border: 1px solid rgba(239,68,68,0.25); border-radius: 0.5rem; padding: 0.6rem 0.75rem; word-break: break-all; font-family: monospace; font-size: 0.875rem; color: rgb(239,68,68);">${escapeHtml(path)}</div>`,
            '</div>',
            '<div class="modal-footer">',
            '<button class="btn btn-secondary" onclick="closeModal(\'mobileLlamaCppDeleteConfirmModal\')">取消</button>',
            '<button class="btn" id="mobileLlamaCppDeleteConfirmBtn" style="background: rgb(239,68,68); border-color: rgb(239,68,68); color: #fff;">确认删除</button>',
            '</div>',
            '</div>'
        ].join('');

        const root = byId('dynamicModalRoot') || document.body;
        root.appendChild(modal);
        modal.classList.add('show');

        const confirmBtn = byId('mobileLlamaCppDeleteConfirmBtn');
        if (confirmBtn) {
            confirmBtn.addEventListener('click', function() {
                if (typeof window.closeModal === 'function') window.closeModal('mobileLlamaCppDeleteConfirmModal');
                onConfirm();
            });
        }
    }

    function ensureTestModal() {
        const existing = byId('mobileLlamaCppTestModal');
        if (existing) return existing;

        const modal = document.createElement('div');
        modal.id = 'mobileLlamaCppTestModal';
        modal.className = 'modal';
        modal.innerHTML = [
            '<div class="modal-content" style="width:100vw; height:100vh; max-width:none; max-height:none; border-radius:0;">',
            '<div class="modal-header">',
            '<h3 class="modal-title" id="mobileLlamaCppTestTitle">Llama.cpp 测试</h3>',
            '<button class="modal-close" onclick="closeModal(\'mobileLlamaCppTestModal\')">&times;</button>',
            '</div>',
            '<div class="modal-body" style="overflow:auto;">',
            '<div style="font-size:0.875rem; color: var(--text-secondary); margin-bottom: 0.75rem;">',
            '用于检查 llama-cli 是否可用，以及设备枚举是否正常。',
            '</div>',
            '<div style="display:flex; flex-direction:column; gap: 0.75rem;">',
            '<div style="border: 1px solid var(--border-color); border-radius: 0.75rem; padding: 0.75rem;">',
            '<div style="font-weight: 700; margin-bottom: 0.35rem;">version</div>',
            '<pre id="mobileLlamaCppTestVersion" style="white-space: pre-wrap; margin:0;"></pre>',
            '</div>',
            '<div style="border: 1px solid var(--border-color); border-radius: 0.75rem; padding: 0.75rem;">',
            '<div style="font-weight: 700; margin-bottom: 0.35rem;">devices</div>',
            '<pre id="mobileLlamaCppTestDevices" style="white-space: pre-wrap; margin:0;"></pre>',
            '</div>',
            '<details style="border: 1px solid var(--border-color); border-radius: 0.75rem; padding: 0.75rem;">',
            '<summary style="font-weight: 700; cursor:pointer;">原始响应</summary>',
            '<pre id="mobileLlamaCppTestRaw" style="white-space: pre-wrap; margin: 0.75rem 0 0 0;"></pre>',
            '</details>',
            '</div>',
            '</div>',
            '<div class="modal-footer">',
            '<button class="btn btn-secondary" onclick="closeModal(\'mobileLlamaCppTestModal\')">关闭</button>',
            '</div>',
            '</div>'
        ].join('');

        const root = byId('dynamicModalRoot') || document.body;
        root.appendChild(modal);
        return modal;
    }

    function setPre(id, text) {
        const el = byId(id);
        if (el) el.textContent = text == null ? '' : String(text);
    }

    async function testOne(item) {
        const modal = ensureTestModal();
        modal.classList.add('show');

        const path = safeText(item && item.path).trim();
        const name = safeText(item && item.name).trim();
        const desc = safeText(item && item.description).trim();
        const display = name || path || 'Llama.cpp';

        const titleEl = byId('mobileLlamaCppTestTitle');
        if (titleEl) titleEl.textContent = '测试 - ' + display;
        setPre('mobileLlamaCppTestVersion', '加载中...');
        setPre('mobileLlamaCppTestDevices', '加载中...');
        setPre('mobileLlamaCppTestRaw', '');

        try {
            const payload = { path };
            if (name) payload.name = name;
            if (desc) payload.description = desc;

            const resp = await fetch('/api/llamacpp/test', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await resp.json();
            if (!(data && data.success)) {
                toast('错误', (data && data.error) ? data.error : '测试失败', 'error');
                setPre('mobileLlamaCppTestVersion', '');
                setPre('mobileLlamaCppTestDevices', '');
                setPre('mobileLlamaCppTestRaw', JSON.stringify(data, null, 2));
                return;
            }
            const version = data && data.data && data.data.version ? data.data.version : null;
            const devices = data && data.data && data.data.listDevices ? data.data.listDevices : null;
            setPre('mobileLlamaCppTestVersion', version ? (version.output || '') : '');
            setPre('mobileLlamaCppTestDevices', devices ? (devices.output || '') : '');
            setPre('mobileLlamaCppTestRaw', JSON.stringify(data, null, 2));
        } catch (e) {
            toast('错误', '网络请求失败', 'error');
            setPre('mobileLlamaCppTestVersion', '');
            setPre('mobileLlamaCppTestDevices', '');
            setPre('mobileLlamaCppTestRaw', safeText(e && e.message ? e.message : e));
        }
    }

    function findRowByPath(path) {
        const p = safeText(path).trim();
        if (!p) return null;
        const list = Array.isArray(rows) ? rows : [];
        for (let i = 0; i < list.length; i++) {
            const it = list[i];
            if (safeText(it && it.path).trim() === p) return it;
        }
        return null;
    }

    function bind() {
        const list = byId('mobileLlamaCppList');
        if (list) {
            list.addEventListener('click', function (e) {
                const btn = e && e.target ? e.target.closest('button[data-llama-act]') : null;
                if (!btn) return;
                const card = btn.closest('[data-llama-item]');
                const tag = card ? card.getAttribute('data-llama-item') : '';
                const path = tag ? decodeURIComponent(tag) : '';
                const item = findRowByPath(path) || { path };
                const act = btn.getAttribute('data-llama-act');
                const isScanned = card ? card.getAttribute('data-llama-scanned') === 'true' : false;
                if (act === 'edit') openEditor('edit', item);
                else if (act === 'delete') removeOne(path, isScanned);
                else if (act === 'test') testOne(item);
            });
        }

        const refreshBtn = byId('mobileLlamaCppRefreshBtn');
        if (refreshBtn) refreshBtn.addEventListener('click', refresh);

        const addBtn = byId('mobileLlamaCppAddBtn');
        if (addBtn) addBtn.addEventListener('click', function () { openEditor('add'); });

        const backBtn = byId('mobileLlamaCppBackBtn');
        if (backBtn) backBtn.addEventListener('click', function () {
            if (window.MobilePage && typeof window.MobilePage.show === 'function') window.MobilePage.show('settings');
        });

        const saveBtn = byId('mobileLlamaCppSaveBtn');
        if (saveBtn) saveBtn.addEventListener('click', saveEditor);
    }

    document.addEventListener('DOMContentLoaded', function () {
        bind();
    });

    window.MobileLlamaCppSetting = { refresh };
})();

