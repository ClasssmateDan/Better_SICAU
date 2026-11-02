import { readFile, writeFile, mkdir, copyFile, mkdtemp } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { buildStatic } from './build.mjs';
import { ensureWebView2Sdk } from './fetch-webview2.mjs';

if (process.platform !== 'win32') throw new Error('Windows EXE 打包需在 Windows 中运行。');
const root = fileURLToPath(new URL('../', import.meta.url));
const { version } = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'));
const output = path.join(root, 'release');
const sdk = await ensureWebView2Sdk();
const source = JSON.parse(await readFile(path.join(root, 'desktop', 'vendor', 'sdk-source.json'), 'utf8'));
const archive = await readFile(path.join(root, 'desktop', 'vendor', 'webview2.' + source.version + '.nupkg'));
if (createHash('sha256').update(archive).digest('hex') !== source.sha256) throw new Error('WebView2 SDK 校验失败。');
for (const name of ['Microsoft.Web.WebView2.Core.dll', 'Microsoft.Web.WebView2.WinForms.dll', 'WebView2Loader.dll']) {
  if (!existsSync(path.join(sdk, name))) throw new Error('SDK 未解压：' + name);
}
await mkdir(output, { recursive: true });
await buildStatic();
const qrLicenses = 'jsQR 1.4.0\r\n' + await readFile(path.join(root, 'qr-tool/dist/vendor/jsQR.LICENSE'), 'utf8')
  + '\r\n\r\nqrcode-generator 1.4.4\r\n' + await readFile(path.join(root, 'qr-tool/dist/vendor/qrcode.LICENSE'), 'utf8');
await writeFile(path.join(output, 'QR-THIRD-PARTY-LICENSES.txt'), qrLicenses);
function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8', windowsHide: true, cwd: root });
  if (result.stdout) process.stdout.write(result.stdout);
  if (result.stderr) process.stderr.write(result.stderr);
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(command + ' 退出码：' + result.status);
}
const icon = path.join(root, 'desktop', 'app.ico');
const windows = process.env.SystemRoot || 'C:\\Windows';
const compiler = ['Framework64', 'Framework'].map(folder => path.join(windows, 'Microsoft.NET', folder, 'v4.0.30319', 'csc.exe')).find(existsSync);
if (!compiler) throw new Error('未找到 .NET Framework 编译器。');
const iconTool = path.join(root, 'desktop', 'CreateIcon.exe');
run(compiler, ['/nologo', '/target:exe', '/reference:System.Drawing.dll', '/reference:System.IO.Compression.dll', '/out:' + iconTool, path.join(root, 'desktop', 'CreateIcon.cs')]);
run(iconTool, [icon]);
const exeName = `课间课程表-${version}.exe`;
const exe = path.join(output, exeName);
run(compiler, ['/nologo', '/target:winexe', '/platform:x64', '/optimize+', '/win32icon:' + icon,
  '/win32manifest:' + path.join(root, 'desktop', 'app.manifest'),
  '/reference:System.Windows.Forms.dll', '/reference:System.Drawing.dll', '/reference:System.Web.Extensions.dll',
  '/reference:' + path.join(sdk, 'Microsoft.Web.WebView2.Core.dll'), '/reference:' + path.join(sdk, 'Microsoft.Web.WebView2.WinForms.dll'),
  '/resource:' + path.join(sdk, 'Microsoft.Web.WebView2.Core.dll') + ',KeJian.Microsoft.Web.WebView2.Core.dll',
  '/resource:' + path.join(sdk, 'Microsoft.Web.WebView2.WinForms.dll') + ',KeJian.Microsoft.Web.WebView2.WinForms.dll',
  '/resource:' + path.join(sdk, 'WebView2Loader.dll') + ',KeJian.WebView2Loader.dll',
  '/resource:' + path.join(sdk, 'LICENSE.txt') + ',KeJian.WebView2.LICENSE.txt',
  '/resource:' + path.join(output, 'QR-THIRD-PARTY-LICENSES.txt') + ',KeJian.QR-LICENSES.txt',
  '/resource:' + path.join(root, 'desktop', 'QrSmoke.js') + ',KeJian.QrSmoke.js',
  '/resource:' + path.join(root, 'desktop', 'CourseSmoke.js') + ',KeJian.CourseSmoke.js',
  '/resource:' + path.join(root, 'desktop', 'ImportSmoke.js') + ',KeJian.ImportSmoke.js',
  '/resource:' + path.join(root, 'desktop', 'LayoutSmoke.js') + ',KeJian.LayoutSmoke.js',
  '/resource:' + path.join(root, 'desktop', 'CalendarSmoke.js') + ',KeJian.CalendarSmoke.js',
  '/resource:' + path.join(root, '课表导入示例.json') + ',KeJian.import-example.json',
  '/resource:' + path.join(root, 'index.html') + ',KeJian.index.html',
  '/resource:' + path.join(root, 'reminder-plan.json') + ',KeJian.reminder-plan.json',
  '/out:' + exe, path.join(root, 'desktop', 'Program.cs'), path.join(root, 'desktop', 'PortableData.cs'), path.join(root, 'desktop', 'QrBrowserWindow.cs'), path.join(root, 'reminders', 'ReminderCore.cs')]);
await copyFile(path.join(sdk, 'LICENSE.txt'), path.join(output, 'WebView2-LICENSE.txt'));
await writeFile(path.join(output, '使用说明.txt'), (await readFile(path.join(root, 'desktop/使用说明.txt'), 'utf8')).replaceAll('{{VERSION}}', version), 'utf8');
const digest = createHash('sha256').update(await readFile(exe)).digest('hex');
await writeFile(path.join(output, 'SHA256.txt'), digest + '  ' + exeName + '\r\n');
for (const name of ['课表数据格式说明.md', '课表导入示例.json']) await copyFile(path.join(root, name), path.join(output, name));
// Build from an explicit clean staging tree, never from the live release/课间数据.
await mkdir(path.join(root, 'desktop/.course-build'), { recursive: true });
const stage = await mkdtemp(path.join(root, 'desktop/.course-build/distribution-' + version + '-'));
const zipFiles = [];
async function stageFile(relative, source) {
  const target = path.resolve(stage, relative);
  if (!target.startsWith(stage + path.sep)) throw new Error('发布路径超出暂存目录。');
  await mkdir(path.dirname(target), { recursive: true });
  await copyFile(source, target);
  zipFiles.push(relative);
}
for (const name of [exeName, '使用说明.txt', 'WebView2-LICENSE.txt', 'QR-THIRD-PARTY-LICENSES.txt', '课表数据格式说明.md', '课表导入示例.json', 'SHA256.txt']) await stageFile(name, path.join(output, name));
const marker = '课间数据/发布资源清单.sha256';
await mkdir(path.join(stage, '课间数据'), { recursive: true });
await writeFile(path.join(stage, marker), 'KeJian bundled assets v1\n\n', 'utf8');
zipFiles.push('课间数据/', marker);
run(iconTool, ['--zip-root', path.join(output, `Better-SICAU-Desktop-Windows-x64-${version}.zip`), stage, ...zipFiles]);
try { await copyFile(exe, path.join(output, '课间课程表.exe')); }
catch (error) { console.log('固定文件名可能仍被旧程序占用，请使用新生成的版本号 EXE：' + error.code); }
console.log('已生成：' + exe);
