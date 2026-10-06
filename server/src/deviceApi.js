import crypto from 'node:crypto';
import path from 'node:path';
import express from 'express';
import { config, releasesDir } from './config.js';
import { db, now } from './db.js';
import { sha256 } from './auth.js';

export const DEFAULT_SETTINGS = {
  imageScaleMode: 'CENTER_CROP', // ou FIT_CENTER
  transition: 'FADE', // ou NONE
  transitionDurationMs: 800,
  videoMuted: false,
};

const newPairingCode = () => String(crypto.randomInt(0, 1_000_000)).padStart(6, '0');

export function baseUrl(req) {
  return config.publicUrl || `${req.protocol}://${req.get('host')}`;
}

/** Versão mais recente do app enviada pelo painel (ou undefined). */
export function latestRelease() {
  return db.prepare('SELECT * FROM app_releases ORDER BY version_code DESC LIMIT 1').get();
}

/** Playlist no formato consumido pela TV. O id "<mídia>#<n>" fica estável ao reordenar (evita novos downloads). */
export function buildPlaylist(playlistId, req) {
  if (!playlistId) return [];
  const rows = db.prepare(`
    SELECT i.duration_sec, i.enabled, m.id AS media_id, m.name, m.type, m.filename, m.size
    FROM playlist_items i JOIN media m ON m.id = i.media_id
    WHERE i.playlist_id = ? ORDER BY i.position, i.id
  `).all(playlistId);
  const seen = new Map();
  const base = baseUrl(req);
  return rows.map((r) => {
    const n = (seen.get(r.media_id) || 0) + 1;
    seen.set(r.media_id, n);
    return {
      id: `${r.media_id}#${n}`,
      name: r.name,
      type: r.type,
      url: `${base}/media/${r.filename}`,
      durationSec: r.duration_sec,
      enabled: !!r.enabled,
      size: r.size,
    };
  });
}

/** Lê X-Device-Id / Authorization: Bearer. Retorna { deviceId, tokenHash } ou null se malformado. */
function readCredentials(req) {
  const deviceId = String(req.get('x-device-id') || '').trim();
  const token = String(req.get('authorization') || '').replace(/^Bearer\s+/i, '').trim();
  if (!/^[A-Za-z0-9-]{8,64}$/.test(deviceId) || token.length < 32) return null;
  return { deviceId, tokenHash: sha256(token) };
}

export const deviceApi = express.Router();

/**
 * Único endpoint usado pela TV (chamado a cada ~60s). Ao mesmo tempo:
 * - registra a TV (primeiro contato → "pending" com código de pareamento exibido na tela da TV);
 * - recebe o status atual (heartbeat: mídia em exibição, armazenamento, erros, atualização...);
 * - devolve a playlist, as configurações, a atualização disponível e um comando pendente (se houver).
 *
 * Autenticação: cabeçalhos X-Device-Id e Authorization: Bearer <token>. O token é gerado pela própria
 * TV e só o seu hash é guardado; um id já aprovado não pode ser assumido por outro aparelho.
 */
deviceApi.post('/sync', express.json({ limit: '64kb' }), (req, res) => {
  const cred = readCredentials(req);
  if (!cred) return res.status(400).json({ error: 'Identificação da TV inválida' });
  const { deviceId, tokenHash } = cred;
  const body = req.body || {};
  let device = db.prepare('SELECT * FROM devices WHERE id = ?').get(deviceId);

  if (!device) {
    const code = newPairingCode();
    db.prepare(`INSERT INTO devices (id, token_hash, name, status, pairing_code, created_at)
                VALUES (?, ?, ?, 'pending', ?, ?)`)
      .run(deviceId, tokenHash, String(body.model || 'Nova TV').slice(0, 60), code, now());
    device = db.prepare('SELECT * FROM devices WHERE id = ?').get(deviceId);
    console.log(`[device] nova TV aguardando pareamento: ${deviceId} (código ${code})`);
  } else if (device.token_hash !== tokenHash) {
    if (device.status !== 'pending') return res.status(403).json({ error: 'Token inválido para esta TV' });
    // TV reinstalada antes de ser aprovada: aceita o novo token e gera um novo código.
    db.prepare('UPDATE devices SET token_hash = ?, pairing_code = ? WHERE id = ?').run(tokenHash, newPairingCode(), deviceId);
    device = db.prepare('SELECT * FROM devices WHERE id = ?').get(deviceId);
  }

  const versionCode = Number.isInteger(body.appVersionCode) ? body.appVersionCode : null;
  db.prepare(`UPDATE devices SET last_seen = ?, last_ip = ?, model = ?, app_version = ?, app_version_code = ?, last_status = ? WHERE id = ?`)
    .run(now(), req.ip, String(body.model || '').slice(0, 100), String(body.appVersion || '').slice(0, 40),
      versionCode, JSON.stringify(body.status || {}).slice(0, 8000), deviceId);

  if (device.status === 'blocked') return res.status(403).json({ status: 'blocked', error: 'TV bloqueada no painel' });
  if (device.status === 'pending') return res.json({ status: 'pending', pairingCode: device.pairing_code });

  const command = device.command;
  if (command) db.prepare('UPDATE devices SET command = NULL WHERE id = ?').run(deviceId);

  const release = latestRelease();
  const appUpdate = release && versionCode !== null && release.version_code > versionCode
    ? {
      versionCode: release.version_code,
      versionName: release.version_name,
      size: release.size,
      sha256: release.sha256,
      downloadPath: `/api/device/releases/${release.id}`,
    }
    : null;

  res.json({
    status: 'approved',
    name: device.name,
    playlist: buildPlaylist(device.playlist_id, req),
    settings: { ...DEFAULT_SETTINGS, ...JSON.parse(device.settings_json || '{}') },
    appUpdate,
    command: command || null,
    serverTime: now(),
  });
});

/** Download do APK de atualização — somente para TVs aprovadas. */
deviceApi.get('/releases/:id', (req, res) => {
  const cred = readCredentials(req);
  const device = cred && db.prepare('SELECT status, token_hash FROM devices WHERE id = ?').get(cred.deviceId);
  if (!device || device.token_hash !== cred.tokenHash || device.status !== 'approved') {
    return res.status(403).json({ error: 'TV não autorizada' });
  }
  const release = db.prepare('SELECT * FROM app_releases WHERE id = ?').get(req.params.id);
  if (!release) return res.status(404).json({ error: 'Versão não encontrada' });
  res.setHeader('Content-Type', 'application/vnd.android.package-archive');
  res.sendFile(path.join(releasesDir, release.filename));
});
