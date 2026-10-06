import path from 'node:path';

/** Configuração lida de variáveis de ambiente (veja .env.example). */
export const config = {
  port: Number(process.env.PORT || 3000),
  /** Pasta persistente com o banco e as mídias (volume do Docker). */
  dataDir: path.resolve(process.env.DATA_DIR || './data'),
  /** Senha do painel web. OBRIGATÓRIA em produção. */
  adminPassword: process.env.ADMIN_PASSWORD || '',
  /** Segredo usado para assinar o cookie de sessão. Se vazio, é gerado e salvo em data/. */
  sessionSecret: process.env.SESSION_SECRET || '',
  /** URL pública (ex.: https://signage.seudominio.com). Usada para montar os links das mídias enviados às TVs. */
  publicUrl: (process.env.PUBLIC_URL || '').replace(/\/+$/, ''),
  /** Tamanho máximo de upload por arquivo, em MB. */
  maxUploadMb: Number(process.env.MAX_UPLOAD_MB || 2048),
  /** Uma TV é considerada online se sincronizou nos últimos N segundos. */
  onlineWindowSec: Number(process.env.ONLINE_WINDOW_SEC || 180),
  /** Pacote do app das TVs: APKs de outro app são recusados no upload. */
  appPackage: process.env.APP_PACKAGE || 'com.tvloja.signage',
};

export const mediaDir = path.join(config.dataDir, 'media');
/** APKs de atualização do app (não são públicos: só TVs autenticadas baixam). */
export const releasesDir = path.join(config.dataDir, 'releases');
