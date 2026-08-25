const API = {
  base: '/api',
  _token: localStorage.getItem('token') || '',

  setToken(token) {
    this._token = token;
    if (token) localStorage.setItem('token', token);
    else localStorage.removeItem('token');
  },

  async request(method, path, body) {
    const opts = { method, headers: { 'Content-Type': 'application/json' } };
    if (this._token) opts.headers['Authorization'] = 'Bearer ' + this._token;
    if (body) opts.body = JSON.stringify(body);
    try {
      const res = await fetch(this.base + path, opts);
      const data = await res.json();
      if (data.code === 0) return data.data;
      if (data.code === 500 && data.msg === '未登录') {
        API.setToken('');
        if (typeof App !== 'undefined' && App.state) App.showLogin();
      }
      throw new Error(data.msg || '请求失败');
    } catch (e) {
      if (e.message.includes('Failed to fetch')) throw new Error('网络连接失败');
      throw e;
    }
  },

  // Auth
  login(username, password) { return this.request('POST', '/auth/login', { username, password }); },
  register(username, password) { return this.request('POST', '/auth/register', { username, password }); },
  verifyToken() { return this.request('GET', '/auth/verify'); },

  // Admin
  getAdminUsers() { return this.request('GET', '/admin/users'); },
  createAdminUser(data) { return this.request('POST', '/admin/users', data); },
  updateAdminUser(id, data) { return this.request('PUT', '/admin/users/' + id, data); },
  deleteAdminUser(id) { return this.request('DELETE', '/admin/users/' + id); },

  // Entries
  getEntries(params) {
    const qs = params ? '?' + new URLSearchParams(params).toString() : '';
    return this.request('GET', '/entries' + qs);
  },
  getEntry(id) { return this.request('GET', '/entries/' + id); },
  createEntry(data) { return this.request('POST', '/entries', data); },
  updateEntry(id, data) { return this.request('PUT', '/entries/' + id, data); },
  deleteEntry(id) { return this.request('DELETE', '/entries/' + id); },
  getEntryVersions(id) { return this.request('GET', '/entries/' + id + '/versions'); },
  getBacklinks(id) { return this.request('GET', '/entries/' + id + '/backlinks'); },
  getEntryLinks(id) { return this.request('GET', '/entries/' + id + '/links'); },
  createEntryLink(id, data) { return this.request('POST', '/entries/' + id + '/links', data); },
  deleteEntryLink(id, linkId) { return this.request('DELETE', '/entries/' + id + '/links/' + linkId); },
  toggleFavorite(id, add) { return this.request(add ? 'POST' : 'DELETE', '/entries/' + id + '/favorite'); },
  shareEntry(id) { return this.request('POST', '/entries/' + id + '/share'); },
  getShareStatus(id) { return this.request('GET', '/entries/' + id + '/share'); },
  disableShare(id) { return this.request('DELETE', '/entries/' + id + '/share'); },
  getShareContent(token) { return this.request('GET', '/share/' + token); },

  // Records
  getRecords(params) {
    const qs = params ? '?' + new URLSearchParams(params).toString() : '';
    return this.request('GET', '/records' + qs);
  },
  getRecord(id) { return this.request('GET', '/records/' + id); },
  createRecord(data) { return this.request('POST', '/records', data); },
  updateRecord(id, data) { return this.request('PUT', '/records/' + id, data); },
  deleteRecord(id) { return this.request('DELETE', '/records/' + id); },

  // Tags
  getTags() { return this.request('GET', '/tags'); },
  createTag(data) { return this.request('POST', '/tags', data); },
  updateTag(id, data) { return this.request('PUT', '/tags/' + id, data); },
  deleteTag(id) { return this.request('DELETE', '/tags/' + id); },

  // Graph
  getGraph() { return this.request('GET', '/graph'); },
  getGraphLayout() { return this.request('GET', '/graph/layout'); },
  saveGraphLayout(positions) { return this.request('POST', '/graph/layout', { positions }); },

  // Search
  search(params) {
    const qs = '?' + new URLSearchParams(params).toString();
    return this.request('GET', '/search' + qs);
  },

  // Stats
  getStats() { return this.request('GET', '/stats'); },

  // Export/Import
  exportData(source) { return this.request('GET', '/export?source=' + (source || 'web')); },
  importData(data, source) { return this.request('POST', '/import?source=' + (source || 'web'), data); },
  importKb(path, relinks) {
    let q = '/import/kb?path=' + encodeURIComponent(path || '');
    if (relinks) q += '&relinks=1';
    return this.request('POST', q);
  },

  // Batch operations
  batchEntries(ids, action, data) {
    const payload = { ids, action };
    if (action === 'add-tag' && data && data.tag) payload.tag = data.tag;
    return this.request('POST', '/entries/batch', payload);
  },
  batchRecords(ids, action, data) {
    const payload = { ids, action };
    if (action === 'add-tag' && data && data.tag) payload.tag = data.tag;
    return this.request('POST', '/records/batch', payload);
  },

  // Attachments
  uploadAttachment(sourceType, sourceId, file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => {
        const base64 = reader.result.split(',')[1];
        this.request('POST', '/attachments/upload', {
          source_type: sourceType,
          source_id: sourceId,
          filename: file.name,
          mime_type: file.type || 'application/octet-stream',
          data: base64
        }).then(resolve).catch(reject);
      };
      reader.onerror = () => reject(new Error('读取文件失败'));
      reader.readAsDataURL(file);
    });
  },
  getAttachments(sourceType, sourceId) {
    return this.request('GET', '/attachments?source_type=' + sourceType + '&source_id=' + sourceId);
  },
  deleteAttachment(id) { return this.request('DELETE', '/attachments/' + id); },

  // Operation logs
  getOperationLogs() { return this.request('GET', '/oplogs'); }
};