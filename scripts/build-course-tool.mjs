import { readFile, writeFile, mkdir, readdir, copyFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
export async function buildCourseTool() {
  const folder = path.join(root, 'desktop/course-tool');
  const build = path.join(root, 'desktop/.course-build');
  const packaging = path.join(root, 'desktop/.packaging');
  if (!existsSync(path.join(packaging, 'PyInstaller/__init__.py'))) throw new Error('缺少项目内 PyInstaller 6.22.3，请先安装到 desktop/.packaging。');
  for (const target of [build, path.join(build,'temp'), path.join(root,'release')]) await mkdir(target,{recursive:true});
  const env = { ...process.env, PYTHONPATH: packaging, PYTHONNOUSERSITE: '1', PYINSTALLER_CONFIG_DIR: path.join(build,'config'), TEMP: path.join(build,'temp'), TMP: path.join(build,'temp') };
  const python = process.env.KEJIAN_PYTHON || 'python';
  const run = args => {
    const result = spawnSync(python,args,{ cwd: root,env,encoding:'utf8',windowsHide:true });
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error('课程工具打包失败：\n'+result.stdout+result.stderr);
    return result.stdout;
  };
  const base = run(['-c','import sys; print(sys.base_prefix)']).trim();
  const pythonLicense = await readFile(path.join(base,'LICENSE.txt'),'utf8');
  const tkLicense = await readFile(path.join(base,'tcl/tk8.6/license.terms'),'utf8');
  const packLicenseFolder = path.join(packaging,'pyinstaller-6.22.3.dist-info/licenses');
  let packLicense = '';
  for (const name of await readdir(packLicenseFolder)) if (/COPY|LICENSE/i.test(name)) packLicense += '\n'+await readFile(path.join(packLicenseFolder,name),'utf8');
  if (!packLicense) throw new Error('缺少 PyInstaller 许可与运行时例外条款。');
  const licenses = 'CPython 3.13\n'+pythonLicense+'\n\nTk / Tcl\n'+tkLicense+'\n\nPyInstaller 6.22.3\n'+packLicense;
  const licensePath = path.join(build,'third-party-licenses.txt'); await writeFile(licensePath,licenses);
  await writeFile(path.join(root,'release/课程工具第三方许可.txt'),licenses);
  await copyFile(path.join(folder,'README.md'),path.join(root,'release/课程工具使用说明.md'));
  const sourceFiles = (await readdir(folder)).filter(name=>name.endsWith('.py')&&!name.startsWith('test_')).sort();
  const fingerprint = createHash('sha256');
  for (const name of sourceFiles) fingerprint.update(name).update(await readFile(path.join(folder,name)));
  fingerprint.update(licenses).update(await readFile(path.join(root,'desktop/app.ico')));
  fingerprint.update('PyInstaller 6.22.3 CPython '+run(['--version']).trim());
  const identity = fingerprint.digest('hex');
  const exe = path.join(build,'dist/KeJianCourseTools.exe'), cache = path.join(build,'build.json');
  if (existsSync(exe) && existsSync(cache)) {
    const previous = JSON.parse(await readFile(cache,'utf8'));
    if (previous.sourceSha256 === identity && previous.exeSha256 === createHash('sha256').update(await readFile(exe)).digest('hex')) return { exe, licenses: licensePath };
  }
  console.log('正在将课程工具与运行组件打包到 Windows 程序…');
  run(['-m','PyInstaller','--noconfirm','--onefile','--windowed','--name','KeJianCourseTools','--distpath',path.join(build,'dist'),'--workpath',path.join(build,'work'),'--specpath',build,
    '--icon',path.join(root,'desktop/app.ico'),'--paths',folder,'--add-data',licensePath+':.','--log-level','WARN',path.join(folder,'integrated_entry.py')]);
  const exeHash = createHash('sha256').update(await readFile(exe)).digest('hex');
  await writeFile(cache,JSON.stringify({ sourceSha256:identity,exeSha256:exeHash,pyinstaller:'6.22.3',python:run(['--version']).trim(),sources:sourceFiles },null,2)+'\n');
  return { exe, licenses:licensePath };
}
