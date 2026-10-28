import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

if (process.platform !== 'win32') throw new Error('桌面提醒工具需要 Windows，静态网页可继续正常构建。');
const root = fileURLToPath(new URL('../', import.meta.url));
const windows = process.env.SystemRoot || 'C:\\Windows';
const compiler = ['Framework64', 'Framework'].map(folder => path.join(windows, 'Microsoft.NET', folder, 'v4.0.30319', 'csc.exe')).find(existsSync);
if (!compiler) throw new Error('未找到 Windows 内置 .NET Framework 编译器。');
const result = spawnSync(compiler, ['/nologo', '/target:winexe', '/optimize+', '/reference:System.Windows.Forms.dll', '/reference:System.Drawing.dll', '/reference:System.Web.Extensions.dll', '/out:' + path.join(root, 'KeJianReminders.exe'), path.join(root, 'reminders', 'DesktopReminders.cs'), path.join(root, 'reminders', 'ReminderCore.cs')], { encoding: 'utf8', windowsHide: true });
if (result.stdout) process.stdout.write(result.stdout);
if (result.stderr) process.stderr.write(result.stderr);
if (result.error) throw result.error;
if (result.status !== 0) throw new Error('桌面提醒编译失败。');
console.log('Windows 桌面提醒已生成：双击 桌面提醒.cmd，无需服务或安装。');
