import path from 'node:path';
import { fileURLToPath } from 'node:url';
import express from 'express';
import { config, mediaDir } from './config.js';
import './db.js';
import { adminApi } from './adminApi.js';
import { deviceApi } from './deviceApi.js';

const app = express();
const publicDir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'public');

// Atrás de um proxy HTTPS (Caddy/nginx): confia em X-Forwarded-* para req.ip, req.protocol e req.secure.
app.set('trust proxy', 'loopback, linklocal, uniquelocal');
app.disable('x-powered-by');
app.use((req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'same-origin');
  next();
});

app.get('/health', (req, res) => res.json({ ok: true }));
app.use('/api/device', deviceApi);
app.use('/api', adminApi);

// Arquivos de mídia: nomes aleatórios (UUID) e imutáveis → cache longo; suporte a Range (streaming de vídeo).
app.use('/media', express.static(mediaDir, { immutable: true, maxAge: '365d', index: false, dotfiles: 'ignore' }));
app.use(express.static(publicDir, { index: 'index.html' }));

app.use((err, req, res, next) => {
  console.error('[erro]', err);
  if (!res.headersSent) res.status(500).json({ error: 'Erro interno do servidor' });
});

if (!config.adminPassword) {
  console.warn('[aviso] ADMIN_PASSWORD não definida: o login no painel ficará bloqueado até configurá-la.');
}

app.listen(config.port, () => {
  console.log(`[server] Digital Signage rodando na porta ${config.port} (dados em ${config.dataDir})`);
});
