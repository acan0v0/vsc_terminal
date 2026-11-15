import * as fs from 'fs/promises';
import * as path from 'path';

export interface Entry {
  name: string;
  type: 'dir' | 'file';
  size?: number;
  mtime?: number;
}

const MAX_FILE_BYTES = 256 * 1024;
const SKIP_DIRS = new Set(['.git', 'node_modules', '.venv', '__pycache__', '.next', 'dist', 'out', 'target']);

/** 把用户/手机传来的路径规范化并限制在 root 之内，越界抛错。 */
export function resolveInside(root: string, rel: string): string {
  const input = (rel ?? '').replace(/\\/g, '/');
  // 显式拒绝绝对路径（含 Windows 盘符），否则会被拼进 root 之下而绕过校验
  if (input.startsWith('/') || path.isAbsolute(rel ?? '') || /^[a-zA-Z]:/.test(input)) {
    throw new Error('path outside root');
  }
  const normalizedRoot = path.resolve(root);
  const target = path.resolve(normalizedRoot, input);
  const relToRoot = path.relative(normalizedRoot, target);
  if (relToRoot.startsWith('..') || path.isAbsolute(relToRoot)) {
    throw new Error('path outside root');
  }
  return target;
}

export async function listDir(root: string, rel: string): Promise<Entry[]> {
  const dir = resolveInside(root, rel);
  const stat = await fs.stat(dir);
  if (!stat.isDirectory()) throw new Error('not a directory');

  const items = await fs.readdir(dir, { withFileTypes: true });
  const entries: Entry[] = [];
  for (const item of items) {
    if (item.name.startsWith('.') && item.name !== '.env.example') continue;
    if (item.isDirectory() && SKIP_DIRS.has(item.name)) continue;
    const full = path.join(dir, item.name);
    try {
      const s = await fs.stat(full);
      if (s.isDirectory()) entries.push({ name: item.name, type: 'dir' });
      else if (s.isFile()) entries.push({ name: item.name, type: 'file', size: s.size, mtime: s.mtimeMs });
    } catch {
      // 权限不足或符号链接失效，跳过
    }
  }
  entries.sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'dir' ? -1 : 1));
  return entries;
}

export interface FileContent {
  path: string;
  content: string;
  truncated: boolean;
  binary: boolean;
  size: number;
}

export async function readFile(root: string, rel: string): Promise<FileContent> {
  const full = resolveInside(root, rel);
  const stat = await fs.stat(full);
  if (!stat.isFile()) throw new Error('not a file');

  const handle = await fs.open(full, 'r');
  try {
    const length = Math.min(stat.size, MAX_FILE_BYTES);
    const buf = Buffer.alloc(length);
    await handle.read(buf, 0, length, 0);
    // 出现 NUL 字节视为二进制
    const binary = buf.includes(0);
    return {
      path: rel,
      content: binary ? '' : buf.toString('utf8'),
      truncated: stat.size > MAX_FILE_BYTES,
      binary,
      size: stat.size,
    };
  } finally {
    await handle.close();
  }
}

/** 校验 root 是个已存在的目录。 */
export async function assertRoot(root: string): Promise<void> {
  const stat = await fs.stat(root);
  if (!stat.isDirectory()) throw new Error(`not a directory: ${root}`);
}
