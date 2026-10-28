import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import https from 'node:https';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const vendor = path.join(root, 'desktop', 'vendor');
export async function ensureWebView2Sdk() {
  const source = JSON.parse(await readFile(path.join(vendor, 'sdk-source.json'), 'utf8'));
  const archive = path.join(vendor, 'webview2.' + source.version + '.nupkg');
  await mkdir(vendor, { recursive: true });
  if (!existsSync(archive)) {
    const content = await new Promise((resolve, reject) => {
      const request = https.get(source.url, response => {
        if (response.statusCode !== 200) { response.resume(); reject(new Error('SDK 下载失败：' + response.statusCode)); return; }
        const chunks = [];
        let length = 0;
        response.on('data', chunk => { length += chunk.length; if (length > 20000000) request.destroy(new Error('SDK 下载体积异常。')); else chunks.push(chunk); });
        response.on('end', () => resolve(Buffer.concat(chunks)));
        response.on('error', reject);
      });
      request.setTimeout(60000, () => request.destroy(new Error('SDK 下载超时。')));
      request.on('error', reject);
    });
    if (createHash('sha256').update(content).digest('hex') !== source.sha256) throw new Error('SDK 下载校验失败。');
    await writeFile(archive, content);
  }
  if (createHash('sha256').update(await readFile(archive)).digest('hex') !== source.sha256) throw new Error('SDK 缓存校验失败。');
  const sdk = path.join(vendor, 'webview2');
  const names = ['Microsoft.Web.WebView2.Core.dll', 'Microsoft.Web.WebView2.WinForms.dll', 'WebView2Loader.dll', 'LICENSE.txt'];
  if (names.some(name => !existsSync(path.join(sdk, name)))) {
    await mkdir(sdk, { recursive: true });
    const quote = value => "'" + value.replaceAll("'", "''") + "'";
    const script = `Add-Type -AssemblyName System.IO.Compression.FileSystem; $taskSdk = [IO.Compression.ZipFile]::OpenRead(${quote(archive)}); try { foreach ($taskEntry in @('lib/net462/Microsoft.Web.WebView2.Core.dll','lib/net462/Microsoft.Web.WebView2.WinForms.dll','runtimes/win-x64/native/WebView2Loader.dll','LICENSE.txt')) { [IO.Compression.ZipFileExtensions]::ExtractToFile($taskSdk.GetEntry($taskEntry), (Join-Path ${quote(sdk)} ([IO.Path]::GetFileName($taskEntry))), $true) } } finally { $taskSdk.Dispose() }`;
    const result = spawnSync('powershell.exe', ['-NoProfile', '-Command', script], { encoding: 'utf8', windowsHide: true });
    if (result.error || result.status !== 0) throw result.error || new Error('SDK 解压失败：' + result.stderr);
  }
  return sdk;
}
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) { await ensureWebView2Sdk(); console.log('WebView2 SDK 已准备并校验。'); }
