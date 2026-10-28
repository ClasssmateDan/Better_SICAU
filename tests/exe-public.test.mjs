import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdir } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { createHash } from 'node:crypto';

const root=fileURLToPath(new URL('../',import.meta.url));
const {version}=JSON.parse(await readFile(path.join(root,'package.json'),'utf8'));
const filename=`课间课程表-${version}.exe`;
const exe=path.join(root,'release',filename);
async function launch(profile,args=[],binary=exe,defaultDirectory=false) {
  const result=spawnSync(binary,['--smoke-test',...(defaultDirectory?[]:['--data-dir',profile]),...args],{windowsHide:true,timeout:50000});
  assert.equal(result.error,undefined);
  const report=JSON.parse((await readFile(path.join(profile,'smoke-result.json'),'utf8')).replace(/^\uFEFF/,''));
  assert.equal(result.status,0,JSON.stringify(report)); assert.equal(report.ok,true,JSON.stringify(report));
  return report;
}
const fixture=name=>path.join(root,'desktop','.test-profile-u-'+name+'-'+Date.now().toString(36));

test('正式 ZIP 在全新目录启动为空课表、零学分、零提醒，扩展和工具完整',async()=>{
  const folder=fixture('unpacked'); await mkdir(folder,{recursive:true});
  const unpack=spawnSync('python',['-X','utf8','-c','import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])',path.join(root,'release',`课间课程表-Windows-x64-${version}.zip`),folder],{windowsHide:true,encoding:'utf8'});
  assert.equal(unpack.status,0,unpack.stderr);
  const profile=path.join(folder,'课间数据');
  const manifest=JSON.parse(await readFile(path.join(profile,'course-tools/browser-extension/manifest.json'),'utf8'));
  assert.equal(manifest.manifest_version,3);
  await readFile(path.join(profile,`runtime/${version}/course-tools/KeJianCourseTools.exe`));
  const first=await launch(profile,['--layout-test','--backup-test','save'],path.join(folder,filename),true);
  assert.equal(first.dom.courses,'0'); assert.equal(first.dom.credits,'0'); assert.equal(first.planEvents,0);
  assert.equal(first.dom.nativeEnabled,false); assert.equal(first.layout.ok,true);
  for(const name of ['timetable.json','导出验证.json']) assert.deepEqual(JSON.parse(await readFile(path.join(profile,name),'utf8')).courses,[]);
  const second=await launch(profile,[],path.join(folder,filename),true);
  assert.equal(second.dom.courses,'0'); assert.equal(second.planEvents,0);
  assert.ok((await readFile(path.join(folder,'SHA256.txt'),'utf8')).startsWith(createHash('sha256').update(await readFile(path.join(folder,filename))).digest('hex')));
});

test('公开版可添加课程，重置只恢复空白模板',async()=>{
  const result=await launch(fixture('reset'),['--course-test','flow']);
  assert.equal(result.courses.resetBlank,true); assert.equal(result.dom.courses,'0'); assert.equal(result.planEvents,0);
});

test('公开版可导入虚构示例，重新启动保留已导入课程',async()=>{
  const profile=fixture('import');
  const result=await launch(profile,['--import-test','flow']);
  assert.equal(result.imports.ok,true); assert.equal(result.dom.courses,'3');
  const restart=await launch(profile);
  assert.equal(restart.dom.courses,'3'); assert.equal(restart.planEvents,result.planEvents);
});

test('空课表仍可独立打开课程工具和扩展目录，不访问真实账号或网络',async()=>{
  const profile=fixture('tools');
  const report=await launch(profile,['--tools-test','flow']);
  assert.equal(report.tools.ok,true); assert.deepEqual(report.tools.results.map(item=>item.status),['opened','opened']);
  assert.equal(report.dom.courses,'0'); assert.equal(report.planEvents,0);
  assert.equal(JSON.parse(await readFile(path.join(profile,'course-tools/smoke-guard.json'),'utf8')).networkBlocked,true);
});

test('空课表可使用明确填写的模拟 classid 生成二维码',async()=>{
  const report=await launch(fixture('qr'),['--qr-test','flow']);
  assert.equal(report.qr.ok,true); assert.equal(report.dom.courses,'0'); assert.equal(report.qrNetworkRequests,0);
});
