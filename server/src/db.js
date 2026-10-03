import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { config, mediaDir } from './config.js';

fs.mkdirSync(mediaDir, { recursive: true });

export const db = new DatabaseSync(path.join(config.dataDir, 'signage.db'));
db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;');

/**
 * Migrações versionadas (PRAGMA user_version). Para evoluir o esquema, adicione um novo item
 * ao final da lista — nunca altere os anteriores.
 */
const migrations = [
  `
  CREATE TABLE media (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    type        TEXT NOT NULL CHECK (type IN ('IMAGE','VIDEO')),
    filename    TEXT NOT NULL UNIQUE,
    mime        TEXT,
    size        INTEGER NOT NULL DEFAULT 0,
    width       INTEGER,
    height      INTEGER,
    duration_ms INTEGER,
    created_at  INTEGER NOT NULL
  );
  CREATE TABLE playlists (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
  );
  CREATE TABLE playlist_items (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    playlist_id  INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
    media_id     TEXT NOT NULL REFERENCES media(id) ON DELETE CASCADE,
    position     INTEGER NOT NULL,
    duration_sec INTEGER NOT NULL DEFAULT 10,
    enabled      INTEGER NOT NULL DEFAULT 1
  );
  CREATE INDEX idx_items_playlist ON playlist_items(playlist_id, position);
  CREATE TABLE devices (
    id            TEXT PRIMARY KEY,
    token_hash    TEXT NOT NULL,
    name          TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','blocked')),
    pairing_code  TEXT,
    playlist_id   INTEGER REFERENCES playlists(id) ON DELETE SET NULL,
    settings_json TEXT NOT NULL DEFAULT '{}',
    command       TEXT,
    model         TEXT,
    app_version   TEXT,
    last_status   TEXT,
    last_seen     INTEGER,
    last_ip       TEXT,
    created_at    INTEGER NOT NULL
  );
  `,
];

const current = db.prepare('PRAGMA user_version').get().user_version;
for (let v = current; v < migrations.length; v++) {
  db.exec('BEGIN');
  try {
    db.exec(migrations[v]);
    db.exec(`PRAGMA user_version = ${v + 1}`);
    db.exec('COMMIT');
    console.log(`[db] migração ${v + 1} aplicada`);
  } catch (e) {
    db.exec('ROLLBACK');
    throw e;
  }
}

/** Executa fn dentro de uma transação. */
export function tx(fn) {
  db.exec('BEGIN');
  try {
    const result = fn();
    db.exec('COMMIT');
    return result;
  } catch (e) {
    db.exec('ROLLBACK');
    throw e;
  }
}

export const now = () => Date.now();
