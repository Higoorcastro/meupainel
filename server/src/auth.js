import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { config } from './config.js';

const COOKIE = 'signage_session';
const SESSION_DAYS = 30;

function loadSecret() {
  if (config.sessionSecret) return config.sessionSecret;
  const file = path.join(config.dataDir, '.session-secret');
  if (fs.existsSync(file)) return fs.readFileSync(file, 'utf8').trim();
  const secret = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(file, secret, { mode: 0o600 });
  return secret;
}
const secret = loadSecret();

const sign = (value) => crypto.createHmac('sha256', secret).update(value).digest('base64url');

export const sha256 = (value) => crypto.createHash('sha256').update(value).digest('hex');

function safeEqual(a, b) {
  const ba = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  return ba.length === bb.length && crypto.timingSafeEqual(ba, bb);
}

export function checkPassword(password) {
  if (!config.adminPassword) return false;
  return safeEqual(sha256(password || ''), sha256(config.adminPassword));
}

function readCookie(req, name) {
  const header = req.headers.cookie || '';
  for (const part of header.split(';')) {
    const [k, ...v] = part.trim().split('=');
    if (k === name) return decodeURIComponent(v.join('='));
  }
  return null;
}

export function setSessionCookie(req, res) {
  const expires = Date.now() + SESSION_DAYS * 86400_000;
  const value = `${expires}.${sign(String(expires))}`;
  const secure = req.secure ? '; Secure' : '';
  res.setHeader('Set-Cookie', `${COOKIE}=${value}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${SESSION_DAYS * 86400}${secure}`);
}

export function clearSessionCookie(res) {
  res.setHeader('Set-Cookie', `${COOKIE}=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0`);
}

export function isAuthenticated(req) {
  const value = readCookie(req, COOKIE);
  if (!value) return false;
  const [expires, mac] = value.split('.');
  return Number(expires) > Date.now() && safeEqual(mac, sign(expires));
}

/**
 * Middleware do painel: exige sessão válida. Requisições que alteram dados também precisam do
 * cabeçalho X-Requested-With (bloqueia CSRF por formulários de outros sites).
 */
export function requireAdmin(req, res, next) {
  if (!isAuthenticated(req)) return res.status(401).json({ error: 'Não autenticado' });
  if (req.method !== 'GET' && req.headers['x-requested-with'] !== 'signage') {
    return res.status(403).json({ error: 'Requisição inválida' });
  }
  next();
}

/** Limita tentativas de login por IP (5 erros a cada 15 minutos). */
const failures = new Map();
export function loginAllowed(ip) {
  const entry = failures.get(ip);
  if (!entry) return true;
  if (Date.now() - entry.first > 15 * 60_000) {
    failures.delete(ip);
    return true;
  }
  return entry.count < 5;
}
export function registerLoginFailure(ip) {
  const entry = failures.get(ip);
  if (!entry || Date.now() - entry.first > 15 * 60_000) failures.set(ip, { first: Date.now(), count: 1 });
  else entry.count++;
}
export function clearLoginFailures(ip) {
  failures.delete(ip);
}
