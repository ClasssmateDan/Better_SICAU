import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// Exact, licensed dependencies are vendored so APK builds stay offline and reproducible.
export async function learningPassDependencies() {
  const folder = fileURLToPath(new URL('../android/vendor/learningpass/', import.meta.url));
  const entries = JSON.parse(await readFile(path.join(folder, 'dependencies.json'), 'utf8'));
  const files = [];
  for (const entry of entries) {
    if (!/^[a-zA-Z0-9.-]+\.jar$/.test(entry.file) || !/^[a-f0-9]{64}$/.test(entry.sha256)) throw Error('学习通依赖清单格式不正确');
    const file = path.join(folder, entry.file);
    if (createHash('sha256').update(await readFile(file)).digest('hex') !== entry.sha256) throw Error('学习通依赖校验失败：' + entry.file);
    files.push(file);
  }
  if (files.length !== 2) throw Error('学习通依赖清单不完整');
  return files;
}
