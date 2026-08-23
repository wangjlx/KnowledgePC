const App = {
  state: {
    currentPage: 'dashboard',
    entries: [],
    records: [],
    tags: [],
    currentEntry: null,
    currentRecord: null,
    editing: false,
    editData: null,
    currentUser: null,
    currentRole: 'user'
  },
  collapsedTags: new Set(),
  _graphSaveTimer: null,

  async init() {
    const token = API._token;
    if (token) {
      try {
        const data = await API.verifyToken();
        this.state.currentUser = data.username;
        this.state.currentRole = data.role || 'user';
        this.renderLayout();
        await this.loadTags();
        await this.navigate('dashboard');
        return;
      } catch (e) { API.setToken(''); }
    }
    this.showLogin();
  },

  renderLayout() {
    const app = document.getElementById('app');
    app.innerHTML = `
      <div class="app-layout">
        <aside class="sidebar" id="sidebar">
          <div class="sidebar-header">知识库</div>
          <nav class="sidebar-nav">
            <div class="nav-item active" data-page="dashboard" onclick="App.navigate('dashboard')">
              <span class="icon">📊</span> 仪表盘
            </div>
            <div class="nav-item" data-page="records" onclick="App.navigate('records')">
              <span class="icon">📋</span> 日常记录
            </div>
            <div class="nav-item" data-page="entries" onclick="App.navigate('entries')">
              <span class="icon">📝</span> 知识条目
            </div>
            <div class="nav-item" data-page="graph" onclick="App.navigate('graph')">
              <span class="icon">🔗</span> 知识图谱
            </div>
            <div class="nav-item" data-page="tags" onclick="App.navigate('tags')">
              <span class="icon">🏷️</span> 标签管理
            </div>
            <div class="nav-item" data-page="search" onclick="App.navigate('search')">
              <span class="icon">🔍</span> 智能搜索
            </div>
            <div class="nav-item" data-page="export" onclick="App.navigate('export')" id="navExport" style="${this.state.currentRole !== 'admin' ? 'display:none' : ''}">
              <span class="icon">📦</span> 数据管理
            </div>
            <div class="nav-item" data-page="admin" onclick="App.navigate('admin')" id="navAdmin" style="${this.state.currentRole !== 'admin' ? 'display:none' : ''}">
              <span class="icon">⚙️</span> 后台管理
            </div>
            <div class="nav-item" style="cursor:pointer;color:var(--text-secondary);font-size:13px;margin-top:8px;border-top:1px solid var(--border);padding-top:8px" onclick="App.logout()">
              <span class="icon">🚪</span> 退出登录
            </div>
          </nav>
          <div class="sidebar-footer" id="sidebarStats"></div>
        </aside>
        <main class="main-content" id="mainContent">
          <div class="page-header" id="pageHeader">
            <h2 id="pageTitle">仪表盘</h2>
            <div class="actions" id="pageActions"></div>
          </div>
          <div class="page-body" id="pageBody"></div>
        </main>
      </div>
      <div class="modal-overlay" id="modalOverlay" onclick="if(event.target===this)App.closeModal()">
        <div class="modal" id="modalContent"></div>
      </div>
      <div class="toast" id="toast"></div>
    `;
  },

  async navigate(page, params) {
    this.state.currentPage = page;
    document.querySelectorAll('.nav-item').forEach(el => el.classList.toggle('active', el.dataset.page === page));
    const header = document.getElementById('pageTitle');
    const actions = document.getElementById('pageActions');
    const body = document.getElementById('pageBody');
    body.innerHTML = '<div class="loading">加载中...</div>';

    switch (page) {
      case 'dashboard': header.textContent = '仪表盘'; actions.innerHTML = ''; await this.renderDashboard(body); break;
      case 'entries': header.textContent = '知识条目'; actions.innerHTML = '<button class="btn btn-primary" onclick="App.showEntryEditor()">+ 新建条目</button><button class="btn" onclick="App.pickAndImportFile(\'entry\')">📥 导入</button>'; await this.renderEntries(body, params); break;
      case 'records': header.textContent = '日常记录'; actions.innerHTML = '<button class="btn btn-primary" onclick="App.showRecordEditor()">+ 新建记录</button><button class="btn" onclick="App.pickAndImportFile(\'record\')">📥 导入</button>'; await this.renderRecords(body, params); break;
      case 'graph': header.textContent = '知识图谱'; actions.innerHTML = ''; await this.renderGraph(body); break;
      case 'tags': header.textContent = '标签管理'; actions.innerHTML = '<button class="btn btn-primary" onclick="App.showTagEditor()">+ 新建标签</button>'; await this.renderTags(body); break;
      case 'search': header.textContent = '智能搜索'; actions.innerHTML = ''; await this.renderSearch(body); break;
      case 'admin': header.textContent = '后台管理'; actions.innerHTML = '<button class="btn btn-primary" onclick="App.showAdminUserEditor()">+ 添加用户</button>'; await this.renderAdmin(body); break;
      case 'export': header.textContent = '数据管理'; actions.innerHTML = ''; await this.renderExport(body); break;
    }
    this.updateSidebarStats();
  },

  // === Auth ===
  showLogin() {
    const app = document.getElementById('app');
    app.innerHTML = `
      <div class="login-page">
        <div class="login-card">
          <div class="login-header">
            <div class="login-logo">📚</div>
            <h2>个人知识管理</h2>
            <p style="color:var(--text-secondary);font-size:14px;margin-top:4px">登录以继续</p>
          </div>
          <div class="login-tabs">
            <button class="login-tab active" data-tab="login" onclick="App.switchLoginTab('login')">登录</button>
            <button class="login-tab" data-tab="register" onclick="App.switchLoginTab('register')">注册</button>
          </div>
          <div id="loginForm">
            <div class="form-group">
              <label>用户名</label>
              <input type="text" id="loginUsername" placeholder="输入用户名"/>
            </div>
            <div class="form-group">
              <label>密码</label>
              <input type="password" id="loginPassword" placeholder="输入密码" onkeydown="if(event.key==='Enter')App.doLogin()"/>
            </div>
            <div id="loginError" style="color:#dc2626;font-size:13px;margin-bottom:8px;display:none"></div>
            <button class="btn btn-primary" style="width:100%" onclick="App.doLogin()" id="loginBtn">登录</button>
          </div>
          <div id="registerForm" style="display:none">
            <div class="form-group">
              <label>用户名</label>
              <input type="text" id="regUsername" placeholder="输入用户名"/>
            </div>
            <div class="form-group">
              <label>密码</label>
              <input type="password" id="regPassword" placeholder="输入密码"/>
            </div>
            <div class="form-group">
              <label>确认密码</label>
              <input type="password" id="regPassword2" placeholder="再次输入密码" onkeydown="if(event.key==='Enter')App.doRegister()"/>
            </div>
            <div id="regError" style="color:#dc2626;font-size:13px;margin-bottom:8px;display:none"></div>
            <button class="btn btn-primary" style="width:100%" onclick="App.doRegister()" id="regBtn">注册</button>
          </div>
        </div>
      </div>
    `;
  },

  switchLoginTab(tab) {
    document.querySelectorAll('.login-tab').forEach(el => el.classList.toggle('active', el.dataset.tab === tab));
    document.getElementById('loginForm').style.display = tab === 'login' ? '' : 'none';
    document.getElementById('registerForm').style.display = tab === 'register' ? '' : 'none';
    document.getElementById('loginError').style.display = 'none';
    document.getElementById('regError').style.display = 'none';
  },

  async doLogin() {
    const username = document.getElementById('loginUsername').value.trim();
    const password = document.getElementById('loginPassword').value.trim();
    if (!username || !password) { this.showLoginError('请输入用户名和密码'); return; }
    const btn = document.getElementById('loginBtn');
    btn.disabled = true; btn.textContent = '登录中...';
    try {
      const data = await API.login(username, password);
      API.setToken(data.token);
      this.state.currentUser = data.username;
      this.state.currentRole = data.role || 'user';
      this.toast('登录成功');
      document.getElementById('app').innerHTML = '';
      this.renderLayout();
      await this.loadTags();
      await this.navigate('dashboard');
    } catch (e) {
      this.showLoginError(e.message);
      btn.disabled = false; btn.textContent = '登录';
    }
  },

  async doRegister() {
    const username = document.getElementById('regUsername').value.trim();
    const password = document.getElementById('regPassword').value.trim();
    const password2 = document.getElementById('regPassword2').value.trim();
    if (!username || !password) { this.showLoginError('请填写完整信息', 'reg'); return; }
    if (password !== password2) { this.showLoginError('两次密码不一致', 'reg'); return; }
    if (password.length < 6) { this.showLoginError('密码至少6位', 'reg'); return; }
    const btn = document.getElementById('regBtn');
    btn.disabled = true; btn.textContent = '注册中...';
    try {
      await API.register(username, password);
      this.toast('注册成功，请登录');
      this.switchLoginTab('login');
      document.getElementById('loginUsername').value = username;
    } catch (e) {
      this.showLoginError(e.message, 'reg');
      btn.disabled = false; btn.textContent = '注册';
    }
  },

  logout() {
    API.setToken('');
    this.showLogin();
  },

  // === Admin ===
  async renderAdmin(body) {
    try {
      const users = await API.getAdminUsers();
      body.innerHTML = `
        <div class="card">
          <div class="card-header"><h3>用户管理</h3></div>
          <div class="entry-list">
            ${(users || []).map(u => `
              <div class="entry-item" style="display:flex;align-items:center;gap:12px">
                <div style="flex:1">
                  <strong>${this.esc(u.username)}</strong>
                  <span style="color:var(--text-secondary);font-size:12px;margin-left:8px">${u.role === 'admin' ? '管理员' : '用户'}</span>
                  <span style="color:var(--text-secondary);font-size:12px;margin-left:8px">${this.esc(u.created_at)}</span>
                </div>
                <button class="btn btn-sm" onclick="App.showAdminUserEditor(${u.id},'${this.esc(u.username)}','${this.esc(u.role)}')">编辑</button>
                ${u.role !== 'admin' ? `<button class="btn btn-sm btn-danger" onclick="App.confirmDelete('user',${u.id})">删除</button>` : ''}
              </div>
            `).join('') || '<div class="empty-state"><p>暂无用户</p></div>'}
          </div>
        </div>
      `;
    } catch (e) {
      body.innerHTML = `<div class="empty-state"><p>加载失败: ${this.esc(e.message)}</p></div>`;
    }
  },

  async showAdminUserEditor(id, username, role) {
    const isEdit = !!id;
    document.getElementById('modalContent').innerHTML = `
      <h3>${isEdit ? '编辑用户' : '添加用户'}</h3>
      <div class="form-group"><label>用户名</label><input type="text" id="adminUserUsername" value="${this.esc(username || '')}" ${isEdit ? 'disabled' : ''} placeholder="输入用户名"/></div>
      <div class="form-group"><label>密码${isEdit ? '（留空不修改）' : ''}</label><input type="password" id="adminUserPassword" placeholder="${isEdit ? '留空则不修改密码' : '输入密码'}"/></div>
      <div class="form-group"><label>角色</label>
        <select id="adminUserRole">
          <option value="user" ${role==='user'?'selected':''}>用户</option>
          <option value="admin" ${role==='admin'?'selected':''}>管理员</option>
        </select>
      </div>
      <div class="actions">
        <button class="btn" onclick="App.closeModal()">取消</button>
        <button class="btn btn-primary" onclick="App.saveAdminUser(${id || 'null'})">${isEdit ? '保存' : '创建'}</button>
      </div>
    `;
    this.showModal();
  },

  async saveAdminUser(id) {
    if (id) {
      const password = document.getElementById('adminUserPassword').value.trim();
      const role = document.getElementById('adminUserRole').value;
      const data = {};
      if (password) data.password = password;
      data.role = role;
      try {
        await API.updateAdminUser(id, data);
        this.toast('更新成功');
        this.closeModal();
        this.navigate('admin');
      } catch (e) { this.toast('更新失败: ' + e.message); }
    } else {
      const username = document.getElementById('adminUserUsername').value.trim();
      const password = document.getElementById('adminUserPassword').value.trim();
      const role = document.getElementById('adminUserRole').value;
      if (!username || !password) { this.toast('请填写完整信息'); return; }
      try {
        await API.createAdminUser({ username, password, role });
        this.toast('创建成功');
        this.closeModal();
        this.navigate('admin');
      } catch (e) { this.toast('创建失败: ' + e.message); }
    }
  },

  showLoginError(msg, form) {
    const id = form === 'reg' ? 'regError' : 'loginError';
    const el = document.getElementById(id);
    if (el) { el.textContent = msg; el.style.display = ''; }
  },

  toast(msg, duration) {
    const el = document.getElementById('toast');
    el.textContent = msg;
    el.classList.add('show');
    setTimeout(() => el.classList.remove('show'), duration || 2000);
  },

  // === Dashboard ===
  async renderDashboard(body) {
    try {
      const stats = await API.getStats();
      body.innerHTML = `
        <div class="user-info-bar">
          <span>👤 ${this.esc(this.state.currentUser || '未知')}</span>
          <span>🌐 ${this.esc(window.location.hostname)}</span>
        </div>
        <div class="stats-grid">
          <div class="stat-card"><div class="stat-value">${stats.entry_count}</div><div class="stat-label">知识条目</div></div>
          <div class="stat-card"><div class="stat-value">${stats.record_count}</div><div class="stat-label">日常记录</div></div>
          <div class="stat-card"><div class="stat-value">${stats.tag_count}</div><div class="stat-label">标签</div></div>
          <div class="stat-card"><div class="stat-value">${stats.link_count}</div><div class="stat-label">关联链接</div></div>
          <div class="stat-card"><div class="stat-value">${stats.favorite_count}</div><div class="stat-label">收藏</div></div>
          <div class="stat-card"><div class="stat-value">${stats.share_count||0}</div><div class="stat-label">分享</div></div>
        </div>
        <div class="card">
          <div class="card-header"><h3>最近更新</h3></div>
          <div class="entry-list" id="recentEntries"></div>
        </div>
      `;
      const recentList = document.getElementById('recentEntries');
      if (stats.recent_entries && stats.recent_entries.length > 0) {
        recentList.innerHTML = stats.recent_entries.map(e =>
          `<div class="entry-item" onclick="App.navigate('entries',{detail:${e.id}})"><div class="entry-title">${this.esc(e.title)}</div><div class="entry-meta">${this.esc(e.updated_at)}</div></div>`
        ).join('');
      } else {
        recentList.innerHTML = '<div class="empty-state"><p>暂无条目，开始创建第一条知识吧</p></div>';
      }
    } catch (e) {
      body.innerHTML = `<div class="empty-state"><p>加载失败: ${this.esc(e.message)}</p></div>`;
    }
  },

  // === Entries ===
  async renderEntries(body, params) {
    if (params && params.detail) {
      await this.renderEntryDetail(body, params.detail);
      return;
    }
    try {
      body.innerHTML = `
        <div class="search-bar">
          <input type="text" id="entrySearch" placeholder="搜索知识条目..." onkeyup="App.debounceSearch('entry')"/>
          <select id="entryTypeFilter" onchange="App.loadEntryList()">
            <option value="">全部类型</option>
            <option value="note">笔记</option>
            <option value="skill">技能</option>
            <option value="task">任务</option>
            <option value="idea">想法</option>
            <option value="article">文章</option>
          </select>
          <select id="entryTagFilter" onchange="App.loadEntryList()" style="min-width:200px"><option value="">全部标签</option></select>
          <button class="btn" id="favFilterBtn" onclick="App.toggleFavFilter()">⭐ 收藏</button>
          
          <button class="btn" id="entryBatchToggle" onclick="App.toggleEntryBatchMode()">☑ 批量</button>
        </div>
        <div id="entryBatchToolbar" style="display:none;margin-bottom:12px;display:none;align-items:center;gap:8px;flex-wrap:wrap">
          <span id="entryBatchCount" style="font-size:13px;color:var(--text-secondary)">已选 0 项</span>
          <button class="btn btn-sm" onclick="App.entryBatchAction('archive')">📦 归档</button>
          <button class="btn btn-sm btn-danger" onclick="App.entryBatchAction('delete')">🗑️ 删除</button>
           <select id="entryBatchTagSelect" style="padding:6px 10px;border:1px solid var(--border);border-radius:8px;font-size:13px">
            <option value="">全部标签...</option>
          </select>
          <button class="btn btn-sm" onclick="App.entryBatchAddTag()">🏷️ 添加标签</button>
          <button class="btn btn-sm" onclick="App.toggleEntryBatchMode()">取消</button>
        </div>
        <div class="entry-list" id="entryList"></div>
      `;
      const tagSelect = document.getElementById('entryTagFilter');
      this.appendTagOptions(tagSelect, this.state.tags);
      const batchTagSelect = document.getElementById('entryBatchTagSelect');
      this.appendTagOptions(batchTagSelect, this.state.tags);
      this._entryBatchMode = false;
      this._entrySelectedIds = [];
      await this.loadEntryList();
    } catch (e) {
      body.innerHTML = `<div class="empty-state"><p>加载失败: ${this.esc(e.message)}</p></div>`;
    }
  },

  _entrySearchTimer: null,
  debounceSearch(type) {
    clearTimeout(this._entrySearchTimer);
    this._entrySearchTimer = setTimeout(() => this.loadEntryList(), 300);
  },

    _currentUser: null,
    _currentRole: 'user',
  toggleFavFilter() {
    this._favFilterActive = !this._favFilterActive;
    document.getElementById('favFilterBtn').style.background = this._favFilterActive ? '#fef3c7' : '';
    this.loadEntryList();
  },

  async loadEntryList() {
    const q = document.getElementById('entrySearch')?.value || '';
    const type = document.getElementById('entryTypeFilter')?.value || '';
    const tag = document.getElementById('entryTagFilter')?.value || '';
    const body = document.getElementById('entryList');
    if (!body) return;
    body.innerHTML = '<div class="loading">加载中...</div>';

    const params = {};
    if (q) params.q = q;
    if (type) params.type = type;
    if (tag) params.tag = tag;
    if (this._favFilterActive) params.favorites = '1';

    try {
      const result = await API.getEntries(params);
      const entries = result.list || [];
      if (entries.length === 0) {
        body.innerHTML = '<div class="empty-state"><div class="empty-icon">📝</div><h3>还没有知识条目</h3><p>点击右上角"新建条目"开始记录你的知识</p></div>';
        return;
      }
      body.innerHTML = entries.map(e => {
        const checked = this._entrySelectedIds && this._entrySelectedIds.includes(e.id) ? 'checked' : '';
        const checkbox = this._entryBatchMode
          ? `<input type="checkbox" class="batch-checkbox" data-id="${e.id}" ${checked} onchange="App.entryToggleSelect(${e.id},this.checked)" onclick="event.stopPropagation()"/>`
          : '';
        return `
          <div class="entry-item ${this._entryBatchMode ? 'batch-mode' : ''}" onclick="${this._entryBatchMode ? '' : "App.navigate('entries',{detail:" + e.id + "})"}">
            ${checkbox}
            <div style="flex:1;min-width:0">
              <div class="entry-title">${this.esc(e.title)}</div>
              <div class="entry-summary">${this.esc(e.summary || e.content?.substring(0,120) || '')}</div>
              <div class="entry-meta">
                <span class="entry-type-badge ${e.entry_type}">${this.typeLabel(e.entry_type)}</span>
                ${e.tag_names ? e.tag_names.split(',').map(t => `<span class="tag-item">${this.esc(t)}</span>`).join('') : ''}
                <span>${e.inlink_count || 0} 引用</span>
                ${e.created_by ? `<span>👤 ${this.esc(e.created_by)}</span>` : ''}
                <span>${this.esc(e.updated_at)}</span>
                ${this.shareStatusBadge(e.share_status)}
              </div>
            </div>
          </div>
        `;
      }).join('');
    } catch (e) {
      body.innerHTML = `<div class="empty-state"><p>加载失败: ${this.esc(e.message)}</p></div>`;
    }
  },

  _entryBatchMode: false,
  _entrySelectedIds: [],
  toggleEntryBatchMode() {
    this._entryBatchMode = !this._entryBatchMode;
    this._entrySelectedIds = [];
    const toolbar = document.getElementById('entryBatchToolbar');
    if (toolbar) toolbar.style.display = this._entryBatchMode ? 'flex' : 'none';
    const toggleBtn = document.getElementById('entryBatchToggle');
    if (toggleBtn) toggleBtn.style.background = this._entryBatchMode ? '#e8f0fe' : '';
    this.loadEntryList();
  },
  entryToggleSelect(id, checked) {
    if (checked) {
      if (!this._entrySelectedIds.includes(id)) this._entrySelectedIds.push(id);
    } else {
      this._entrySelectedIds = this._entrySelectedIds.filter(i => i !== id);
    }
    const countEl = document.getElementById('entryBatchCount');
    if (countEl) countEl.textContent = '已选 ' + this._entrySelectedIds.length + ' 项';
  },
  entryBatchAction(action) {
    const ids = this._entrySelectedIds;
    if (ids.length === 0) { this.toast('请先选择条目'); return; }
    const confirmMsg = action === 'delete' ? `确定删除选中的 ${ids.length} 个条目？` : `确定归档选中的 ${ids.length} 个条目？`;
    if (!confirm(confirmMsg)) return;
    API.batchEntries(ids, action).then(() => {
      this.toast('批量操作完成');
      this._entrySelectedIds = [];
      this.toggleEntryBatchMode();
      this.loadEntryList();
    }).catch(e => this.toast('操作失败: ' + e.message));
  },
  entryBatchAddTag() {
    const ids = this._entrySelectedIds;
    if (ids.length === 0) { this.toast('请先选择条目'); return; }
    const tag = document.getElementById('entryBatchTagSelect')?.value;
    if (!tag) { this.toast('请选择标签'); return; }
    API.batchEntries(ids, 'add-tag', { tag }).then(() => {
      this.toast('批量添加标签完成');
      this._entrySelectedIds = [];
      this.toggleEntryBatchMode();
      this.loadEntryList();
    }).catch(e => this.toast('操作失败: ' + e.message));
  },

  async renderEntryDetail(body, id) {
    try {
      const entry = await API.getEntry(id);
      const backlinks = await API.getBacklinks(id);
      const versions = await API.getEntryVersions(id);
      const favEntry = await API.getEntries({ favorites: '1' });
      const isFav = favEntry.list?.some(e => e.id == id);

      const topActions = `
        <div class="detail-actions">
          <button class="btn" onclick="App.navigate('entries')">← 返回</button>
          <button class="btn btn-primary" onclick="App.showEntryEditor(${id})">✏️ 编辑</button>
          <button class="btn" onclick="App.toggleFav(${id}, ${isFav})">${isFav ? '⭐' : '☆'} 收藏</button>
          <button class="btn" onclick="App.shareEntry(${id})">🔗 分享</button>
          <select class="btn export-select" onchange="App.handleExportChange(this,'entry',${id})">
            <option value="">📥 导出</option>
            <option value="md">.md</option>
            <option value="txt">.txt</option>
            <option value="docx">.docx</option>
          </select>
          <button class="btn btn-danger" onclick="App.confirmDelete('entry',${id})">🗑️ 删除</button>
        </div>
      `;

      const hasOutline = (entry.content || '').split('\n').filter(l => /^#{1,4}\s/.test(l)).length >= 2;
      const outlineHtml = this.renderOutline(entry.content || '');
      body.innerHTML = `
        <div class="detail-view">
          <div class="detail-title">${this.esc(entry.title)}</div>
          <div class="detail-meta">
            <span class="entry-type-badge ${entry.entry_type}">${this.typeLabel(entry.entry_type)}</span>
            ${entry.tag_names ? entry.tag_names.split(',').map(t => `<span class="tag-item">${this.esc(t)}</span>`).join('') : ''}
            <span>📅 ${this.esc(entry.updated_at)}</span>
            <span>🔗 ${entry.inlink_count || 0} 被引用 · ${entry.outlink_count || 0} 引用</span>
            ${entry.created_by ? `<span>👤 ${this.esc(entry.created_by)}</span>` : ''}
            ${this.shareStatusBadge(entry.share_status)}
          </div>
          ${topActions}
          <div class="detail-section" id="entryAttachSection"></div>
          <div class="detail-body-with-outline${hasOutline ? ' has-outline' : ''}">
            ${hasOutline ? `<div class="outline-sidebar" id="outlineSidebar"><div class="outline-header"><span class="outline-title">📑 大纲</span><button class="outline-close" onclick="App.toggleOutline()" title="收起大纲">✕</button></div>${outlineHtml}</div>` : ''}
            ${hasOutline ? `<button class="outline-show-btn" id="outlineShowBtn" style="display:none" onclick="App.toggleOutline()" title="显示大纲">📑</button>` : ''}
            <div class="detail-content" style="${hasOutline ? 'flex:1;min-width:0' : ''}">${this.md(entry.content || '')}</div>
          </div>

          <div class="links-section">
            <h4>🔗 关联条目</h4>
            <div class="links-list" id="entryLinks"></div>
            <div style="margin-top:8px"><button class="btn btn-sm" onclick="App.showLinkEditor(${id})">+ 添加关联</button></div>
          </div>

          <div class="links-section">
            <h4>📥 反向链接 (${backlinks.inlinks?.length || 0})</h4>
            <div class="links-list">
              ${(backlinks.inlinks || []).map(e =>
                `<span class="link-chip" onclick="App.navigate('entries',{detail:${e.id}})">${this.esc(e.title)}<span class="link-type">${e.link_type}</span></span>`
              ).join('') || '<span style="font-size:13px;color:var(--text-secondary)">暂无反向链接</span>'}
            </div>
          </div>

          <div class="links-section">
            <h4>📜 版本历史 (${versions.length})</h4>
            <div class="version-timeline">
              ${versions.slice(0,5).map(v => `
                <div class="version-item">
                  <strong>v${v.version}</strong> ${this.esc(v.change_summary || '')}
                  <div style="font-size:12px;color:var(--text-secondary);margin-top:4px">${this.esc(v.created_at)}</div>
                </div>
              `).join('')}
            </div>
          </div>
        </div>
      `;

      const links = await API.getEntryLinks(id);
      document.getElementById('entryLinks').innerHTML = (links || []).map(l =>
        `<span class="link-chip" onclick="App.navigate('entries',{detail:${l.target_id}})">${this.esc(l.target_title)}<span class="link-type">${l.link_type}</span></span>`
      ).join('') || '<span style="font-size:13px;color:var(--text-secondary)">暂无关联</span>';

      this.renderEntryAttachments('entry', id);

    } catch (e) {
      body.innerHTML = `<div class="empty-state"><p>加载失败: ${this.esc(e.message)}</p></div>`;
    }
  },

  // === Entry Editor ===
  async showEntryEditor(id) {
    let entry = { title: '', content: '', summary: '', entry_type: 'note', importance: 0, tags: [] };
    if (id) {
      try {
        const data = await API.getEntry(id);
        entry = { ...data, tags: data.tag_names ? data.tag_names.split(',') : [] };
      } catch (e) { this.toast('加载失败: ' + e.message); return; }
    }
    const isEdit = !!id;

    document.getElementById('pageBody').innerHTML = `
      <div class="editor-fullscreen">
        <div class="editor-body">
          <div class="detail-actions">
            <button class="btn" onclick="App.navigate('entries')">← 返回</button>
            <button class="btn btn-primary" onclick="App.saveEntry(${id || 'null'})">保存</button>
          </div>
          <div class="editor-form-row-multi">
            <div class="editor-form-field-row">
              <label>标题</label>
              <div class="editor-form-input">
                <input type="text" id="entryFormTitle" value="${this.esc(entry.title)}" placeholder="输入标题"/>
              </div>
            </div>
            <div class="editor-form-field-row">
              <label>摘要</label>
              <div class="editor-form-input">
                <input type="text" id="entryFormSummary" value="${this.esc(entry.summary)}" placeholder="简要描述内容"/>
              </div>
            </div>
          </div>
          <div class="editor-form-row">
            <label>内容</label>
            <div class="editor-form-input editor-textarea-group">
              <textarea id="entryFormContent" placeholder="支持 Markdown 格式...">${this.escHtml(entry.content)}</textarea>
            </div>
          </div>
          <div class="editor-form-row-multi">
            <div class="editor-form-field-row">
              <label>类型</label>
              <div class="editor-form-input">
                <select id="entryFormType">
                  ${['note','skill','task','idea','article'].map(t =>
                    `<option value="${t}" ${entry.entry_type===t?'selected':''}>${this.typeLabel(t)}</option>`
                  ).join('')}
                </select>
              </div>
            </div>
            <div class="editor-form-field-row">
              <label>重要性</label>
              <div class="editor-form-input">
                <select id="entryFormImportance">
                  ${[0,1,2,3,4,5].map(v =>
                    `<option value="${v}" ${entry.importance==v?'selected':''}>${'★'.repeat(v)}${'☆'.repeat(5-v)}</option>`
                  ).join('')}
                </select>
              </div>
            </div>
          </div>
          <div class="editor-form-row-multi">
            <div class="editor-form-field-row">
              <label>标签</label>
              <div class="editor-form-input">
                <div class="tag-input-container" id="tagInputContainer" onclick="document.getElementById('tagInputField').focus()">
                  <div id="tagChips"></div>
                  <input type="text" id="tagInputField" placeholder="输入标签后回车..." onkeydown="App.handleTagKeydown(event)"/>
                </div>
                <div id="tagSuggestions" style="font-size:12px;color:var(--text-secondary);margin-top:4px"></div>
              </div>
            </div>
            <div class="editor-form-field-row">
              <label>附件</label>
              <div class="editor-form-input">
                <input type="file" id="entryFormAttachments" multiple style="font-size:13px"/>
                <div id="entryFormAttachList" style="font-size:12px;color:var(--text-secondary);margin-top:4px"></div>
              </div>
            </div>
          </div>
        </div>
      </div>
    `;

    App._editTags = entry.tags || [];
    App._entryAttachFiles = [];
    document.getElementById('entryFormAttachments').onchange = function() {
      App._entryAttachFiles = Array.from(this.files);
      const el = document.getElementById('entryFormAttachList');
      el.textContent = App._entryAttachFiles.map(f => '📎 ' + f.name).join(' | ');
    };
    App.renderTagChips();
  },

  _editTags: [],
  renderTagChips() {
    const container = document.getElementById('tagChips');
    if (!container) return;
    container.innerHTML = this._editTags.map(t =>
      `<span class="tag-chip">${this.esc(t)}<span class="tag-remove" onclick="App.removeTag('${this.esc(t)}')">×</span></span>`
    ).join('');
    const suggestions = document.getElementById('tagSuggestions');
    if (suggestions) {
      const used = new Set(this._editTags);
      const avail = this.state.tags.filter(t => !used.has(t.name)).map(t => t.name);
      suggestions.textContent = avail.length > 0 ? '建议标签: ' + avail.join(', ') : '';
    }
  },

  handleTagKeydown(e) {
    if (e.key === 'Enter') {
      e.preventDefault();
      const val = e.target.value.trim();
      if (val && !this._editTags.includes(val)) {
        this._editTags.push(val);
        this.renderTagChips();
      }
      e.target.value = '';
    }
  },

  removeTag(tag) {
    this._editTags = this._editTags.filter(t => t !== tag);
    this.renderTagChips();
  },

  async saveEntry(id) {
    const title = document.getElementById('entryFormTitle').value.trim();
    if (!title) { this.toast('请输入标题'); return; }
    const data = {
      title,
      content: document.getElementById('entryFormContent').value,
      summary: document.getElementById('entryFormSummary').value,
      entry_type: document.getElementById('entryFormType').value,
      importance: parseInt(document.getElementById('entryFormImportance').value),
      tags: this._editTags
    };
    try {
      if (id) {
        data.change_summary = '更新内容';
        await API.updateEntry(id, data);
        this.toast('保存成功');
      } else {
        const result = await API.createEntry(data);
        id = result.id;
        this.toast('创建成功');
      }
      if (this._entryAttachFiles && this._entryAttachFiles.length > 0) {
        this.toast('正在上传附件...');
        for (const file of this._entryAttachFiles) {
          try { await API.uploadAttachment('entry', id, file); } catch (e) { console.error('附件上传失败', e); }
        }
        this._entryAttachFiles = [];
      }
      this.navigate('entries');
    } catch (e) {
      this.toast('保存失败: ' + e.message);
    }
  },

  // === Link Editor ===
  async showLinkEditor(sourceId) {
    const entries = await API.getEntries({ pageSize: 100 });
    const list = entries.list || [];
    document.getElementById('modalContent').innerHTML = `
      <h3>添加关联</h3>
      <div class="form-group">
        <label>选择关联条目</label>
        <select id="linkTargetSelect" size="8" style="height:auto;min-height:200px">
          ${list.map(e =>
            `<option value="${e.id}">${this.esc(e.title)} (${this.typeLabel(e.entry_type)})</option>`
          ).join('')}
        </select>
      </div>
      <div class="form-group">
        <label>关联类型</label>
        <select id="linkTypeSelect">
          <option value="reference">引用</option>
          <option value="related">相关</option>
          <option value="dependency">依赖</option>
        </select>
      </div>
      <div class="actions">
        <button class="btn" onclick="App.closeModal()">取消</button>
        <button class="btn btn-primary" onclick="App.saveLink(${sourceId})">添加</button>
      </div>
    `;
    this.showModal();
  },

  async saveLink(sourceId) {
    const targetId = document.getElementById('linkTargetSelect').value;
    const linkType = document.getElementById('linkTypeSelect').value;
    if (!targetId) { this.toast('请选择条目'); return; }
    if (parseInt(targetId) === sourceId) { this.toast('不能关联到自身'); return; }
    try {
      await API.createEntryLink(sourceId, { target_id: parseInt(targetId), link_type: linkType });
      this.toast('关联添加成功');
      this.closeModal();
      this.navigate('entries', { detail: sourceId });
    } catch (e) {
      this.toast('添加失败: ' + e.message);
    }
  },

  // === Favorites ===
  async toggleFav(id, isFav) {
    try {
      await API.toggleFavorite(id, !isFav);
      this.toast(isFav ? '已取消收藏' : '已收藏');
      this.navigate('entries', { detail: id });
    } catch (e) { this.toast('操作失败'); }
  },


  async shareEntry(id) {
    try {
      const status = await API.getShareStatus(id);
      if (status && status.share_token) {
        this.showShareDialog(id, status.share_token, status.share_url);
      } else {
        const result = await API.shareEntry(id);
        this.showShareDialog(id, result.share_token, result.share_url);
        this.toast('分享已开启');
      }
    } catch (e) { this.toast('分享操作失败'); }
  },

  showShareDialog(id, token, shareUrl) {
    document.getElementById('modalContent').innerHTML = `
      <div class="share-dialog">
        <h3>分享知识条目</h3>
        <div class="share-url-box">
          <input type="text" value="${shareUrl}" readonly id="shareUrl" class="share-url-input"/>
          <button onclick="App.copyShareUrl()" class="btn-copy">复制链接</button>
        </div>
        <div class="share-actions">
          <button onclick="App.disableShare(${id})" class="btn-disable">停用链接</button>
          <button onclick="App.closeModal()" class="btn-close">关闭</button>
        </div>
      </div>
    `;
    this.showModal();
  },

  copyShareUrl() {
    const input = document.getElementById('shareUrl');
    if (input) {
      input.select();
      navigator.clipboard.writeText(input.value).then(() => {
        this.toast('链接已复制到剪贴板');
      }).catch(() => {
        document.execCommand('copy');
        this.toast('链接已复制');
      });
    }
  },

  async disableShare(id) {
    try {
      await API.disableShare(id);
      this.toast('分享已停用');
      this.closeModal();
    } catch (e) { this.toast('操作失败'); }
  },

  // === Records ===
  async renderRecords(body, params) {
    if (params && params.detail) {
      await this.renderRecordDetail(body, params.detail);
      return;
    }
    try {
      body.innerHTML = `
        <div class="search-bar">
          <input type="text" id="recordSearch" placeholder="搜索日常记录..." onkeyup="App.debounceRecordSearch()"/>
          <select id="recordTypeFilter" onchange="App.loadRecordList()">
            <option value="">全部类型</option>
            <option value="daily">日记</option>
            <option value="meeting">会议</option>
            <option value="idea">想法</option>
            <option value="note">笔记</option>
            <option value="task">任务</option>
          </select>
          <select id="recordTagFilter" onchange="App.loadRecordList()" style="min-width:200px"><option value="">全部标签</option></select>
          <button class="btn" id="recordBatchToggle" onclick="App.toggleRecordBatchMode()">☑ 批量</button>
        </div>
        <div id="recordBatchToolbar" style="display:none;margin-bottom:12px;align-items:center;gap:8px;flex-wrap:wrap">
          <span id="recordBatchCount" style="font-size:13px;color:var(--text-secondary)">已选 0 项</span>
          <button class="btn btn-sm" onclick="App.recordBatchAction('archive')">📦 归档</button>
          <button class="btn btn-sm btn-danger" onclick="App.recordBatchAction('delete')">🗑️ 删除</button>
           <select id="recordBatchTagSelect" style="padding:6px 10px;border:1px solid var(--border);border-radius:8px;font-size:13px">
            <option value="">全部标签...</option>
          </select>
          <button class="btn btn-sm" onclick="App.recordBatchAddTag()">🏷️ 添加标签</button>
          <button class="btn btn-sm" onclick="App.toggleRecordBatchMode()">取消</button>
        </div>
        <div class="entry-list" id="recordList"></div>
      `;
      const batchTagSelect = document.getElementById('recordBatchTagSelect');
      this.appendTagOptions(batchTagSelect, this.state.tags);
      const recordTagFilter = document.getElementById('recordTagFilter');
      this.appendTagOptions(recordTagFilter, this.state.tags);
      this._recordBatchMode = false;
      this._recordSelectedIds = [];
      await this.loadRecordList();
    } catch (e) {
      body.innerHTML = `<div class="empty-state"><p>加载失败: ${this.esc(e.message)}</p></div>`;
    }
  },

  _recordSearchTimer: null,
  debounceRecordSearch() {
    clearTimeout(this._recordSearchTimer);
    this._recordSearchTimer = setTimeout(() => this.loadRecordList(), 300);
  },

  async loadRecordList() {
    const q = document.getElementById('recordSearch')?.value || '';
    const type = document.getElementById('recordTypeFilter')?.value || '';
    const tag = document.getElementById('recordTagFilter')?.value || '';
    const body = document.getElementById('recordList');
    if (!body) return;
    body.innerHTML = '<div class="loading">加载中...</div>';
    const params = {};
    if (q) params.q = q;
    if (type) params.type = type;
    if (tag) params.tag = tag;
    try {
      const result = await API.getRecords(params);
      const records = result.list || [];
      if (records.length === 0) {
        body.innerHTML = '<div class="empty-state"><div class="empty-icon">📋</div><h3>还没有日常记录</h3><p>点击右上角"新建记录"开始记录工作</p></div>';
        return;
      }
      body.innerHTML = records.map(r => {
        const checked = this._recordSelectedIds && this._recordSelectedIds.includes(r.id) ? 'checked' : '';
        const checkbox = this._recordBatchMode
          ? `<input type="checkbox" class="batch-checkbox" data-id="${r.id}" ${checked} onchange="App.recordToggleSelect(${r.id},this.checked)" onclick="event.stopPropagation()"/>`
          : '';
        return `
          <div class="entry-item ${this._recordBatchMode ? 'batch-mode' : ''}" onclick="${this._recordBatchMode ? '' : "App.navigate('records',{detail:" + r.id + "})"}">
            ${checkbox}
            <div style="flex:1;min-width:0">
              <div class="entry-title">${this.esc(r.title)}</div>
              <div class="entry-summary">${this.esc((r.content || '').substring(0,120))}</div>
              <div class="entry-meta">
                <span class="entry-type-badge ${r.record_type}">${this.recordTypeLabel(r.record_type)}</span>
                ${r.tag_names ? r.tag_names.split(',').map(t => `<span class="tag-item">${this.esc(t)}</span>`).join('') : ''}
                ${r.created_by ? `<span>👤 ${this.esc(r.created_by)}</span>` : ''}
                <span>📅 ${this.esc(r.updated_at)}</span>
              </div>
            </div>
          </div>
        `;
      }).join('');
    } catch (e) {
      body.innerHTML = `<div class="empty-state"><p>加载失败: ${this.esc(e.message)}</p></div>`;
    }
  },

  _recordBatchMode: false,
  _recordSelectedIds: [],
  toggleRecordBatchMode() {
    this._recordBatchMode = !this._recordBatchMode;
    this._recordSelectedIds = [];
    const toolbar = document.getElementById('recordBatchToolbar');
    if (toolbar) toolbar.style.display = this._recordBatchMode ? 'flex' : 'none';
    const toggleBtn = document.getElementById('recordBatchToggle');
    if (toggleBtn) toggleBtn.style.background = this._recordBatchMode ? '#e8f0fe' : '';
    this.loadRecordList();
  },
  recordToggleSelect(id, checked) {
    if (checked) {
      if (!this._recordSelectedIds.includes(id)) this._recordSelectedIds.push(id);
    } else {
      this._recordSelectedIds = this._recordSelectedIds.filter(i => i !== id);
    }
    const countEl = document.getElementById('recordBatchCount');
    if (countEl) countEl.textContent = '已选 ' + this._recordSelectedIds.length + ' 项';
  },
  recordBatchAction(action) {
    const ids = this._recordSelectedIds;
    if (ids.length === 0) { this.toast('请先选择记录'); return; }
    const confirmMsg = action === 'delete' ? `确定删除选中的 ${ids.length} 条记录？` : `确定归档选中的 ${ids.length} 条记录？`;
    if (!confirm(confirmMsg)) return;
    API.batchRecords(ids, action).then(() => {
      this.toast('批量操作完成');
      this._recordSelectedIds = [];
      this.toggleRecordBatchMode();
      this.loadRecordList();
    }).catch(e => this.toast('操作失败: ' + e.message));
  },
  recordBatchAddTag() {
    const ids = this._recordSelectedIds;
    if (ids.length === 0) { this.toast('请先选择记录'); return; }
    const tag = document.getElementById('recordBatchTagSelect')?.value;
    if (!tag) { this.toast('请选择标签'); return; }
    API.batchRecords(ids, 'add-tag', { tag }).then(() => {
      this.toast('批量添加标签完成');
      this._recordSelectedIds = [];
      this.toggleRecordBatchMode();
      this.loadRecordList();
    }).catch(e => this.toast('操作失败: ' + e.message));
  },

  async renderRecordDetail(body, id) {
    try {
      const record = await API.getRecord(id);
      const topActions = `
        <div class="detail-actions">
          <button class="btn" onclick="App.navigate('records')">← 返回</button>
          <button class="btn btn-primary" onclick="App.showRecordEditor(${id})">✏️ 编辑</button>
          <select class="btn export-select" onchange="App.handleExportChange(this,'record',${id})">
            <option value="">📥 导出</option>
            <option value="md">.md</option>
            <option value="txt">.txt</option>
            <option value="docx">.docx</option>
          </select>
          <button class="btn btn-sm" onclick="App.convertRecordToEntry(${id})">📝 转为知识条目</button>
          <button class="btn btn-danger" onclick="App.confirmDelete('record',${id})">🗑️ 删除</button>
        </div>
      `;
      const hasOutline = (record.content || '').split('\n').filter(l => /^#{1,4}\s/.test(l)).length >= 2;
      const outlineHtml = this.renderOutline(record.content || '');
      body.innerHTML = `
        <div class="detail-view">
          <div class="detail-title">${this.esc(record.title)}</div>
          <div class="detail-meta">
            <span class="entry-type-badge ${record.record_type}">${this.recordTypeLabel(record.record_type)}</span>
            ${record.tag_names ? record.tag_names.split(',').map(t => `<span class="tag-item">${this.esc(t)}</span>`).join('') : ''}
            ${record.created_by ? `<span>👤 ${this.esc(record.created_by)}</span>` : ''}
            <span>📅 ${this.esc(record.updated_at)}</span>
          </div>
          ${topActions}
          <div class="detail-section" id="recordAttachSection"></div>
          <div class="detail-body-with-outline${hasOutline ? ' has-outline' : ''}">
            ${hasOutline ? `<div class="outline-sidebar" id="outlineSidebar"><div class="outline-header"><span class="outline-title">📑 大纲</span><button class="outline-close" onclick="App.toggleOutline()" title="收起大纲">✕</button></div>${outlineHtml}</div>` : ''}
            ${hasOutline ? `<button class="outline-show-btn" id="outlineShowBtn" style="display:none" onclick="App.toggleOutline()" title="显示大纲">📑</button>` : ''}
            <div class="detail-content" style="${hasOutline ? 'flex:1;min-width:0' : ''}">${this.md(record.content || '')}</div>
          </div>
        </div>
      `;
      this.renderRecordAttachments(id);
    } catch (e) {
      body.innerHTML = `<div class="empty-state"><p>加载失败: ${this.esc(e.message)}</p></div>`;
    }
  },

  renderAttachments(containerId, sourceType, sourceId, canDelete) {
    const container = document.getElementById(containerId);
    if (!container) return;
    API.getAttachments(sourceType, sourceId).then(attachList => {
      if (!attachList || attachList.length === 0) return;
      container.innerHTML = `
        <div class="links-section">
          <h4>📎 附件 (${attachList.length})</h4>
          <div class="attach-list">
            ${attachList.map(a => `
              <div class="attach-item">
                <span class="attach-name">📄 ${this.esc(a.filename)}</span>
                <span class="attach-size">${this.formatSize(a.file_size)}</span>
                ${canDelete ? `<button class="btn btn-sm btn-danger" onclick="App.deleteAttachment(${a.id},'${sourceType}',${sourceId})">删除</button>` : ''}
              </div>
            `).join('')}
          </div>
        </div>
      `;
    }).catch(() => {});
  },

  renderEntryAttachments(id) {
    this.renderAttachments('entryAttachSection', 'entry', id, true);
  },

  renderRecordAttachments(id) {
    this.renderAttachments('recordAttachSection', 'record', id, true);
  },

  async deleteAttachment(id, sourceType, sourceId) {
    if (!confirm('确定删除此附件？')) return;
    try {
      await API.deleteAttachment(id);
      this.toast('附件已删除');
      if (sourceType === 'entry') this.renderEntryAttachments(sourceId);
      else this.renderRecordAttachments(sourceId);
    } catch (e) { this.toast('删除失败: ' + e.message); }
  },

  formatSize(bytes) {
    if (!bytes) return '';
    if (bytes < 1024) return bytes + 'B';
    if (bytes < 1048576) return (bytes / 1024).toFixed(1) + 'KB';
    return (bytes / 1048576).toFixed(1) + 'MB';
  },

  // === Record Editor ===
  async showRecordEditor(id) {
    let record = { title: '', content: '', record_type: 'daily', importance: 0, tags: [] };
    if (id) {
      try {
        const data = await API.getRecord(id);
        record = { ...data, tags: data.tag_names ? data.tag_names.split(',') : [] };
      } catch (e) { this.toast('加载失败'); return; }
    }

    document.getElementById('pageBody').innerHTML = `
      <div class="editor-fullscreen">
        <div class="editor-body">
          <div class="detail-actions">
            <button class="btn" onclick="App.navigate('records')">← 返回</button>
            <button class="btn btn-primary" onclick="App.saveRecord(${id || 'null'})">保存</button>
          </div>
          <div class="editor-form-row-multi">
            <div class="editor-form-field-row">
              <label>标题</label>
              <div class="editor-form-input">
                <input type="text" id="recordFormTitle" value="${this.esc(record.title)}"/>
              </div>
            </div>
          </div>
          <div class="editor-form-row">
            <label>内容</label>
            <div class="editor-form-input editor-textarea-group">
              <textarea id="recordFormContent" placeholder="输入内容...">${this.escHtml(record.content)}</textarea>
            </div>
          </div>
          <div class="editor-form-row-multi">
            <div class="editor-form-field-row">
              <label>类型</label>
              <div class="editor-form-input">
                <select id="recordFormType">
                  ${['daily','meeting','idea','note','task'].map(t =>
                    `<option value="${t}" ${record.record_type===t?'selected':''}>${this.recordTypeLabel(t)}</option>`
                  ).join('')}
                </select>
              </div>
            </div>
            <div class="editor-form-field-row">
              <label>重要性</label>
              <div class="editor-form-input">
                <select id="recordFormImportance">
                  ${[0,1,2,3,4,5].map(v =>
                    `<option value="${v}" ${record.importance==v?'selected':''}>${'★'.repeat(v)}${'☆'.repeat(5-v)}</option>`
                  ).join('')}
                </select>
              </div>
            </div>
          </div>
          <div class="editor-form-row-multi">
            <div class="editor-form-field-row">
              <label>标签</label>
              <div class="editor-form-input">
                <div class="tag-input-container" onclick="document.getElementById('recordTagField').focus()">
                  <div id="recordTagChips"></div>
                  <input type="text" id="recordTagField" placeholder="输入标签后回车..." onkeydown="App.handleRecordTagKeydown(event)"/>
                </div>
                <div id="recordTagSuggestions" style="font-size:12px;color:var(--text-secondary);margin-top:4px"></div>
              </div>
            </div>
            <div class="editor-form-field-row">
              <label>附件</label>
              <div class="editor-form-input">
                <input type="file" id="recordFormAttachments" multiple style="font-size:13px"/>
                <div id="recordFormAttachList" style="font-size:12px;color:var(--text-secondary);margin-top:4px"></div>
              </div>
            </div>
          </div>
        </div>
      </div>
    `;
    App._recordTags = record.tags || [];
    App._recordAttachFiles = [];
    document.getElementById('recordFormAttachments').onchange = function() {
      App._recordAttachFiles = Array.from(this.files);
      const el = document.getElementById('recordFormAttachList');
      el.textContent = App._recordAttachFiles.map(f => '📎 ' + f.name).join(' | ');
    };
    App.renderRecordTagChips();
  },

  _recordTags: [],
  renderRecordTagChips() {
    const container = document.getElementById('recordTagChips');
    if (!container) return;
    container.innerHTML = this._recordTags.map(t =>
      `<span class="tag-chip">${this.esc(t)}<span class="tag-remove" onclick="App.removeRecordTag('${this.esc(t)}')">×</span></span>`
    ).join('');
    const suggestions = document.getElementById('recordTagSuggestions');
    if (suggestions) {
      const used = new Set(this._recordTags);
      const avail = this.state.tags.filter(t => !used.has(t.name)).map(t => t.name);
      suggestions.textContent = avail.length > 0 ? '建议标签: ' + avail.join(', ') : '';
    }
  },

  handleRecordTagKeydown(e) {
    if (e.key === 'Enter') {
      e.preventDefault();
      const val = e.target.value.trim();
      if (val && !this._recordTags.includes(val)) {
        this._recordTags.push(val);
        this.renderRecordTagChips();
      }
      e.target.value = '';
    }
  },

  removeRecordTag(tag) {
    this._recordTags = this._recordTags.filter(t => t !== tag);
    this.renderRecordTagChips();
  },

  async saveRecord(id) {
    const title = document.getElementById('recordFormTitle').value.trim();
    if (!title) { this.toast('请输入标题'); return; }
    const data = {
      title,
      content: document.getElementById('recordFormContent').value,
      record_type: document.getElementById('recordFormType').value,
      importance: parseInt(document.getElementById('recordFormImportance').value),
      tags: this._recordTags
    };
    try {
      if (id) {
        await API.updateRecord(id, data);
        this.toast('保存成功');
      } else {
        const result = await API.createRecord(data);
        id = result.id;
        this.toast('创建成功');
      }
      if (this._recordAttachFiles && this._recordAttachFiles.length > 0) {
        this.toast('正在上传附件...');
        for (const file of this._recordAttachFiles) {
          try { await API.uploadAttachment('record', id, file); } catch (e) { console.error('附件上传失败', e); }
        }
        this._recordAttachFiles = [];
      }
      this.navigate('records');
    } catch (e) {
      this.toast('保存失败: ' + e.message);
    }
  },

  // === Tags ===
  appendTagOptions(select, tags) {
    if (!select || !tags) return;
    const byId = {};
    tags.forEach(t => byId[t.id] = t);
    const roots = tags.filter(t => !t.parent_id || !byId[t.parent_id]);
    const build = (t, depth) => {
      const subs = tags.filter(c => c.parent_id === t.id);
      const hasSub = subs.length > 0;
      const o = document.createElement('option');
      o.value = t.name;
      o.textContent = (hasSub ? '▾ ' : '　'.repeat(depth) + '└ ') + (hasSub ? t.name + '（分组）' : t.name);
      select.appendChild(o);
      subs.forEach(c => build(c, depth + 1));
    };
    roots.forEach(r => build(r, 0));
  },

  async renderTags(body) {
    try {
      const tags = this.state.tags;
      const byId = {};
      tags.forEach(t => byId[t.id] = t);
      const parents = tags.filter(t => !t.parent_id);
      const children = tags.filter(t => t.parent_id);
      body.innerHTML = `
        <div class="card">
          <div class="entry-list">
            ${tags.length === 0 ? '<div class="empty-state"><p>暂无标签</p></div>' : ''}
            ${parents.map(t => `
              ${this.renderTagRow(t, 0, byId, children)}
            `).join('')}
            ${tags.length > 0 && parents.length > 0 ? '' : ''}
            ${tags.filter(t => t.parent_id && !byId[t.parent_id]).map(t => this.renderTagRow(t, 0, byId, children)).join('')}
          </div>
        </div>
      `;
    } catch (e) {
      body.innerHTML = `<div class="empty-state"><p>加载失败</p></div>`;
    }
  },

  renderTagRow(t, depth, byId, children) {
    const sub = children.filter(c => c.parent_id === t.id);
    const isGroup = sub.length > 0;
    const collapsed = this.collapsedTags && this.collapsedTags.has(t.id);
    const subHtml = sub.map(c => this.renderTagRow(c, depth + 1, byId, children)).join('');
    return `
      <div class="entry-item" style="display:flex;align-items:center;gap:12px;padding-left:${8 + depth * 28}px">
        ${isGroup
          ? `<button class="tag-toggle" title="折叠/展开" onclick="App.toggleTagChildren(this, ${t.id})">${collapsed ? '▸' : '▾'}</button>`
          : (depth > 0 ? '<span style="color:var(--text-secondary);width:22px;text-align:center">└</span>' : '')}
        <span style="width:12px;height:12px;border-radius:50%;background:${t.color};flex-shrink:0"></span>
        <div style="flex:1"><strong>${this.esc(t.name)}</strong>
          <span style="color:var(--text-secondary)">${isGroup ? `（分组 · ${sub.length} 个子标签）` : ''} (${t.entry_count || 0} 条目)</span>
        </div>
        <button class="btn btn-sm" onclick="App.showTagEditor(${t.id})">编辑</button>
        <button class="btn btn-sm btn-danger" onclick="App.confirmDelete('tag',${t.id})">删除</button>
      </div>
      ${isGroup ? `<div id="tag-children-${t.id}"${collapsed ? ' style="display:none"' : ''}>${subHtml}</div>` : ''}
    `;
  },

  toggleTagChildren(btn, id) {
    const el = document.getElementById('tag-children-' + id);
    if (!el) return;
    if (!this.collapsedTags) this.collapsedTags = new Set();
    const hidden = el.style.display === 'none';
    el.style.display = hidden ? '' : 'none';
    if (btn) btn.textContent = hidden ? '▾' : '▸';
    if (hidden) this.collapsedTags.delete(id); else this.collapsedTags.add(id);
  },

  async showTagEditor(id, name, color) {
    const self = this;
    const tags = this.state.tags;
    let current = null;
    if (id) current = tags.find(t => t.id === id) || {};
    const nameVal = name !== undefined && name !== null ? name : (current.name || '');
    const colorVal = color || current.color || '#1a73e8';
    const curParent = current.parent_id || 0;
    const lockedIds = new Set([id || 0]);
    tags.forEach(t => { if (t.parent_id === (id || 0)) lockedIds.add(t.id); });
    const options = `<option value="0">（无）顶级标签</option>` +
      tags.filter(t => !lockedIds.has(t.id)).map(t => {
        const pName = t.parent_id ? (tags.find(p => p.id === t.parent_id) || {}).name : '';
        return `<option value="${t.id}" ${t.id === curParent ? 'selected' : ''}>${this.esc(pName ? pName + ' / ' : '')}${this.esc(t.name)}</option>`;
      }).join('');
    document.getElementById('modalContent').innerHTML = `
      <h3>${id ? '编辑标签' : '新建标签'}</h3>
      <div class="form-group"><label>名称</label><input type="text" id="tagFormName" value="${this.esc(nameVal)}"/></div>
      <div class="form-group"><label>颜色</label><input type="color" id="tagFormColor" value="${colorVal}" style="height:40px;padding:4px"/></div>
      <div class="form-group"><label>父标签（分组）</label>
        <select id="tagFormParent">${options}</select>
      </div>
      <div class="actions">
        <button class="btn" onclick="App.closeModal()">取消</button>
        <button class="btn btn-primary" onclick="App.saveTag(${id || 'null'})">保存</button>
      </div>
    `;
    this.showModal();
  },

  async saveTag(id) {
    const name = document.getElementById('tagFormName').value.trim();
    const color = document.getElementById('tagFormColor').value;
    const parentId = parseInt(document.getElementById('tagFormParent').value) || 0;
    if (!name) { this.toast('请输入名称'); return; }
    try {
      if (id) await API.updateTag(id, { name, color, parent_id: parentId });
      else await API.createTag({ name, color, parent_id: parentId });
      this.toast(id ? '更新成功' : '创建成功');
      this.closeModal();
      await this.loadTags();
      this.navigate('tags');
    } catch (e) { this.toast('操作失败: ' + e.message); }
  },

  async loadTags() {
    try {
      this.state.tags = await API.getTags() || [];
    } catch (e) { this.state.tags = []; }
  },

  // === Knowledge Graph ===
  async renderGraph(body) {
    body.innerHTML = '<div class="graph-container" id="graphContainer"><div class="loading">加载知识图谱...</div></div>';
    try {
      const data = await API.getGraph();
      const nodes = data.nodes || [];
      const edges = data.edges || [];
      if (nodes.length === 0) {
        document.getElementById('graphContainer').innerHTML = '<div class="empty-state"><div class="empty-icon">🔗</div><h3>暂无数据</h3><p>创建知识条目并建立关联后，图谱将在此显示</p></div>';
        return;
      }
      let savedLayout = null;
      try { savedLayout = await API.getGraphLayout(); } catch (e) {}
      this.renderGraphCanvas(nodes, edges, savedLayout || []);
    } catch (e) {
      document.getElementById('graphContainer').innerHTML = `<div class="empty-state"><p>加载失败: ${this.esc(e.message)}</p></div>`;
    }
  },

  renderGraphCanvas(nodes, edges, savedLayout) {
    const container = document.getElementById('graphContainer');
    const w = container.clientWidth || 800;
    const h = container.clientHeight || 500;
    container.innerHTML = '';

    const canvas = document.createElement('canvas');
    canvas.width = w;
    canvas.height = h;
    container.appendChild(canvas);
    const ctx = canvas.getContext('2d');

    const typeColors = { note: '#1a73e8', skill: '#d97706', task: '#059669', idea: '#7c3aed', article: '#db2777' };
    // 世界坐标护栏：布局可保存/恢复的有效范围 ±WORLD_GUARD（画布红色虚线框同步显示，拖拽钳制于此）
    const WORLD_GUARD = 5000;
    const clampWorld = v => Math.max(-WORLD_GUARD, Math.min(WORLD_GUARD, v));
    const savedMap = {};
    (savedLayout || []).forEach(p => { savedMap[p.entry_id] = p; });
    const nodeMap = {};
    const positions = [];
    nodes.forEach((n, i) => {
      const angle = (2 * Math.PI * i) / nodes.length;
      const radius = Math.min(w, h) * 0.35;
      const saved = savedMap[n.id];
      let x, y;
      // 负坐标合法；仅拒绝非数值或超出护栏的异常值，避免误回落环形布局
      if (saved && Number.isFinite(+saved.x) && Number.isFinite(+saved.y)
          && Math.abs(+saved.x) <= WORLD_GUARD && Math.abs(+saved.y) <= WORLD_GUARD) {
        x = +saved.x; y = +saved.y;
      } else {
        x = w / 2 + radius * Math.cos(angle);
        y = h / 2 + radius * Math.sin(angle);
      }
      positions.push({
        id: n.id, title: n.title, entry_type: n.entry_type,
        x, y
      });
      nodeMap[n.id] = positions[i];
    });

    // 视图相机：屏幕坐标 = 世界坐标 * scale + offset。
    // scale=1 / offset=0 时与旧版像素坐标完全一致，故旧布局数据直接兼容。
    let cam = { x: 0, y: 0, scale: 1 };
    try {
      const v = JSON.parse(localStorage.getItem('kb_graph_view'));
      if (v && isFinite(v.x) && isFinite(v.y) && v.scale >= 0.2 && v.scale <= 3) cam = v;
    } catch (e) {}
    const persistView = () => { try { localStorage.setItem('kb_graph_view', JSON.stringify(cam)); } catch (e) {} };
    const toScreenX = wx => wx * cam.scale + cam.x;
    const toScreenY = wy => wy * cam.scale + cam.y;
    const toWorldX = sx => (sx - cam.x) / cam.scale;
    const toWorldY = sy => (sy - cam.y) / cam.scale;

    let dragging = null, offsetX = 0, offsetY = 0;
    let panning = false, panStartX = 0, panStartY = 0, panCamX = 0, panCamY = 0;
    // 框选与组拖拽（v1.7.0）
    const selected = new Set();               // 当前选中的节点集合
    let selecting = false, selStartX = 0, selStartY = 0, selCurX = 0, selCurY = 0;
    let groupDrag = null;                     // { startWX, startWY, origins: Map<p,{x,y}> }

    function draw() {
      ctx.clearRect(0, 0, w, h);
      ctx.save();
      ctx.translate(cam.x, cam.y);
      ctx.scale(cam.scale, cam.scale);
      ctx.lineWidth = 1.5 / cam.scale;

      // 世界坐标护栏边界（±WORLD_GUARD）：红色虚线框，随缩放/平移变换
      ctx.setLineDash([12 / cam.scale, 8 / cam.scale]);
      ctx.strokeStyle = 'rgba(220,38,38,0.5)';
      ctx.lineWidth = 2 / cam.scale;
      ctx.strokeRect(-WORLD_GUARD, -WORLD_GUARD, WORLD_GUARD * 2, WORLD_GUARD * 2);
      ctx.setLineDash([]);
      // 边框角标文字（屏幕尺寸恒定）
      ctx.fillStyle = 'rgba(220,38,38,0.75)';
      ctx.font = (12 / cam.scale) + 'px sans-serif';
      ctx.textAlign = 'left';
      ctx.fillText('布局边界 ±' + WORLD_GUARD, -WORLD_GUARD + 10 / cam.scale, -WORLD_GUARD + 18 / cam.scale);

      edges.forEach(e => {
        const s = nodeMap[e.source_id];
        const t = nodeMap[e.target_id];
        if (!s || !t) return;
        ctx.beginPath();
        ctx.moveTo(s.x, s.y);
        ctx.lineTo(t.x, t.y);
        ctx.strokeStyle = e.link_type === 'dependency' ? '#dc2626' : e.link_type === 'related' ? '#059669' : '#9ca3af';
        ctx.stroke();
      });

      positions.forEach(p => {
        if (selected.has(p)) {
          // 选中高亮环
          ctx.beginPath();
          ctx.arc(p.x, p.y, 11, 0, Math.PI * 2);
          ctx.strokeStyle = '#1a73e8';
          ctx.lineWidth = 2.5 / cam.scale;
          ctx.stroke();
        }
        ctx.beginPath();
        ctx.arc(p.x, p.y, 8, 0, Math.PI * 2);
        ctx.fillStyle = typeColors[p.entry_type] || '#9ca3af';
        ctx.fill();
        ctx.fillStyle = '#1f2937';
        ctx.font = (12 / cam.scale) + 'px sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText(p.title.length > 10 ? p.title.substring(0, 10) + '...' : p.title, p.x, p.y - 14 / cam.scale);
      });
      ctx.restore();
    }

    // 屏幕坐标命中节点（已换算世界坐标，适配缩放/平移）
    function hitNode(mx, my) {
      const wx = toWorldX(mx), wy = toWorldY(my);
      let hit = null;
      positions.forEach(p => {
        const dx = wx - p.x, dy = wy - p.y;
        if (dx * dx + dy * dy < 100) hit = p;
      });
      return hit;
    }

    function updateZoomLabel() {
      const el = document.getElementById('graphZoomLabel');
      if (el) el.textContent = Math.round(cam.scale * 100) + '%';
    }

    // 滚轮缩放：以鼠标位置为中心（锚点世界坐标保持不变）
    canvas.addEventListener('wheel', (e) => {
      e.preventDefault();
      const rect = canvas.getBoundingClientRect();
      const mx = e.clientX - rect.left, my = e.clientY - rect.top;
      const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
      const ns = Math.min(3, Math.max(0.2, cam.scale * factor));
      const wx = toWorldX(mx), wy = toWorldY(my);
      cam.scale = ns;
      cam.x = mx - wx * ns;
      cam.y = my - wy * ns;
      persistView();
      draw();
      updateZoomLabel();
    }, { passive: false });

    // 框选矩形（屏幕坐标绘制，虚线+半透明填充）
    function drawSelRect() {
      const rw = Math.abs(selCurX - selStartX), rh = Math.abs(selCurY - selStartY);
      if (rw < 2 && rh < 2) return;
      const x = Math.min(selStartX, selCurX), y = Math.min(selStartY, selCurY);
      ctx.save();
      ctx.strokeStyle = '#1a73e8';
      ctx.lineWidth = 1.5;
      ctx.setLineDash([6, 3]);
      ctx.strokeRect(x, y, rw, rh);
      ctx.fillStyle = 'rgba(26,115,232,0.08)';
      ctx.fillRect(x, y, rw, rh);
      ctx.restore();
    }

    const mousedown = (e) => {
      if (e.button !== 0) return; // 仅左键
      const rect = canvas.getBoundingClientRect();
      const mx = e.clientX - rect.left, my = e.clientY - rect.top;
      const hit = hitNode(mx, my);
      if (hit) {
        if (selected.size > 1 && selected.has(hit)) {
          // 点击已选中的节点 → 整体拖动选中组
          groupDrag = { startWX: toWorldX(mx), startWY: toWorldY(my), origins: new Map() };
          selected.forEach(p => groupDrag.origins.set(p, { x: p.x, y: p.y }));
          canvas.style.cursor = 'move';
        } else {
          // 单节点拖拽（点组外节点时清空原选区）
          selected.clear();
          selected.add(hit);
          dragging = hit;
          offsetX = hit.x - toWorldX(mx);
          offsetY = hit.y - toWorldY(my);
          canvas.style.cursor = 'move';
        }
      } else if (e.shiftKey) {
        // Shift + 空白拖拽 → 框选（不清除已有选区，追加）
        selecting = true;
        selStartX = selCurX = mx;
        selStartY = selCurY = my;
        canvas.style.cursor = 'crosshair';
      } else {
        // 空白拖拽 → 平移视图（顺带清空选区）
        if (selected.size) { selected.clear(); draw(); }
        panning = true;
        panStartX = mx; panStartY = my;
        panCamX = cam.x; panCamY = cam.y;
        canvas.style.cursor = 'grabbing';
      }
    };
    const mousemove = (e) => {
      const rect = canvas.getBoundingClientRect();
      const mx = e.clientX - rect.left, my = e.clientY - rect.top;
      if (groupDrag) {
        // 整组按世界坐标位移同步移动（钳制于护栏边界内）
        const dx = toWorldX(mx) - groupDrag.startWX;
        const dy = toWorldY(my) - groupDrag.startWY;
        groupDrag.origins.forEach((o, p) => { p.x = clampWorld(o.x + dx); p.y = clampWorld(o.y + dy); });
        draw();
      } else if (dragging) {
        dragging.x = clampWorld(toWorldX(mx) + offsetX);
        dragging.y = clampWorld(toWorldY(my) + offsetY);
        draw();
      } else if (selecting) {
        selCurX = mx; selCurY = my;
        draw();
        drawSelRect();
      } else if (panning) {
        cam.x = panCamX + (mx - panStartX);
        cam.y = panCamY + (my - panStartY);
        persistView();
        draw();
      } else {
        // 悬停光标提示：节点上可拖、空白可平移
        canvas.style.cursor = hitNode(mx, my) ? 'move' : 'grab';
      }
    };
    const mouseup = () => {
      if (dragging || groupDrag) {
        dragging = null; groupDrag = null;
        canvas.style.cursor = '';
        clearTimeout(App._graphSaveTimer);
        App._graphSaveTimer = setTimeout(() => {
          API.saveGraphLayout(positions.map(p => ({ id: p.id, x: Math.round(p.x), y: Math.round(p.y) })))
            .catch(() => {});
        }, 400);
      }
      if (selecting) {
        selecting = false;
        const rw = Math.abs(selCurX - selStartX), rh = Math.abs(selCurY - selStartY);
        if (rw > 4 || rh > 4) {
          // 松手时把框内节点加入选区（追加模式，Shift 语义）
          const x0 = Math.min(selStartX, selCurX), x1 = Math.max(selStartX, selCurX);
          const y0 = Math.min(selStartY, selCurY), y1 = Math.max(selStartY, selCurY);
          positions.forEach(p => {
            const sx = toScreenX(p.x), sy = toScreenY(p.y);
            if (sx >= x0 && sx <= x1 && sy >= y0 && sy <= y1) selected.add(p);
          });
          draw();
        }
        canvas.style.cursor = '';
      }
      if (panning) {
        panning = false;
        canvas.style.cursor = '';
      }
    };

    // Esc 清除选区
    window.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && selected.size) {
        selected.clear();
        draw();
      }
    });

    canvas.addEventListener('mousedown', mousedown);
    window.addEventListener('mousemove', mousemove);
    window.addEventListener('mouseup', mouseup);
    canvas.addEventListener('dblclick', (e) => {
      const rect = canvas.getBoundingClientRect();
      const hit = hitNode(e.clientX - rect.left, e.clientY - rect.top);
      if (hit) App.navigate('entries', { detail: hit.id });
    });

    draw();
    updateZoomLabel();

    // 缩放控件（右下角）
    const zoomBar = document.createElement('div');
    zoomBar.className = 'graph-zoom';
    zoomBar.innerHTML =
      '<button class="zoom-btn" data-action="out" title="缩小">−</button>' +
      '<span class="zoom-label" id="graphZoomLabel">100%</span>' +
      '<button class="zoom-btn" data-action="in" title="放大">+</button>' +
      '<button class="zoom-btn" data-action="reset" title="重置视图">⤢</button>';
    zoomBar.addEventListener('click', (e) => {
      const btn = e.target.closest('.zoom-btn');
      if (!btn) return;
      const action = btn.dataset.action;
      const cx = w / 2, cy = h / 2;
      if (action === 'in' || action === 'out') {
        const ns = action === 'in' ? Math.min(3, cam.scale * 1.3) : Math.max(0.2, cam.scale / 1.3);
        const wx = toWorldX(cx), wy = toWorldY(cy);
        cam.scale = ns;
        cam.x = cx - wx * ns;
        cam.y = cy - wy * ns;
      } else if (action === 'reset') {
        cam = { x: 0, y: 0, scale: 1 };
      }
      persistView();
      draw();
      updateZoomLabel();
    });
    container.appendChild(zoomBar);

    // Legend（左下角）
    const legend = document.createElement('div');
    legend.className = 'graph-legend';
    legend.innerHTML = Object.entries(typeColors).map(([k, v]) =>
      `<div class="legend-item"><span class="legend-dot" style="background:${v}"></span>${App.typeLabel(k)}</div>`
    ).join('') + `
      <div class="graph-hint">
        滚轮缩放 · 空白拖拽平移<br>
        Shift 拖拽框选多节点<br>
        拖节点调整布局<br>
        双击查看详情<br>
        <span style="color:rgba(220,38,38,0.85)">▌</span>红虚线框 = 布局边界（±${WORLD_GUARD}）
      </div>`;
    container.appendChild(legend);
  },

  // === Search ===
  async renderSearch(body) {
    body.innerHTML = `
      <div class="search-bar">
        <input type="text" id="searchQuery" placeholder="搜索关键词..." onkeyup="App.debounceSearchQuery()"/>
        <select id="searchType" onchange="App.executeSearch()">
          <option value="all">全部</option>
          <option value="entry">知识条目</option>
          <option value="record">日常记录</option>
        </select>
        <button class="btn" onclick="App.showAdvancedSearch()">🔍 高级</button>
      </div>
      <div class="filter-group" id="advancedFilters" style="display:none">
        <select id="searchTag" onchange="App.executeSearch()" style="min-width:200px"><option value="">全部标签</option></select>
        <input type="date" id="searchDateFrom" onchange="App.executeSearch()" style="padding:8px;border:1px solid var(--border);border-radius:8px;font-size:13px"/>
        <span style="color:var(--text-secondary);font-size:13px">至</span>
        <input type="date" id="searchDateTo" onchange="App.executeSearch()" style="padding:8px;border:1px solid var(--border);border-radius:8px;font-size:13px"/>
      </div>
      <div id="searchResults"></div>
    `;
    const tagSelect = document.getElementById('searchTag');
    this.appendTagOptions(tagSelect, this.state.tags);
  },

  showAdvancedSearch() {
    const el = document.getElementById('advancedFilters');
    el.style.display = el.style.display === 'none' ? 'flex' : 'none';
  },

  _searchTimer: null,
  debounceSearchQuery() {
    clearTimeout(this._searchTimer);
    this._searchTimer = setTimeout(() => this.executeSearch(), 400);
  },

  async executeSearch() {
    const q = document.getElementById('searchQuery')?.value || '';
    const type = document.getElementById('searchType')?.value || 'all';
    const tag = document.getElementById('searchTag')?.value || '';
    const dateFrom = document.getElementById('searchDateFrom')?.value || '';
    const dateTo = document.getElementById('searchDateTo')?.value || '';
    const body = document.getElementById('searchResults');
    if (!body) return;
    if (!q && !tag && !dateFrom && !dateTo) {
      body.innerHTML = '<div class="empty-state"><p>输入关键词开始搜索</p></div>';
      return;
    }
    body.innerHTML = '<div class="loading">搜索中...</div>';
    const params = { q, type };
    if (tag) params.tag = tag;
    if (dateFrom) params.date_from = dateFrom;
    if (dateTo) params.date_to = dateTo;

    try {
      const result = await API.search(params);
      const entries = result.entries || [];
      const records = result.records || [];
      body.innerHTML = `
        <div style="margin-bottom:12px;font-size:13px;color:var(--text-secondary)">找到 ${entries.length + records.length} 个结果</div>
        ${entries.length > 0 ? `<div class="card"><div class="card-header"><h3>📝 知识条目 (${entries.length})</h3></div><div class="entry-list">${entries.map(e => `
          <div class="entry-item" onclick="App.navigate('entries',{detail:${e.id}})">
            <div class="entry-title">${this.highlight(this.esc(e.title), q)}</div>
            <div class="entry-summary">${this.highlight(this.esc((e.summary || e.content || '').substring(0,150)), q)}</div>
            <div class="entry-meta"><span class="entry-type-badge ${e.entry_type}">${this.typeLabel(e.entry_type)}</span>${e.created_by ? `<span>👤 ${this.esc(e.created_by)}</span>` : ''}<span>${this.esc(e.updated_at)}</span></div>
          </div>
        `).join('')}</div></div>` : ''}
        ${records.length > 0 ? `<div class="card"><div class="card-header"><h3>📋 日常记录 (${records.length})</h3></div><div class="entry-list">${records.map(r => `
          <div class="entry-item" onclick="App.navigate('records',{detail:${r.id}})">
            <div class="entry-title">${this.highlight(this.esc(r.title), q)}</div>
            <div class="entry-summary">${this.highlight(this.esc((r.content || '').substring(0,150)), q)}</div>
            <div class="entry-meta"><span class="entry-type-badge ${r.record_type}">${this.recordTypeLabel(r.record_type)}</span>${r.created_by ? `<span>👤 ${this.esc(r.created_by)}</span>` : ''}<span>${this.esc(r.updated_at)}</span></div>
          </div>
        `).join('')}</div></div>` : ''}
        ${entries.length === 0 && records.length === 0 ? '<div class="empty-state"><p>没有找到匹配结果</p></div>' : ''}
      `;
    } catch (e) {
      body.innerHTML = `<div class="empty-state"><p>搜索失败: ${this.esc(e.message)}</p></div>`;
    }
  },

  // === Export ===
  async renderExport(body) {
    body.innerHTML = `
      <div class="card">
        <div class="card-header"><h3>📤 导出数据</h3></div>
        <p style="color:var(--text-secondary);margin-bottom:12px">将所有知识条目、日常记录、标签和关联导出为 JSON 格式。</p>
        <button class="btn btn-primary" onclick="App.exportData()">导出 JSON</button>
      </div>
      <div class="card">
        <div class="card-header"><h3>📥 导入数据</h3></div>
        <p style="color:var(--text-secondary);margin-bottom:12px">从 JSON 文件导入数据（会合并到现有数据中）。</p>
        <input type="file" id="importFile" accept=".json" style="margin-bottom:12px"/>
        <button class="btn btn-primary" onclick="App.importData()">导入数据</button>
        <hr style="margin:14px 0;border:none;border-top:1px solid var(--border-color, #eee)"/>
        <p style="color:var(--text-secondary);margin-bottom:12px">从本地知识库目录（KB）自动扫描导入 wiki / external-wiki / skills / 日报等文件，已存在的条目会自动跳过。</p>
        <button class="btn" onclick="App.importKb()">📂 从KB目录导入</button>
        <div id="kbImportResult" style="margin-top:10px;font-size:13px;white-space:pre-wrap"></div>
      </div>
      <div class="card">
        <div class="card-header"><h3>📋 操作记录</h3></div>
        <div class="oplog-list" id="oplogList">加载中...</div>
      </div>
      <div class="card">
        <div class="card-header"><h3>ℹ️ 关于</h3></div>
        <p style="color:var(--text-secondary);font-size:14px">
          知识库 v1.8.4<br/>
          个人知识管理平台 · 离线优先 · 数据本地存储<br/>
          所有数据存储在本地 SQLite 数据库中，保障隐私安全。
        </p>
      </div>
    `;
    this.loadOperationLogs();
  },

  async loadOperationLogs() {
    const el = document.getElementById('oplogList');
    if (!el) return;
    try {
      const logs = await API.getOperationLogs();
      if (!logs || logs.length === 0) {
        el.innerHTML = '<div class="empty-state"><p>暂无操作记录</p></div>';
        return;
      }
      const actionLabels = {
        export: '📤导出', import: '📥导入',
        create_entry: '📝新建条目', create_record: '📋新建记录'
      };
      el.innerHTML = logs.map(log => `
        <div class="oplog-item">
          <span class="oplog-action ${log.action}">${actionLabels[log.action] || log.action}</span>
          <span class="oplog-user">${this.esc(log.username || '未知')}</span>
          <span class="oplog-source">${log.source === 'mobile' ? '📱手机端' : '💻网页端'}</span>
          <span class="oplog-time">${this.esc(log.created_at)}</span>
        </div>
      `).join('');
    } catch (e) {
      el.innerHTML = '<div class="empty-state"><p>加载失败</p></div>';
    }
  },

  async exportData() {
    try {
      const data = await API.exportData('web');
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `knowledge_backup_${new Date().toISOString().slice(0,10)}.json`;
      a.click();
      URL.revokeObjectURL(url);
      this.toast('导出成功');
    } catch (e) {
      this.toast('导出失败: ' + e.message);
    }
  },

  async importData() {
    const fileInput = document.getElementById('importFile');
    if (!fileInput.files || !fileInput.files[0]) { this.toast('请选择文件'); return; }
    try {
      const text = await fileInput.files[0].text();
      const data = JSON.parse(text);
      await API.importData(data, 'web');
      this.toast('导入成功');
      await this.loadTags();
      this.navigate('dashboard');
    } catch (e) {
      this.toast('导入失败: ' + e.message);
    }
  },

  async importKb() {
    const res = document.getElementById('kbImportResult');
    if (res) res.textContent = '正在扫描KB目录...';
    try {
      const data = await API.importKb('');
      if (res) {
        const parts = [
          `条目新增: ${data.entries_new}`,
          `条目同步更新: ${data.entries_updated || 0}`,
          `条目跳过(已存在): ${data.entries_skip}`,
          `记录新增: ${data.records_new}`,
          `记录同步更新: ${data.records_updated || 0}`,
          `记录跳过(已存在): ${data.records_skip}`,
          `新增链接: ${data.links}`,
          `新增标签: ${data.tags}`
        ];
        if (data.errors && data.errors.length) parts.push('错误:\n' + data.errors.join('\n'));
        res.textContent = parts.join('\n');
      }
      this.toast('KB导入完成');
      await this.loadTags();
      this.navigate('dashboard');
    } catch (e) {
      if (res) res.textContent = '导入失败: ' + e.message;
      this.toast('KB导入失败: ' + e.message);
    }
  },

  // === Delete Confirmation ===
  confirmDelete(type, id) {
    const labels = { entry: '知识条目', record: '日常记录', tag: '标签', user: '用户' };
    document.getElementById('modalContent').innerHTML = `
      <h3>确认删除</h3>
      <p>确定要删除这个${labels[type] || '条目'}吗？此操作不可撤销。</p>
      <div class="actions">
        <button class="btn" onclick="App.closeModal()">取消</button>
        <button class="btn btn-danger" onclick="App.doDelete('${type}',${id})">确认删除</button>
      </div>
    `;
    this.showModal();
  },

  async doDelete(type, id) {
    try {
      if (type === 'entry') await API.deleteEntry(id);
      else if (type === 'record') await API.deleteRecord(id);
      else if (type === 'tag') await API.deleteTag(id);
      else if (type === 'user') await API.deleteAdminUser(id);
      this.toast('已删除');
      this.closeModal();
      if (type === 'entry') await this.loadEntryList();
      else if (type === 'record') await this.loadRecordList();
      else if (type === 'tag') { await this.loadTags(); this.navigate('tags'); }
      else if (type === 'user') this.navigate('admin');
    } catch (e) {
      this.toast('删除失败: ' + e.message);
    }
  },

  // === Modal ===
  showModal() {
    document.getElementById('modalOverlay').classList.add('show');
  },

  closeModal() {
    document.getElementById('modalOverlay').classList.remove('show');
  },

  // === Sidebar ===
  async updateSidebarStats() {
    try {
      const stats = await API.getStats();
      document.getElementById('sidebarStats').textContent = `📝 ${stats.entry_count} 条目 · 🏷️ ${stats.tag_count} 标签`;
    } catch (e) {}
  },

  // === Utilities ===
  esc(s) {
    if (!s) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;');
  },

  escHtml(s) {
    if (!s) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  },

  highlight(text, query) {
    if (!query || !text) return text;
    const q = query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return text.replace(new RegExp(`(${q})`, 'gi'), '<mark style="background:#fef3c7;padding:0 2px">$1</mark>');
  },

  typeLabel(type) {
    const labels = { note: '笔记', skill: '技能', task: '任务', idea: '想法', article: '文章' };
    return labels[type] || type;
  },

  shareStatusBadge(status) {
    const map = {
      active: '<span style="display:inline-block;padding:2px 7px;border-radius:8px;font-size:11px;font-weight:500;background:#d1fae5;color:#059669">已分享</span>',
      disabled: '<span style="display:inline-block;padding:2px 7px;border-radius:8px;font-size:11px;font-weight:500;background:#f3f4f6;color:#9ca3af">已停用</span>',
      none: ''
    };
    return map[status] || '';
  },

  recordTypeLabel(type) {
    const labels = { daily: '日记', meeting: '会议', idea: '想法', note: '笔记', task: '任务' };
    return labels[type] || type;
  },

  handleExportChange(sel, type, id) {
    const format = sel.value;
    sel.value = '';
    if (!format) return;
    if (format === 'doc') {
      this.exportWord(type, id);
    } else {
      this.exportItem(type, id, format);
    }
  },

  downloadFile(content, filename) {
    const ext = filename.split('.').pop();
    const mime = ext === 'md' ? 'text/markdown' : (ext === 'doc' || ext === 'docx') ? 'application/msword' : 'text/plain;charset=utf-8';
    const blob = new Blob([content], { type: mime });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  },

  pickAndImportFile(type) {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.md,.txt,.text';
    input.onchange = async (e) => {
      const file = e.target.files[0];
      if (!file) return;
      try {
        const content = await file.text();
        let title = file.name.replace(/\.(md|txt|text)$/i, '');
        const firstLine = content.trimStart().split('\n')[0];
        const m = firstLine && firstLine.match(/^#\s+(.+)/);
        if (m) title = m[1];
        if (type === 'entry') {
          await API.createEntry({ title, content, entry_type: 'note' });
          this.toast('条目导入成功');
          this.loadEntryList();
        } else {
          await API.createRecord({ title, content, record_type: 'note' });
          this.toast('记录导入成功');
          this.loadRecordList();
        }
      } catch (err) {
        this.toast('导入失败: ' + err.message);
      }
    };
    input.click();
  },

  async exportItem(type, id, format) {
    try {
      const data = type === 'entry' ? await API.getEntry(id) : await API.getRecord(id);
      this.downloadFile(data.content || '', data.title + '.' + format);
      this.toast('导出成功');
    } catch (err) {
      this.toast('导出失败: ' + err.message);
    }
  },

  async exportWord(type, id) {
    try {
      const data = type === 'entry' ? await API.getEntry(id) : await API.getRecord(id);
      const typeLabels = { note: '笔记', skill: '技能', task: '任务', idea: '想法', article: '文章' };
      const typeLabel = typeLabels[data.entry_type || data.record_type] || data.entry_type || data.record_type || '';
      const meta = [typeLabel, data.created_by ? '作者: ' + data.created_by : '', data.updated_at || data.created_at || ''].filter(Boolean).join(' | ');
      const contentHtml = this.md2html(data.content || '');
      const html = '<!DOCTYPE html><html><head><meta charset="UTF-8"><title>' + this.esc(data.title) + '</title>' +
        '<style>body{font-family:宋体,SimSun,serif;font-size:12pt;line-height:1.8;color:#333;max-width:800px;margin:40px auto;padding:0 20px}' +
        'h1{font-size:22pt;text-align:center;margin-bottom:4pt}h2{font-size:16pt;border-bottom:1px solid #ccc;padding-bottom:4pt}' +
        'h3{font-size:14pt}.meta{text-align:center;font-size:10pt;color:#999;margin-bottom:20pt}' +
        'p{margin:6pt 0}pre{background:#f5f5f5;padding:10pt;border:1px solid #ddd;font-size:10pt}' +
        'code{background:#f5f5f5;padding:1pt 4pt;font-size:10pt}blockquote{border-left:3px solid #1a73e8;padding:6pt 12pt;margin:10pt 0;background:#f8fafc}' +
        'ul,ol{margin:6pt 0;padding-left:20pt}img{max-width:100%}table{border-collapse:collapse;width:100%;margin:10pt 0}' +
        'td,th{border:1px solid #ccc;padding:4pt 8pt;text-align:left}' +
        '</style></head><body>' +
        '<h1>' + this.esc(data.title) + '</h1>' +
        '<div class="meta">' + this.esc(meta) + '</div>' +
        '<div>' + contentHtml + '</div></body></html>';
      this.downloadFile(html, data.title + '.docx');
      this.toast('Word 导出成功');
    } catch (err) {
      this.toast('Word 导出失败: ' + err.message);
    }
  },

  md2html(text) {
    if (!text) return '';
    let t = this.esc(text);
    t = t.replace(/^### (.+)$/gm, '<h3>$1</h3>');
    t = t.replace(/^## (.+)$/gm, '<h2>$1</h2>');
    t = t.replace(/^# (.+)$/gm, '<h1>$1</h1>');
    // Protect code blocks
    const codeBlocks = [];
    t = t.replace(/```([\s\S]*?)```/g, (m) => { const i = codeBlocks.length; codeBlocks.push(m); return `%%CB${i}%%`; });
    t = t.replace(/`([^`]+)`/g, '<code>$1</code>');
    t = t.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    t = t.replace(/\*([^*]+)\*/g, '<em>$1</em>');
    t = t.replace(/^>- (.+)$/gm, '<blockquote>$1</blockquote>');
    t = t.replace(/^- (.+)$/gm, '<li>$1</li>');
    t = t.replace(/(<li>.*<\/li>)/s, '<ul>$1</ul>');
    t = t.replace(/^\d+\. (.+)$/gm, '<li>$1</li>');
    t = t.replace(/\n\s*\n/g, '</p><p>');
    t = t.replace(/\n/g, '<br>');
    t = '<p>' + t + '</p>';
    t = t.replace(/<p><\/p>/g, '');
    // Restore code blocks
    t = t.replace(/%%CB(\d+)%%/g, (_, i) => codeBlocks[+i]);
    return t;
  },

  async convertRecordToEntry(id) {
    try {
      const record = await API.getRecord(id);
      const tags = record.tag_names ? record.tag_names.split(',').map(t => t.trim()).filter(Boolean) : [];
      const entryData = {
        title: record.title,
        content: record.content || '',
        summary: (record.content || '').substring(0, 200),
        entry_type: 'note',
        importance: record.importance || 0,
        tags: tags
      };
      const result = await API.createEntry(entryData);
      this.toast('已转化为知识条目');
      this.navigate('entries', { detail: result.id });
    } catch (e) {
      this.toast('转化失败: ' + e.message);
    }
  },

  renderOutline(content) {
    if (!content) return '';
    const headings = [];
    const lines = content.split('\n');
    let idx = 0;
    lines.forEach((line) => {
      const m = line.match(/^(#{1,4})\s+(.+)/);
      if (m) {
        headings.push({ level: m[1].length, text: m[2].trim(), idx: idx });
        idx++;
      }
    });
    if (headings.length < 2) return '';
    let html = '<ul>';
    headings.forEach(h => {
      html += `<li style="padding-left:${(h.level - 1) * 14}px"><a href="#" data-outline="${h.idx}" onclick="event.preventDefault();document.getElementById('outline-h${h.idx}')?.scrollIntoView({behavior:'smooth'})">${this.esc(h.text)}</a></li>`;
    });
    html += '</ul>';
    return html;
  },

  toggleOutline() {
    const sidebar = document.getElementById('outlineSidebar');
    const showBtn = document.getElementById('outlineShowBtn');
    if (!sidebar) return;
    const hidden = sidebar.style.display === 'none';
    sidebar.style.display = hidden ? '' : 'none';
    if (showBtn) showBtn.style.display = hidden ? 'none' : '';
  },

  md(text) {
    if (!text) return '';
    let headingIdx = 0;
    let html = text
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/```(\w*)\n([\s\S]*?)```/g, function(m, lang, code) {
        return '<pre><code>' + code.replace(/</g, '&lt;').replace(/>/g, '&gt;') + '</code></pre>';
      })
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/^#### (.+)/gm, function(m, t) { return '<h4 id="outline-h' + (headingIdx++) + '">' + t + '</h4>'; })
      .replace(/^### (.+)/gm, function(m, t) { return '<h3 id="outline-h' + (headingIdx++) + '">' + t + '</h3>'; })
      .replace(/^## (.+)/gm, function(m, t) { return '<h2 id="outline-h' + (headingIdx++) + '">' + t + '</h2>'; })
      .replace(/^# (.+)/gm, function(m, t) { return '<h1 id="outline-h' + (headingIdx++) + '">' + t + '</h1>'; })
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/\*(.+?)\*/g, '<em>$1</em>')
      .replace(/^- (.+)/gm, '<li>$1</li>')
      .replace(/^(\d+)\. (.+)/gm, '<li>$1. $2</li>')
      .replace(/> (.+)/g, '<blockquote>$1</blockquote>')
      .replace(/\n\n/g, '</p><p>')
      .replace(/\n/g, '<br/>');
    return '<p>' + html + '</p>';
  }
};

document.addEventListener('DOMContentLoaded', () => App.init());
document.addEventListener('click', () => {
  document.querySelectorAll('.export-menu').forEach(m => m.style.display = 'none');
});