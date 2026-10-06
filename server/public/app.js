'use strict';

// ===================================================================== Utilidades

const $ = (sel, root = document) => root.querySelector(sel);
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

async function api(path, { method = 'GET', body } = {}) {
  const res = await fetch(`/api${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'signage' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 401 && path !== '/login') {
    showLogin();
    throw new Error('Sessão expirada');
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `Erro ${res.status}`);
  return data;
}

let toastTimer;
function toast(message, isError = false) {
  const el = $('#toast');
  el.textContent = message;
  el.className = `toast${isError ? ' error' : ''}`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add('hidden'), 3500);
}
const fail = (e) => toast(e.message, true);

function bytes(n) {
  if (n == null) return '—';
  const u = ['B', 'KB', 'MB', 'GB'];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(i > 1 ? 1 : 0)} ${u[i]}`;
}
function duration(ms) {
  if (!ms) return '—';
  const s = Math.round(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}
function ago(ts) {
  if (!ts) return 'nunca';
  const s = Math.round((Date.now() - ts) / 1000);
  if (s < 60) return `há ${s}s`;
  if (s < 3600) return `há ${Math.round(s / 60)} min`;
  if (s < 86400) return `há ${Math.round(s / 3600)} h`;
  return new Date(ts).toLocaleString('pt-BR');
}
const preview = (m, cls = 'preview') => m.type === 'IMAGE'
  ? `<img class="${cls}" src="${esc(m.url)}" loading="lazy" alt="">`
  : `<video class="${cls}" src="${esc(m.url)}#t=0.5" preload="metadata" muted></video>`;

// ===================================================================== Estado / navegação

const state = { media: [], playlists: [], devices: [], current: null, dirty: false, view: 'devices' };

function showLogin() {
  $('#app').classList.add('hidden');
  $('#login').classList.remove('hidden');
  $('#password').focus();
}

async function showApp() {
  $('#login').classList.add('hidden');
  $('#app').classList.remove('hidden');
  await Promise.all([loadMedia(), loadPlaylists()]);
  await loadDevices();
  switchView(location.hash.slice(1) || 'devices');
}

function switchView(view) {
  if (!['devices', 'playlists', 'media', 'releases'].includes(view)) view = 'devices';
  if (state.view === 'playlists' && view !== 'playlists' && state.dirty &&
      !confirm('Há alterações não salvas na playlist. Sair mesmo assim?')) return;
  if (view !== 'playlists') state.dirty = false;
  state.view = view;
  history.replaceState(null, '', `#${view}`);
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.view === view));
  document.querySelectorAll('.view').forEach((v) => v.classList.toggle('hidden', v.id !== `view-${view}`));
  if (view === 'devices') loadDevices().catch(fail);
  if (view === 'playlists') renderPlaylistList();
  if (view === 'media') loadMedia().catch(fail);
  if (view === 'releases') loadReleases().catch(fail);
}

document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', () => switchView(t.dataset.view)));
window.addEventListener('beforeunload', (e) => { if (state.dirty) e.preventDefault(); });

$('#login-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('#login-error').textContent = '';
  try {
    await api('/login', { method: 'POST', body: { password: $('#password').value } });
    $('#password').value = '';
    await showApp();
  } catch (err) {
    $('#login-error').textContent = err.message;
  }
});

$('#logout').addEventListener('click', async () => {
  await api('/logout', { method: 'POST' }).catch(() => {});
  showLogin();
});

// ===================================================================== TVs

async function loadDevices() {
  state.devices = await api('/devices');
  renderDevices();
}

function playlistOptions(selected, emptyLabel = '— sem playlist —') {
  return `<option value="">${emptyLabel}</option>` + state.playlists
    .map((p) => `<option value="${p.id}" ${p.id === selected ? 'selected' : ''}>${esc(p.name)}</option>`).join('');
}

function renderDevices() {
  $('#pair-playlist').innerHTML = playlistOptions(null, 'Playlist (opcional)');
  const approved = state.devices.filter((d) => d.status !== 'pending');
  const online = approved.filter((d) => d.online).length;
  const pending = state.devices.length - approved.length;
  $('#devices-summary').textContent = `${approved.length} TV(s) • ${online} online${pending ? ` • ${pending} aguardando código` : ''}`;

  if (!state.devices.length) {
    $('#devices').innerHTML = `<div class="card empty">Nenhuma TV conectada ainda.<br>No app da TV: Administração › Configurações › Servidor.</div>`;
    return;
  }
  $('#devices').innerHTML = state.devices.map(deviceCard).join('');
}

function deviceCard(d) {
  if (d.status === 'pending') {
    return `<div class="card device">
      <div class="device-head"><span class="device-name">${esc(d.name)}</span><span class="badge pending">Aguardando código</span></div>
      <p class="muted small">Esta TV está pedindo para conectar. Digite o código exibido na tela dela em "Adicionar TV".</p>
      <dl class="kv"><dt>Modelo</dt><dd>${esc(d.model || '—')}</dd><dt>Último contato</dt><dd>${ago(d.lastSeen)}</dd><dt>IP</dt><dd>${esc(d.lastIp || '—')}</dd></dl>
      <div class="device-actions"><button class="btn small danger" data-act="delete" data-id="${d.id}">Remover</button></div>
    </div>`;
  }
  const st = d.lastStatus || {};
  const pb = st.playback || {};
  const badge = d.status === 'blocked' ? '<span class="badge blocked">Bloqueada</span>'
    : d.online ? '<span class="badge online">● Online</span>' : '<span class="badge offline">○ Offline</span>';
  const s = d.settings;
  return `<div class="card device" data-id="${d.id}">
    <div class="device-head">
      <div><div class="device-name">${esc(d.name)}</div><div class="muted small">visto ${ago(d.lastSeen)}</div></div>
      ${badge}
    </div>
    <div class="now-playing">${pb.current
      ? `▶ <strong>${esc(pb.current)}</strong> (${(pb.index ?? 0) + 1}/${pb.total ?? '?'}) • ${esc(pb.status || '')}`
      : esc(pb.status || 'Sem informação de reprodução')}</div>
    <dl class="kv">
      <dt>Playlist</dt><dd>${esc(d.playlistName || 'nenhuma')}</dd>
      <dt>Armazenamento</dt><dd>${st.storageFreeBytes != null ? `${bytes(st.storageFreeBytes)} livres de ${bytes(st.storageTotalBytes)}` : '—'}</dd>
      <dt>Downloads</dt><dd>${st.downloadsPending ? `${st.downloadsPending} pendente(s)` : 'em dia'}</dd>
      <dt>Último erro</dt><dd>${esc(pb.lastError || 'nenhum')}</dd>
      <dt>Aparelho</dt><dd>${esc(d.model || '—')}</dd>
      <dt>App / Tela</dt><dd>${esc(d.appVersion || '—')} • ${esc(st.display || '—')}</dd>
      ${d.updateAvailable || st.update?.state && st.update.state !== 'IDLE' ? `<dt>Atualização</dt><dd>${updateLabel(d)}</dd>` : ''}
      <dt>IP</dt><dd>${esc(d.lastIp || '—')}</dd>
    </dl>
    <div class="device-controls">
      <label>Playlist<select data-field="playlistId">${playlistOptions(d.playlistId)}</select></label>
      <label>Ajuste da imagem<select data-setting="imageScaleMode">
        <option value="CENTER_CROP" ${s.imageScaleMode === 'CENTER_CROP' ? 'selected' : ''}>Preencher tela</option>
        <option value="FIT_CENTER" ${s.imageScaleMode === 'FIT_CENTER' ? 'selected' : ''}>Imagem inteira</option></select></label>
      <label>Transição<select data-setting="transition">
        <option value="FADE" ${s.transition === 'FADE' ? 'selected' : ''}>Fade</option>
        <option value="NONE" ${s.transition === 'NONE' ? 'selected' : ''}>Nenhuma</option></select></label>
      <label>Duração do fade (ms)<input type="number" min="0" max="3000" step="100" value="${s.transitionDurationMs}" data-setting="transitionDurationMs"></label>
      <label>Som dos vídeos<select data-setting="videoMuted">
        <option value="false" ${!s.videoMuted ? 'selected' : ''}>Ligado</option>
        <option value="true" ${s.videoMuted ? 'selected' : ''}>Mudo</option></select></label>
    </div>
    <div class="device-actions">
      <button class="btn small" data-act="command" data-command="restart_playlist" data-id="${d.id}">⟲ Reiniciar playlist</button>
      <button class="btn small" data-act="command" data-command="sync_now" data-id="${d.id}">Sincronizar</button>
      ${d.updateAvailable ? `<button class="btn small primary" data-act="command" data-command="update_app" data-id="${d.id}">⬆ Instalar atualização</button>` : ''}
      <button class="btn small" data-act="rename" data-id="${d.id}">Renomear</button>
      <button class="btn small" data-act="block" data-id="${d.id}">${d.status === 'blocked' ? 'Desbloquear' : 'Bloquear'}</button>
      <button class="btn small danger" data-act="delete" data-id="${d.id}">Remover</button>
    </div>
    ${d.pendingCommand ? '<div class="muted small">Comando enviado; será executado na próxima sincronização (até 1 min).</div>' : ''}
  </div>`;
}

$('#devices').addEventListener('change', async (e) => {
  const card = e.target.closest('.device');
  if (!card?.dataset.id) return;
  try {
    if (e.target.dataset.field === 'playlistId') {
      await api(`/devices/${card.dataset.id}`, { method: 'PATCH', body: { playlistId: e.target.value || null } });
      toast('Playlist alterada. A TV atualiza em até 1 minuto.');
    } else if (e.target.dataset.setting) {
      let value = e.target.value;
      if (e.target.dataset.setting === 'videoMuted') value = value === 'true';
      await api(`/devices/${card.dataset.id}`, { method: 'PATCH', body: { settings: { [e.target.dataset.setting]: value } } });
      toast('Configuração salva. A TV aplica em até 1 minuto.');
    }
    await loadDevices();
  } catch (err) { fail(err); }
});

$('#devices').addEventListener('click', async (e) => {
  const btn = e.target.closest('button[data-act]');
  if (!btn) return;
  const id = btn.dataset.id;
  const device = state.devices.find((d) => d.id === id);
  try {
    if (btn.dataset.act === 'command') {
      await api(`/devices/${id}/command`, { method: 'POST', body: { command: btn.dataset.command } });
      toast('Comando enviado. A TV executa em até 1 minuto.');
    } else if (btn.dataset.act === 'rename') {
      const name = prompt('Nome da TV:', device.name);
      if (!name) return;
      await api(`/devices/${id}`, { method: 'PATCH', body: { name } });
    } else if (btn.dataset.act === 'block') {
      await api(`/devices/${id}`, { method: 'PATCH', body: { status: device.status === 'blocked' ? 'approved' : 'blocked' } });
    } else if (btn.dataset.act === 'delete') {
      if (!confirm(`Remover "${device.name}"? Para voltar, a TV precisará ser pareada de novo.`)) return;
      await api(`/devices/${id}`, { method: 'DELETE' });
    }
    await loadDevices();
  } catch (err) { fail(err); }
});

$('#pair-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  try {
    await api('/devices/pair', {
      method: 'POST',
      body: { code: $('#pair-code').value, name: $('#pair-name').value, playlistId: $('#pair-playlist').value || null },
    });
    $('#pair-form').reset();
    toast('TV conectada! Ela começa a exibir em instantes.');
    await loadDevices();
  } catch (err) { fail(err); }
});

setInterval(() => {
  if (!$('#app').classList.contains('hidden') && state.view === 'devices' && !document.activeElement?.closest('.device, #pair-form')) {
    loadDevices().catch(() => {});
  }
}, 15000);

/** Texto do estado de atualização reportado pela TV. */
function updateLabel(d) {
  const u = d.lastStatus?.update || {};
  const target = u.version ? ` ${esc(u.version)}` : '';
  switch (u.state) {
    case 'DOWNLOADING': return `<span class="tag">baixando${target}${u.progress != null ? ` ${u.progress}%` : ''}</span>`;
    case 'READY': return `<span class="tag ok">pronta para instalar${target}</span>`;
    case 'INSTALLING': return `<span class="tag warn">aguardando OK na TV${target}</span>`;
    case 'ERROR': return `<span class="tag err">erro: ${esc(u.error || 'desconhecido')}</span>`;
    default: return d.updateAvailable ? '<span class="tag">disponível (a TV baixa na próxima sincronização)</span>' : '';
  }
}

// ===================================================================== Atualização do app

async function loadReleases() {
  const [releases, devices] = await Promise.all([api('/releases'), api('/devices')]);
  state.devices = devices;
  const latest = releases.find((r) => r.latest);
  $('#releases').innerHTML = releases.length ? releases.map((r) => `
    <div class="row">
      <div class="grow"><strong>${esc(r.versionName)}</strong> <span class="muted small">build ${r.versionCode} • ${bytes(r.size)} • ${new Date(r.createdAt).toLocaleString('pt-BR')}</span></div>
      ${r.latest ? '<span class="tag ok">atual</span>' : ''}
      <button class="btn small danger" data-release-delete="${r.id}" data-name="${esc(r.versionName)}">Excluir</button>
    </div>`).join('') : '<div class="empty small">Nenhuma versão enviada ainda.</div>';

  const tvs = devices.filter((d) => d.status === 'approved');
  $('#release-devices').innerHTML = tvs.length ? tvs.map((d) => `
    <div class="row">
      <div class="grow"><strong>${esc(d.name)}</strong> <span class="muted small">${d.online ? '● online' : '○ offline'} • versão ${esc(d.appVersion || '?')}</span>
        <div>${updateLabel(d) || (latest ? '<span class="tag ok">atualizada</span>' : '')}</div></div>
      ${d.updateAvailable ? `<button class="btn small primary" data-update="${d.id}">Instalar nesta TV</button>` : ''}
    </div>`).join('') : '<div class="empty small">Nenhuma TV conectada.</div>';
  $('#update-all').disabled = !tvs.some((d) => d.updateAvailable);
}

$('#view-releases').addEventListener('click', async (e) => {
  const del = e.target.closest('[data-release-delete]');
  const upd = e.target.closest('[data-update]');
  try {
    if (del) {
      if (!confirm(`Excluir a versão ${del.dataset.name}? TVs que ainda não instalaram deixam de recebê-la.`)) return;
      await api(`/releases/${del.dataset.releaseDelete}`, { method: 'DELETE' });
    } else if (upd) {
      await api(`/devices/${upd.dataset.update}/command`, { method: 'POST', body: { command: 'update_app' } });
      toast('Pedido enviado. Em até 1 minuto a TV mostra a confirmação: no controle, aperte ← e OK (Atualizar).');
    } else if (e.target.id === 'update-all') {
      const r = await api('/devices/update-all', { method: 'POST' });
      toast(`Pedido enviado para ${r.devices} TV(s). Em cada uma, aperte ← e OK (Atualizar) no controle.`);
    } else return;
    await loadReleases();
  } catch (err) { fail(err); }
});

const apkDrop = $('#apk-drop');
$('#apk-input').addEventListener('change', (e) => { if (e.target.files[0]) uploadApk(e.target.files[0]); e.target.value = ''; });
['dragenter', 'dragover'].forEach((ev) => apkDrop.addEventListener(ev, (e) => { e.preventDefault(); apkDrop.classList.add('over'); }));
['dragleave', 'drop'].forEach((ev) => apkDrop.addEventListener(ev, (e) => { e.preventDefault(); apkDrop.classList.remove('over'); }));
apkDrop.addEventListener('drop', (e) => { if (e.dataTransfer.files[0]) uploadApk(e.dataTransfer.files[0]); });

function uploadApk(file) {
  const row = document.createElement('div');
  row.className = 'upload';
  row.innerHTML = `<span>${esc(file.name)} — ${bytes(file.size)}</span> <span class="pct">0%</span><div class="bar"><span></span></div>`;
  $('#apk-upload').prepend(row);
  const xhr = new XMLHttpRequest();
  xhr.open('PUT', `/api/releases?name=${encodeURIComponent(file.name)}`);
  xhr.setRequestHeader('X-Requested-With', 'signage');
  xhr.setRequestHeader('Content-Type', 'application/octet-stream');
  xhr.upload.onprogress = (e) => {
    if (!e.lengthComputable) return;
    const pct = Math.round((e.loaded / e.total) * 100);
    row.querySelector('.pct').textContent = `${pct}%`;
    row.querySelector('.bar span').style.width = `${pct}%`;
  };
  xhr.onload = () => {
    const data = JSON.parse(xhr.responseText || '{}');
    if (xhr.status < 300) {
      row.classList.add('done');
      row.querySelector('.pct').textContent = `versão ${data.versionName} (build ${data.versionCode}) enviada ✓`;
      toast(`Versão ${data.versionName} disponível. As TVs vão baixá-la em segundo plano.`);
      loadReleases().catch(fail);
    } else {
      row.classList.add('failed');
      row.querySelector('.pct').textContent = `falhou: ${data.error || xhr.status}`;
    }
  };
  xhr.onerror = () => { row.classList.add('failed'); row.querySelector('.pct').textContent = 'falha de conexão'; };
  xhr.send(file);
}

// ===================================================================== Playlists

async function loadPlaylists() {
  state.playlists = await api('/playlists');
}

function renderPlaylistList() {
  const list = $('#playlist-list');
  if (!state.playlists.length) {
    list.innerHTML = '<div class="empty small">Nenhuma playlist.<br>Clique em "+ Nova playlist".</div>';
    $('#playlist-editor').innerHTML = '<div class="empty">Crie uma playlist e adicione mídias a ela. Depois escolha a playlist de cada TV na aba "TVs".</div>';
    return;
  }
  list.innerHTML = state.playlists.map((p) => `
    <button class="list-item ${state.current?.id === p.id ? 'active' : ''}" data-id="${p.id}">
      ${esc(p.name)}<small>${p.itemCount} item(ns) • ${p.deviceCount} TV(s)</small>
    </button>`).join('');
  if (!state.current) openPlaylist(state.playlists[0].id);
}

$('#playlist-list').addEventListener('click', (e) => {
  const btn = e.target.closest('.list-item');
  if (!btn) return;
  if (state.dirty && !confirm('Há alterações não salvas. Descartar?')) return;
  openPlaylist(Number(btn.dataset.id));
});

$('#new-playlist').addEventListener('click', async () => {
  const name = prompt('Nome da nova playlist:', 'Playlist da loja');
  if (!name) return;
  try {
    const p = await api('/playlists', { method: 'POST', body: { name } });
    await loadPlaylists();
    await openPlaylist(p.id);
  } catch (err) { fail(err); }
});

async function openPlaylist(id) {
  try {
    state.current = await api(`/playlists/${id}`);
    state.dirty = false;
    renderPlaylistList();
    renderEditor();
  } catch (err) { fail(err); }
}

function markDirty() {
  state.dirty = true;
  const el = $('#dirty-flag');
  if (el) el.textContent = '● Alterações não salvas';
}

function renderEditor() {
  const p = state.current;
  if (!p) return;
  const total = p.items.filter((i) => i.enabled)
    .reduce((sum, i) => sum + (i.type === 'IMAGE' ? i.durationSec * 1000 : (i.mediaDurationMs || 0)), 0);
  $('#playlist-editor').innerHTML = `
    <div class="editor-head">
      <input id="playlist-name" value="${esc(p.name)}" maxlength="80" aria-label="Nome da playlist">
      <button class="btn" id="add-media">+ Adicionar mídias</button>
      <button class="btn danger" id="delete-playlist">Excluir playlist</button>
    </div>
    <div class="items" id="items">
      ${p.items.length ? p.items.map(itemRow).join('') : '<div class="empty">Playlist vazia. Clique em "+ Adicionar mídias".</div>'}
    </div>
    <div class="editor-foot">
      <span class="muted small">${p.items.length} item(ns) • ciclo de ~${duration(total)} • arraste ⠿ para reordenar</span>
      <span><span id="dirty-flag" class="dirty">${state.dirty ? '● Alterações não salvas' : ''}</span>
      <button class="btn primary" id="save-playlist">Salvar playlist</button></span>
    </div>`;
}

function itemRow(i, index) {
  const isImage = i.type === 'IMAGE';
  return `<div class="item ${i.enabled ? '' : 'disabled'}" draggable="true" data-index="${index}">
    <span class="handle" title="Arraste para reordenar">⠿</span>
    ${preview(i, 'thumb')}
    <div style="min-width:0">
      <div class="item-name">${index + 1}. ${esc(i.name)}</div>
      <div class="item-meta">${isImage ? 'Imagem' : `Vídeo • ${duration(i.mediaDurationMs)} (duração do próprio vídeo)`}</div>
    </div>
    <div class="item-controls">
      ${isImage ? `<label>Exibir <input type="number" min="1" max="3600" value="${i.durationSec}" data-field="durationSec"> s</label>` : ''}
      <label><input type="checkbox" ${i.enabled ? 'checked' : ''} data-field="enabled"> Ativo</label>
      <button class="btn small" data-move="-1" title="Subir">▲</button>
      <button class="btn small" data-move="1" title="Descer">▼</button>
      <button class="btn small danger" data-remove title="Remover da playlist">✕</button>
    </div>
  </div>`;
}

const editor = $('#playlist-editor');

editor.addEventListener('input', (e) => {
  const row = e.target.closest('.item');
  if (e.target.id === 'playlist-name') return markDirty();
  if (!row) return;
  const item = state.current.items[Number(row.dataset.index)];
  if (e.target.dataset.field === 'durationSec') item.durationSec = Math.max(1, Number(e.target.value) || 1);
  if (e.target.dataset.field === 'enabled') {
    item.enabled = e.target.checked;
    row.classList.toggle('disabled', !item.enabled);
  }
  markDirty();
});

editor.addEventListener('click', async (e) => {
  const row = e.target.closest('.item');
  const items = state.current?.items;
  if (e.target.closest('[data-move]') && row) {
    const from = Number(row.dataset.index);
    const to = from + Number(e.target.closest('[data-move]').dataset.move);
    if (to < 0 || to >= items.length) return;
    items.splice(to, 0, items.splice(from, 1)[0]);
    markDirty();
    renderEditor();
  } else if (e.target.closest('[data-remove]') && row) {
    items.splice(Number(row.dataset.index), 1);
    markDirty();
    renderEditor();
  } else if (e.target.id === 'add-media') {
    openPicker();
  } else if (e.target.id === 'delete-playlist') {
    if (!confirm(`Excluir a playlist "${state.current.name}"? As TVs que a usam ficarão sem conteúdo do servidor.`)) return;
    try {
      await api(`/playlists/${state.current.id}`, { method: 'DELETE' });
      state.current = null;
      state.dirty = false;
      await loadPlaylists();
      renderPlaylistList();
    } catch (err) { fail(err); }
  } else if (e.target.id === 'save-playlist') {
    await savePlaylist();
  }
});

async function savePlaylist() {
  const p = state.current;
  const name = $('#playlist-name').value.trim() || p.name;
  try {
    await api(`/playlists/${p.id}`, { method: 'PATCH', body: { name } });
    await api(`/playlists/${p.id}/items`, {
      method: 'PUT',
      body: { items: p.items.map((i) => ({ mediaId: i.mediaId, durationSec: i.durationSec, enabled: i.enabled })) },
    });
    state.dirty = false;
    await loadPlaylists();
    await openPlaylist(p.id);
    toast('Playlist salva. As TVs atualizam em até 1 minuto.');
  } catch (err) { fail(err); }
}

// Arrastar e soltar para reordenar
let dragIndex = null;
editor.addEventListener('dragstart', (e) => {
  const row = e.target.closest('.item');
  if (!row) return;
  dragIndex = Number(row.dataset.index);
  row.classList.add('dragging');
  e.dataTransfer.effectAllowed = 'move';
});
editor.addEventListener('dragover', (e) => {
  const row = e.target.closest('.item');
  if (dragIndex === null || !row) return;
  e.preventDefault();
  editor.querySelectorAll('.drop-target').forEach((r) => r.classList.remove('drop-target'));
  row.classList.add('drop-target');
});
editor.addEventListener('drop', (e) => {
  const row = e.target.closest('.item');
  if (dragIndex === null || !row) return;
  e.preventDefault();
  const to = Number(row.dataset.index);
  const items = state.current.items;
  items.splice(to, 0, items.splice(dragIndex, 1)[0]);
  dragIndex = null;
  markDirty();
  renderEditor();
});
editor.addEventListener('dragend', () => {
  dragIndex = null;
  editor.querySelectorAll('.dragging, .drop-target').forEach((r) => r.classList.remove('dragging', 'drop-target'));
});

// Seletor de mídias
const picker = $('#picker');
let pickerSelection = [];

async function openPicker() {
  await loadMedia();
  pickerSelection = [];
  updatePickerCount();
  $('#picker-grid').innerHTML = state.media.length ? state.media.map((m) => `
    <div class="media-card selectable" data-id="${m.id}">
      <span class="type-tag">${m.type === 'IMAGE' ? 'IMG' : 'VÍDEO'}</span>
      ${preview(m)}
      <div class="info"><span class="name">${esc(m.name)}</span></div>
    </div>`).join('') : '<div class="empty">Nenhuma mídia enviada ainda. Use a aba "Mídias".</div>';
  picker.showModal();
}

$('#picker-grid').addEventListener('click', (e) => {
  const card = e.target.closest('.media-card');
  if (!card) return;
  const id = card.dataset.id;
  const i = pickerSelection.indexOf(id);
  if (i >= 0) pickerSelection.splice(i, 1); else pickerSelection.push(id);
  card.classList.toggle('selected', i < 0);
  updatePickerCount();
});

function updatePickerCount() {
  $('#picker-count').textContent = pickerSelection.length ? `${pickerSelection.length} selecionada(s)` : 'Nenhuma selecionada';
}

picker.addEventListener('close', () => {
  if (picker.returnValue !== 'add' || !pickerSelection.length) return;
  for (const id of pickerSelection) {
    const m = state.media.find((x) => x.id === id);
    if (m) state.current.items.push({ mediaId: m.id, name: m.name, type: m.type, url: m.url, durationSec: 10, enabled: true, mediaDurationMs: m.durationMs });
  }
  markDirty();
  renderEditor();
  toast('Mídias adicionadas. Clique em "Salvar playlist" para enviar às TVs.');
});

// ===================================================================== Mídias

async function loadMedia() {
  state.media = await api('/media');
  renderMedia();
}

function renderMedia() {
  const total = state.media.reduce((s, m) => s + (m.size || 0), 0);
  $('#media-summary').textContent = `${state.media.length} arquivo(s) • ${bytes(total)}`;
  $('#media-grid').innerHTML = state.media.length ? state.media.map((m) => `
    <div class="media-card" data-id="${m.id}">
      <span class="type-tag">${m.type === 'IMAGE' ? 'IMG' : 'VÍDEO'}</span>
      ${preview(m)}
      <div class="info">
        <span class="name" title="${esc(m.name)}">${esc(m.name)}</span>
        <span class="muted">${m.width ? `${m.width}x${m.height} • ` : ''}${m.type === 'VIDEO' ? `${duration(m.durationMs)} • ` : ''}${bytes(m.size)}</span>
        <span class="muted">${m.usedIn ? `Em ${m.usedIn} playlist(s)` : 'Não usada em playlists'}</span>
      </div>
      <div class="actions">
        <button class="btn small" data-act="rename">Renomear</button>
        <button class="btn small danger" data-act="delete">Excluir</button>
      </div>
    </div>`).join('') : '<div class="empty">Nenhuma mídia enviada ainda.</div>';
}

$('#media-grid').addEventListener('click', async (e) => {
  const btn = e.target.closest('button[data-act]');
  if (!btn) return;
  const m = state.media.find((x) => x.id === btn.closest('.media-card').dataset.id);
  try {
    if (btn.dataset.act === 'rename') {
      const name = prompt('Nome da mídia:', m.name);
      if (!name) return;
      await api(`/media/${m.id}`, { method: 'PATCH', body: { name } });
    } else {
      const warn = m.usedIn ? ` Ela será removida de ${m.usedIn} playlist(s).` : '';
      if (!confirm(`Excluir "${m.name}"?${warn}`)) return;
      await api(`/media/${m.id}`, { method: 'DELETE' });
      state.current = null;
      await loadPlaylists();
    }
    await loadMedia();
  } catch (err) { fail(err); }
});

// Upload com arrastar/soltar e barra de progresso
const dropzone = $('#dropzone');
$('#file-input').addEventListener('change', (e) => { uploadFiles([...e.target.files]); e.target.value = ''; });
['dragenter', 'dragover'].forEach((ev) => dropzone.addEventListener(ev, (e) => { e.preventDefault(); dropzone.classList.add('over'); }));
['dragleave', 'drop'].forEach((ev) => dropzone.addEventListener(ev, (e) => { e.preventDefault(); dropzone.classList.remove('over'); }));
dropzone.addEventListener('drop', (e) => uploadFiles([...e.dataTransfer.files]));

/** Lê dimensões/duração no navegador (o servidor não precisa de ffmpeg). */
function readMetadata(file) {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file);
    let finished = false;
    let video = null;
    const done = (meta) => {
      if (finished) return;
      finished = true;
      if (video) { video.removeAttribute('src'); video.load(); } // para a leitura antes de liberar a URL
      URL.revokeObjectURL(url);
      resolve(meta);
    };
    if (file.type.startsWith('image/')) {
      const img = new Image();
      img.onload = () => done({ width: img.naturalWidth, height: img.naturalHeight });
      img.onerror = () => done({});
      img.src = url;
    } else if (file.type.startsWith('video/')) {
      const v = document.createElement('video');
      video = v;
      v.preload = 'metadata';
      v.onloadedmetadata = () => done({ width: v.videoWidth, height: v.videoHeight, durationMs: Math.round(v.duration * 1000) });
      v.onerror = () => done({});
      v.src = url;
    } else done({});
    setTimeout(() => done({}), 10000);
  });
}

async function uploadFiles(files) {
  for (const file of files) {
    const row = document.createElement('div');
    row.className = 'upload';
    row.innerHTML = `<span>${esc(file.name)} — ${bytes(file.size)}</span> <span class="pct">0%</span><div class="bar"><span></span></div>`;
    $('#uploads').prepend(row);
    try {
      const meta = await readMetadata(file);
      const params = new URLSearchParams({ name: file.name });
      Object.entries(meta).forEach(([k, v]) => v && params.set(k, v));
      await new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open('PUT', `/api/media?${params}`);
        xhr.setRequestHeader('X-Requested-With', 'signage');
        xhr.setRequestHeader('Content-Type', 'application/octet-stream');
        xhr.upload.onprogress = (e) => {
          if (!e.lengthComputable) return;
          const pct = Math.round((e.loaded / e.total) * 100);
          row.querySelector('.pct').textContent = `${pct}%`;
          row.querySelector('.bar span').style.width = `${pct}%`;
        };
        xhr.onload = () => (xhr.status < 300 ? resolve() : reject(new Error(JSON.parse(xhr.responseText || '{}').error || `Erro ${xhr.status}`)));
        xhr.onerror = () => reject(new Error('Falha de conexão'));
        xhr.send(file);
      });
      row.classList.add('done');
      row.querySelector('.pct').textContent = 'enviado ✓';
      row.querySelector('.bar span').style.width = '100%';
      setTimeout(() => row.remove(), 4000);
    } catch (err) {
      row.classList.add('failed');
      row.querySelector('.pct').textContent = `falhou: ${err.message}`;
    }
  }
  await loadMedia();
}

// ===================================================================== Início

api('/me').then((r) => (r.authenticated ? showApp() : showLogin())).catch(showLogin);
