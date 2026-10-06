import fs from 'node:fs';
import zlib from 'node:zlib';

/**
 * Lê package, versionCode e versionName de um APK sem dependências externas:
 * 1. localiza AndroidManifest.xml dentro do ZIP (diretório central);
 * 2. decodifica o XML binário do Android (AXML).
 */
export function readApkInfo(file) {
  const buf = fs.readFileSync(file);
  const manifest = extractZipEntry(buf, 'AndroidManifest.xml');
  if (!manifest) throw new Error('Arquivo não é um APK válido (AndroidManifest.xml ausente)');
  return parseManifest(manifest);
}

// ----------------------------------------------------------------------------- ZIP

function extractZipEntry(buf, wanted) {
  // Fim do diretório central (EOCD): procurado do fim para o começo (pode haver comentário).
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65_557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('Arquivo não é um ZIP/APK válido');
  const entries = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  for (let n = 0; n < entries; n++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('Diretório do APK corrompido');
    const method = buf.readUInt16LE(p + 10);
    const compSize = buf.readUInt32LE(p + 20);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const localOffset = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
    if (name === wanted) {
      const lNameLen = buf.readUInt16LE(localOffset + 26);
      const lExtraLen = buf.readUInt16LE(localOffset + 28);
      const start = localOffset + 30 + lNameLen + lExtraLen;
      const data = buf.subarray(start, start + compSize);
      if (method === 0) return data;
      if (method === 8) return zlib.inflateRawSync(data);
      throw new Error(`Compressão não suportada no APK (${method})`);
    }
    p += 46 + nameLen + extraLen + commentLen;
  }
  return null;
}

// ----------------------------------------------------------------------------- AXML

const ATTR_VERSION_CODE = 0x0101021b;
const ATTR_VERSION_NAME = 0x0101021c;
const TYPE_STRING = 0x03;
const TYPE_INT_DEC = 0x10;
const TYPE_INT_HEX = 0x11;

function parseManifest(xml) {
  if (xml.readUInt16LE(0) !== 0x0003) throw new Error('AndroidManifest.xml em formato inesperado');
  let strings = [];
  let resourceIds = [];
  let p = xml.readUInt16LE(2);
  while (p < xml.length) {
    const type = xml.readUInt16LE(p);
    const headerSize = xml.readUInt16LE(p + 2);
    const size = xml.readUInt32LE(p + 4);
    if (size === 0) break;
    if (type === 0x0001) strings = readStringPool(xml, p);
    else if (type === 0x0180) {
      for (let i = p + headerSize; i < p + size; i += 4) resourceIds.push(xml.readUInt32LE(i));
    } else if (type === 0x0102) {
      const ext = p + headerSize;
      const name = strings[xml.readUInt32LE(ext + 4)];
      if (name === 'manifest') {
        const attrStart = xml.readUInt16LE(ext + 8);
        const attrSize = xml.readUInt16LE(ext + 10);
        const attrCount = xml.readUInt16LE(ext + 12);
        const info = {};
        for (let a = 0; a < attrCount; a++) {
          const at = ext + attrStart + a * attrSize;
          const nameIdx = xml.readUInt32LE(at + 4);
          const rawIdx = xml.readInt32LE(at + 8);
          const dataType = xml.readUInt8(at + 15);
          const data = xml.readUInt32LE(at + 16);
          const attrName = strings[nameIdx];
          const resId = resourceIds[nameIdx];
          const strValue = rawIdx >= 0 ? strings[rawIdx] : (dataType === TYPE_STRING ? strings[data] : undefined);
          if (attrName === 'package') info.packageName = strValue;
          else if (attrName === 'versionCode' || resId === ATTR_VERSION_CODE) {
            info.versionCode = dataType === TYPE_INT_DEC || dataType === TYPE_INT_HEX ? data : Number(strValue);
          } else if (attrName === 'versionName' || resId === ATTR_VERSION_NAME) {
            info.versionName = strValue ?? String(data);
          }
        }
        if (!info.packageName || !info.versionCode) throw new Error('Não foi possível ler a versão do APK');
        return { packageName: info.packageName, versionCode: info.versionCode, versionName: info.versionName || String(info.versionCode) };
      }
    }
    p += size;
  }
  throw new Error('Elemento <manifest> não encontrado no APK');
}

function readStringPool(xml, chunk) {
  const count = xml.readUInt32LE(chunk + 8);
  const flags = xml.readUInt32LE(chunk + 16);
  const stringsStart = xml.readUInt32LE(chunk + 20);
  const utf8 = (flags & 0x100) !== 0;
  const offsets = chunk + xml.readUInt16LE(chunk + 2);
  const base = chunk + stringsStart;
  const out = new Array(count);
  for (let i = 0; i < count; i++) {
    let s = base + xml.readUInt32LE(offsets + i * 4);
    if (utf8) {
      s += (xml[s] & 0x80) ? 2 : 1; // comprimento em UTF-16 (ignorado)
      let len = xml[s];
      if (len & 0x80) { len = ((len & 0x7f) << 8) | xml[s + 1]; s += 2; } else s += 1;
      out[i] = xml.toString('utf8', s, s + len);
    } else {
      let len = xml.readUInt16LE(s);
      if (len & 0x8000) { len = ((len & 0x7fff) << 16) | xml.readUInt16LE(s + 2); s += 4; } else s += 2;
      out[i] = xml.toString('utf16le', s, s + len * 2);
    }
  }
  return out;
}
