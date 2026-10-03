import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import express from 'express';
import { config, mediaDir } from './config.js';
import { db, now, tx } from './db.js';
import {
  checkPassword, clearLoginFailures, clearSessionCookie, isAuthenticated, loginAllowed,
  registerLoginFailure, requireAdmin, setSessionCookie,
} from './auth.js';
import { DEFAULT_SETTINGS } from './deviceApi.js';

export const adminApi = express.Router();
const json = express.json({ limit: '1mb' });

const EXTENSIONS = {
  jpg: ['IMAGE', 'image/jpeg'], jpeg: ['IMAGE', 'image/jpeg'], png: ['IMAGE', 'image/png'], webp: ['IMAGE', 'image/webp'],
  mp4: ['VIDEO', 'video/mp4'], m4v: ['VIDEO', 'video/mp4'], mov: ['VIDEO', 'video/quicktime'],
  mkv: ['VIDEO', 'video/x-matroska'], webm: ['VIDEO', 'video/webm'],
};
const COMMANDS = new Set(['restart_playlist', 'sync_now']);
const int = (v) => (v === undefined || v === null || v === '' ? null : Math.round(Number(v)) || null);

// ------------------------------------------------------------------ Sessão

adminApi.post('/login', json, (req, res) => {
  if (!config.adminPassword) return res.status(500).json({ error: 'ADMIN_PASSWORD não configurada no servidor' });
  if (!loginAllowed(req.ip)) return res.status(429).json({ error: 'Muitas tentativas. Aguarde 15 minutos.' });
  if (!checkPassword(req.body?.password)) {
    registerLoginFailure(req.ip);
    return res.status(401).json({ error: 'Senha incorreta' });
  }
  clearLoginFailures(req.ip);
  setSessionCookie(req, res);
  res.json({ ok: true });
});

adminApi.post('/logout', (req, res) => {
  clearSessionCookie(res);
  res.json({ ok: true });
});

adminApi.get('/me', (req, res) => res.json({ authenticated: isAuthenticated(req) }));

adminApi.use(requireAdmin);

// ------------------------------------------------------------------ Mídias

const mediaRow = (m) => ({
  id: m.id, name: m.name, type: m.type, url: `/media/${m.filename}`, mime: m.mime, size: m.size,
  width: m.width, height: m.height, durationMs: m.duration_ms, createdAt: m.created_at,
  usedIn: m.used_in ?? 0,
});

adminApi.get('/media', (req, res) => {
  const rows = db.prepare(`
    SELECT m.*, (SELECT COUNT(DISTINCT playlist_id) FROM playlist_items i WHERE i.media_id = m.id) AS used_in
    FROM media m ORDER BY m.created_at DESC`).all();
  res.json(rows.map(mediaRow));
});

/**
 * Upload em streaming: o corpo da requisição é o próprio arquivo (sem multipart), gravado direto em disco.
 * Metadados (nome, dimensões, duração) vêm na query string — calculados pelo navegador.
 */
adminApi.put('/media', async (req, res) => {
  const original = String(req.query.name || '').trim();
  const ext = path.extname(original).slice(1).toLowerCase();
  const info = EXTENSIONS[ext];
  if (!info) return res.status(400).json({ error: 'Formato não suportado. Use JPG, PNG, WebP ou MP4.' });
  const length = Number(req.get('content-length') || 0);
  const max = config.maxUploadMb * 1024 * 1024;
  if (length > max) return res.status(413).json({ error: `Arquivo maior que ${config.maxUploadMb} MB` });

  const id = crypto.randomUUID();
  const filename = `${id}.${ext}`;
  const tmp = path.join(mediaDir, `.upload-${filename}`);
  let received = 0;
  const counter = new Transform({
    transform(chunk, _enc, done) {
      received += chunk.length;
      done(received > max ? new Error('Arquivo muito grande') : null, chunk);
    },
  });
  try {
    await pipeline(req, counter, fs.createWriteStream(tmp));
    if (received === 0) throw new Error('Arquivo vazio');
    fs.renameSync(tmp, path.join(mediaDir, filename));
  } catch (e) {
    fs.rmSync(tmp, { force: true });
    if (!res.headersSent) res.status(400).json({ error: `Falha no upload: ${e.message}` });
    return;
  }
  const name = path.basename(original, path.extname(original)).slice(0, 120) || 'Mídia';
  db.prepare(`INSERT INTO media (id, name, type, filename, mime, size, width, height, duration_ms, created_at)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
    .run(id, name, info[0], filename, info[1], received, int(req.query.width), int(req.query.height),
      int(req.query.durationMs), now());
  console.log(`[media] upload: ${original} (${(received / 1048576).toFixed(1)} MB)`);
  res.status(201).json(mediaRow(db.prepare('SELECT * FROM media WHERE id = ?').get(id)));
});

adminApi.patch('/media/:id', json, (req, res) => {
  const name = String(req.body?.name || '').trim().slice(0, 120);
  if (!name) return res.status(400).json({ error: 'Nome obrigatório' });
  const r = db.prepare('UPDATE media SET name = ? WHERE id = ?').run(name, req.params.id);
  if (!r.changes) return res.status(404).json({ error: 'Mídia não encontrada' });
  touchPlaylistsUsing(req.params.id);
  res.json({ ok: true });
});

adminApi.delete('/media/:id', (req, res) => {
  const m = db.prepare('SELECT * FROM media WHERE id = ?').get(req.params.id);
  if (!m) return res.status(404).json({ error: 'Mídia não encontrada' });
  tx(() => {
    touchPlaylistsUsing(m.id);
    db.prepare('DELETE FROM media WHERE id = ?').run(m.id); // itens de playlist removidos em cascata
  });
  fs.rmSync(path.join(mediaDir, m.filename), { force: true });
  res.json({ ok: true });
});

function touchPlaylistsUsing(mediaId) {
  db.prepare(`UPDATE playlists SET updated_at = ? WHERE id IN (SELECT playlist_id FROM playlist_items WHERE media_id = ?)`)
    .run(now(), mediaId);
}

// ------------------------------------------------------------------ Playlists

adminApi.get('/playlists', (req, res) => {
  res.json(db.prepare(`
    SELECT p.id, p.name, p.updated_at AS updatedAt,
      (SELECT COUNT(*) FROM playlist_items i WHERE i.playlist_id = p.id) AS itemCount,
      (SELECT COUNT(*) FROM devices d WHERE d.playlist_id = p.id) AS deviceCount
    FROM playlists p ORDER BY p.name COLLATE NOCASE`).all());
});

adminApi.post('/playlists', json, (req, res) => {
  const name = String(req.body?.name || '').trim().slice(0, 80) || 'Nova playlist';
  const r = db.prepare('INSERT INTO playlists (name, created_at, updated_at) VALUES (?, ?, ?)').run(name, now(), now());
  res.status(201).json({ id: Number(r.lastInsertRowid), name });
});

adminApi.get('/playlists/:id', (req, res) => {
  const p = db.prepare('SELECT id, name FROM playlists WHERE id = ?').get(req.params.id);
  if (!p) return res.status(404).json({ error: 'Playlist não encontrada' });
  const items = db.prepare(`
    SELECT i.media_id AS mediaId, i.duration_sec AS durationSec, i.enabled, m.name, m.type, m.filename,
           m.duration_ms AS mediaDurationMs
    FROM playlist_items i JOIN media m ON m.id = i.media_id
    WHERE i.playlist_id = ? ORDER BY i.position, i.id`).all(p.id)
    .map((i) => ({ ...i, enabled: !!i.enabled, url: `/media/${i.filename}` }));
  res.json({ ...p, items });
});

adminApi.patch('/playlists/:id', json, (req, res) => {
  const name = String(req.body?.name || '').trim().slice(0, 80);
  if (!name) return res.status(400).json({ error: 'Nome obrigatório' });
  db.prepare('UPDATE playlists SET name = ?, updated_at = ? WHERE id = ?').run(name, now(), req.params.id);
  res.json({ ok: true });
});

/** Substitui a lista inteira de itens (ordem = ordem do array). */
adminApi.put('/playlists/:id/items', json, (req, res) => {
  const p = db.prepare('SELECT id FROM playlists WHERE id = ?').get(req.params.id);
  if (!p) return res.status(404).json({ error: 'Playlist não encontrada' });
  const items = Array.isArray(req.body?.items) ? req.body.items : null;
  if (!items) return res.status(400).json({ error: 'Lista de itens inválida' });
  const exists = db.prepare('SELECT 1 FROM media WHERE id = ?');
  tx(() => {
    db.prepare('DELETE FROM playlist_items WHERE playlist_id = ?').run(p.id);
    const insert = db.prepare(`INSERT INTO playlist_items (playlist_id, media_id, position, duration_sec, enabled) VALUES (?, ?, ?, ?, ?)`);
    items.forEach((it, index) => {
      if (!exists.get(String(it.mediaId))) return;
      const duration = Math.min(3600, Math.max(1, Math.round(Number(it.durationSec) || 10)));
      insert.run(p.id, String(it.mediaId), index, duration, it.enabled === false ? 0 : 1);
    });
    db.prepare('UPDATE playlists SET updated_at = ? WHERE id = ?').run(now(), p.id);
  });
  res.json({ ok: true });
});

adminApi.delete('/playlists/:id', (req, res) => {
  db.prepare('DELETE FROM playlists WHERE id = ?').run(req.params.id);
  res.json({ ok: true });
});

// ------------------------------------------------------------------ TVs

adminApi.get('/devices', (req, res) => {
  const onlineSince = now() - config.onlineWindowSec * 1000;
  const rows = db.prepare(`
    SELECT d.*, p.name AS playlist_name FROM devices d LEFT JOIN playlists p ON p.id = d.playlist_id
    ORDER BY d.status = 'pending' DESC, d.name COLLATE NOCASE`).all();
  res.json(rows.map((d) => ({
    id: d.id,
    name: d.name,
    status: d.status,
    playlistId: d.playlist_id,
    playlistName: d.playlist_name,
    settings: { ...DEFAULT_SETTINGS, ...JSON.parse(d.settings_json || '{}') },
    pendingCommand: d.command,
    model: d.model,
    appVersion: d.app_version,
    lastSeen: d.last_seen,
    lastIp: d.last_ip,
    online: !!d.last_seen && d.last_seen >= onlineSince,
    lastStatus: safeJson(d.last_status),
    createdAt: d.created_at,
  })));
});

/** Aprova uma TV digitando o código de pareamento exibido na tela dela. */
adminApi.post('/devices/pair', json, (req, res) => {
  const code = String(req.body?.code || '').replace(/\D/g, '');
  const device = db.prepare(`SELECT * FROM devices WHERE status = 'pending' AND pairing_code = ?`).get(code);
  if (!device) return res.status(404).json({ error: 'Nenhuma TV aguardando com este código. Confira o código na tela da TV.' });
  const name = String(req.body?.name || '').trim().slice(0, 60) || device.name;
  const playlistId = int(req.body?.playlistId);
  db.prepare(`UPDATE devices SET status = 'approved', pairing_code = NULL, name = ?, playlist_id = ? WHERE id = ?`)
    .run(name, playlistId, device.id);
  console.log(`[device] TV aprovada: ${name} (${device.id})`);
  res.json({ ok: true, id: device.id });
});

adminApi.patch('/devices/:id', json, (req, res) => {
  const d = db.prepare('SELECT * FROM devices WHERE id = ?').get(req.params.id);
  if (!d) return res.status(404).json({ error: 'TV não encontrada' });
  const b = req.body || {};
  const name = b.name !== undefined ? String(b.name).trim().slice(0, 60) || d.name : d.name;
  const playlistId = b.playlistId !== undefined ? int(b.playlistId) : d.playlist_id;
  let settings = JSON.parse(d.settings_json || '{}');
  if (b.settings && typeof b.settings === 'object') {
    const s = b.settings;
    if (['CENTER_CROP', 'FIT_CENTER'].includes(s.imageScaleMode)) settings.imageScaleMode = s.imageScaleMode;
    if (['FADE', 'NONE'].includes(s.transition)) settings.transition = s.transition;
    if (s.transitionDurationMs !== undefined) settings.transitionDurationMs = Math.min(3000, Math.max(0, int(s.transitionDurationMs) || 0));
    if (s.videoMuted !== undefined) settings.videoMuted = !!s.videoMuted;
  }
  const status = ['approved', 'blocked'].includes(b.status) ? b.status : d.status;
  db.prepare('UPDATE devices SET name = ?, playlist_id = ?, settings_json = ?, status = ? WHERE id = ?')
    .run(name, playlistId, JSON.stringify(settings), status, d.id);
  res.json({ ok: true });
});

adminApi.post('/devices/:id/command', json, (req, res) => {
  const command = String(req.body?.command || '');
  if (!COMMANDS.has(command)) return res.status(400).json({ error: 'Comando desconhecido' });
  const r = db.prepare('UPDATE devices SET command = ? WHERE id = ?').run(command, req.params.id);
  if (!r.changes) return res.status(404).json({ error: 'TV não encontrada' });
  res.json({ ok: true });
});

adminApi.delete('/devices/:id', (req, res) => {
  db.prepare('DELETE FROM devices WHERE id = ?').run(req.params.id);
  res.json({ ok: true });
});

function safeJson(text) {
  try {
    return text ? JSON.parse(text) : null;
  } catch {
    return null;
  }
}
