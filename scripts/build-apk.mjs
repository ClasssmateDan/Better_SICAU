import { readFile, writeFile, mkdir, copyFile, readdir, unlink } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { randomBytes, createHash } from 'node:crypto';
import path from 'node:path';
import { buildStatic } from './build.mjs';
import { createPreset } from '../dist/preset.mjs';
import { validateData } from '../dist/model.mjs';
import { androidTools, root, run } from './android-tools.mjs';
import { createAndroidDocument } from './android-document.mjs';

const tools = await androidTools();
const base = path.join(root, 'android');
const build = path.join(base, '.build', 'apk-' + Date.now());
const assets = path.join(base, 'assets'), generated = path.join(build, 'generated'), classes = path.join(build, 'classes'), dex = path.join(build, 'dex');
for (const directory of [build, assets, generated, classes, dex, path.join(root, 'release')]) await mkdir(directory, { recursive: true });
const sourceArgument = process.argv.indexOf('--timetable');
const snapshotPath = path.join(base, 'timetable.json');
if (sourceArgument >= 0) {
  if (!process.argv[sourceArgument + 1]) throw new Error('--timetable 缺少 JSON 文件路径');
  const chosen = validateData(JSON.parse((await readFile(path.resolve(process.argv[sourceArgument + 1]), 'utf8')).replace(/^\uFEFF/, '')));
  await writeFile(snapshotPath, JSON.stringify(chosen, null, 2) + '\n');
} else if (!existsSync(snapshotPath)) await writeFile(snapshotPath, JSON.stringify(createPreset(), null, 2) + '\n');
const timetable = validateData(JSON.parse(await readFile(snapshotPath, 'utf8')));
const { standalone } = await buildStatic();
const html = await createAndroidDocument(standalone);
await writeFile(path.join(assets, 'index.html'), html);
await writeFile(path.join(assets, 'timetable.json'), JSON.stringify(timetable));
for (const [source, name] of [['课表数据格式说明.md', 'format-guide.md'], ['课表导入示例.json', 'example.json']]) {
  await copyFile(path.join(root, source), path.join(assets, name));
  // Remove only these two generated aliases from the first packaging attempt.
  try { await unlink(path.join(assets, source)); } catch (error) { if (error.code !== 'ENOENT') throw error; }
}
await writeFile(path.join(assets, 'QR-THIRD-PARTY-LICENSES.txt'), 'jsQR 1.4.0\n' + await readFile(path.join(root, 'qr-tool/dist/vendor/jsQR.LICENSE'), 'utf8') + '\nqrcode-generator 1.4.4\n' + await readFile(path.join(root, 'qr-tool/dist/vendor/qrcode.LICENSE'), 'utf8'));
// SDK's Windows native tools expect relative ASCII paths for a Chinese workspace.
const relative = filename => path.relative(root, filename);
run(tools.aapt, ['compile', '--dir', 'android/res', '-o', relative(path.join(build, 'resources.zip'))]);
const unsigned = path.join(build, 'unsigned.apk');
run(tools.aapt, ['link', '-o', relative(unsigned), '-I', tools.androidJar, '--manifest', 'android/AndroidManifest.xml', '-A', relative(assets), '--java', relative(generated), relative(path.join(build, 'resources.zip'))]);
async function files(directory, suffix) {
  const result = [];
  for (const item of await readdir(directory, { withFileTypes: true })) {
    const filename = path.join(directory, item.name);
    if (item.isDirectory()) result.push(...await files(filename, suffix)); else if (item.name.endsWith(suffix)) result.push(filename);
  }
  return result;
}
const javaSources = [...await files(path.join(base, 'src'), '.java'), ...await files(generated, '.java')];
const argumentFile = path.join(build, 'javac.args');
await writeFile(argumentFile, ['--release', '8', '-encoding', 'UTF-8', '-classpath', tools.androidJar, '-d', classes, ...javaSources].map(value => '"' + value.replaceAll('\\', '/') + '"').join('\n'));
run(tools.javac, ['@' + argumentFile]);
const jar = path.join(build, 'classes.jar');
run(tools.jar, ['--create', '--file', jar, '-C', classes, '.']);
run(tools.java, ['-cp', tools.d8, 'com.android.tools.r8.D8', '--lib', tools.androidJar, '--min-api', '26', '--output', dex, jar]);
run(tools.jar, ['--update', '--file', unsigned, '-C', dex, 'classes.dex']);
const aligned = path.join(build, 'aligned.apk'); run(tools.align, ['-f', '-p', '4', relative(unsigned), relative(aligned)]);

const signing = path.resolve(process.env.KEJIAN_ANDROID_SIGNING_DIR || path.join(base, 'private-signing')); await mkdir(signing, { recursive: true });
const password = path.join(signing, 'password.txt'), key = path.join(signing, 'release.jks');
if (existsSync(key) && !existsSync(password)) throw new Error('签名密钥仍在但密码文件缺失，请恢复密码文件；不会替换原密钥。');
if (!existsSync(password)) await writeFile(password, randomBytes(32).toString('base64') + '\n', { flag: 'wx' });
if (!existsSync(key)) run(tools.keytool, ['-genkeypair', '-keystore', key, '-storepass:file', password, '-keypass:file', password, '-alias', 'kejian', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', 'CN=KeJian Local,O=Personal Projects,C=CN']);
const manifest = await readFile(path.join(base, 'AndroidManifest.xml'), 'utf8');
const version = manifest.match(/android:versionName="([\d.]+)"/)[1];
const target = path.join(root, 'release', `课间课程表-安卓-${version}.apk`);
run(tools.java, ['-jar', tools.signer, 'sign', '--ks', key, '--ks-key-alias', 'kejian', '--ks-pass', 'file:' + password, '--out', target, aligned]);
const signature = run(tools.java, ['-jar', tools.signer, 'verify', '--verbose', '--print-certs', target]);
// Verify an ASCII-named copy too; signing output has the user-facing Chinese name.
const verifiedCopy = path.join(build, 'signed.apk'); await copyFile(target, verifiedCopy);
run(tools.align, ['-c', '-p', '4', relative(verifiedCopy)]);
const apkHash = createHash('sha256').update(await readFile(target)).digest('hex');
await writeFile(target + '.sha256', apkHash + '\n');
await copyFile(path.join(base, 'README.md'), path.join(root, 'release', '安卓使用说明.md'));
await writeFile(path.join(root, 'release', '安卓构建记录.json'), JSON.stringify({ version, applicationId: 'com.dan.kejian', minSdk: 26, targetSdk: 35, sha256: apkHash, bytes: (await readFile(target)).length,
  courses: timetable.courses.length, credits: timetable.courses.reduce((sum, course) => sum + course.credits, 0), semester: timetable.semester,
  timetableSha256: createHash('sha256').update(await readFile(snapshotPath)).digest('hex'), signature, builtAt: new Date().toISOString(), buildFolder: path.relative(root, build) }, null, 2) + '\n');
console.log('APK 已生成并通过签名和对齐校验：' + target);
console.log('已内置 ' + timetable.courses.length + ' 门课程，学期起始日 ' + timetable.semester.startDate);
