import { access } from 'node:fs/promises';
import { spawn } from 'node:child_process';

const page = new URL('../index.html', import.meta.url);
await access(page);
const command = process.platform === 'win32' ? 'rundll32.exe'
  : process.platform === 'darwin' ? 'open' : 'xdg-open';
const args = process.platform === 'win32'
  ? ['url.dll,FileProtocolHandler', page.href] : [page.href];
const child = spawn(command, args, { stdio: 'ignore', windowsHide: true });
child.on('error', error => {
  console.error(`自动打开失败：${error.message}。请双击本目录的 index.html。`);
  process.exitCode = 1;
});
child.unref();
console.log('已打开静态课程表，不启动本地服务。');
