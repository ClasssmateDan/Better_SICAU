import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

export const root = fileURLToPath(new URL('../', import.meta.url));
export async function androidTools() {
  let local = {};
  try { local = JSON.parse(await readFile(path.join(root, 'android/local-toolchain.json'), 'utf8')); } catch { /* environment overrides are also supported */ }
  const sdk = process.env.KEJIAN_ANDROID_SDK || process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || local.sdk;
  const jdk = process.env.KEJIAN_JAVA_HOME || process.env.JAVA_HOME || local.jdk;
  if (!sdk || !jdk) throw new Error('请设置 KEJIAN_ANDROID_SDK 与 JAVA_HOME，或在 android/local-toolchain.json 指定 sdk、jdk。');
  const binary = name => path.join(jdk, 'bin', name + (process.platform === 'win32' ? '.exe' : ''));
  const build = path.join(sdk, 'build-tools', '35.0.0');
  const tools = { sdk, jdk, java: binary('java'), javac: binary('javac'), jar: binary('jar'), keytool: binary('keytool'),
    androidJar: path.join(sdk, 'platforms/android-35/android.jar'), aapt: path.join(build, process.platform === 'win32' ? 'aapt2.exe' : 'aapt2'),
    align: path.join(build, process.platform === 'win32' ? 'zipalign.exe' : 'zipalign'), d8: path.join(build, 'lib/d8.jar'), signer: path.join(build, 'lib/apksigner.jar') };
  for (const [name, file] of Object.entries(tools)) if (!existsSync(file)) throw new Error('缺少安卓构建工具：' + name + ' (' + file + ')');
  return tools;
}
export function run(command, args, options = {}) {
  const result = spawnSync(command, args, { cwd: root, encoding: 'utf8', windowsHide: true,
    env: { ...process.env, TEMP: path.join(root, 'android/.build'), TMP: path.join(root, 'android/.build') }, ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(command + ' 退出码 ' + result.status + '\n' + result.stdout + result.stderr);
  return result.stdout;
}
