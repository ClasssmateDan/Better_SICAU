import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile,mkdir} from 'node:fs/promises';
import path from 'node:path';
import {androidTools,root,run} from '../scripts/android-tools.mjs';
const tools=await androidTools();
const folder=path.join(root,'android/.build/public-test-'+Date.now().toString(36));await mkdir(folder,{recursive:true});
run(tools.javac,['-encoding','UTF-8','-d',folder,path.join(root,'android/src/com/dan/kejian/SigninWebViewProfile.java'),path.join(root,'android/tests/ProfileLifecycleTest.java')]);
test('公开安卓模板为空课表',async()=>{
  assert.deepEqual(JSON.parse(await readFile(path.join(root,'android/timetable.json'),'utf8')).courses,[]);
});
test('重复创建、失败重试和并发创建只配置一次签到进程',()=>{
  for(const mode of ['reopen','retry','concurrent'])assert.match(run(tools.java,['-cp',folder,'ProfileLifecycleTest',mode]),/PASS/);
});
