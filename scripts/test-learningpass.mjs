// Tests only synthetic accounts and HTTP responses. No real school requests.
import { readFile, writeFile, mkdir, readdir, copyFile, mkdtemp } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { tmpdir } from 'node:os';
import { androidTools, root, run } from './android-tools.mjs';
import { learningPassDependencies } from './learningpass-dependencies.mjs';
import { holidayJavaSource } from './holiday-source.mjs';
import { buildStatic } from './build.mjs';
import { createAndroidDocument } from './android-document.mjs';

const tools = await androidTools(), dependencies = await learningPassDependencies();
const cache = process.env.KEJIAN_GRADLE_USER_HOME;
const gradleHome = process.env.KEJIAN_GRADLE_HOME;
if (!cache || !gradleHome) throw Error('设置 KEJIAN_GRADLE_HOME 和 KEJIAN_GRADLE_USER_HOME，指定 Gradle 8.9 与已缓存的测试依赖。测试不下载依赖。');
const parent = process.env.KEJIAN_ANDROID_TEST_ROOT || path.join(tmpdir(), 'kejian-learningpass-tests');
if (/[^\x20-\x7e]/.test(parent)) throw Error('Windows Gradle 测试目录需要使用英文路径，请设置 KEJIAN_ANDROID_TEST_ROOT。');
await mkdir(parent, {recursive:true}); const stage = await mkdtemp(path.join(parent,'run-'));
async function copyTree(from, to) {
  await mkdir(to,{recursive:true});
  for (const file of await readdir(from,{withFileTypes:true})) {
    if (file.isSymbolicLink()) throw Error('测试输入不能是符号链接');
    if (file.isDirectory()) await copyTree(path.join(from,file.name),path.join(to,file.name));
    else await copyFile(path.join(from,file.name),path.join(to,file.name));
  }
}
await copyTree(path.join(root,'android/src'),path.join(stage,'app/src/main/java'));
await writeFile(path.join(stage,'app/src/main/java/com/dan/kejian/BuiltinHolidays.java'),holidayJavaSource());
await copyTree(path.join(root,'android/res'),path.join(stage,'app/src/main/res'));
await copyTree(path.join(root,'android/tests/learningpass'),path.join(stage,'app/src/test/java'));
await copyFile(path.join(root,'android/AndroidManifest.xml'),path.join(stage,'app/src/main/AndroidManifest.xml'));
const assets = path.join(stage,'app/src/main/assets'); await mkdir(assets,{recursive:true});
await writeFile(path.join(assets,'index.html'),await createAndroidDocument((await buildStatic()).standalone));
await copyFile(path.join(root,'android/timetable.json'),path.join(assets,'timetable.json'));
await mkdir(path.join(stage,'libs'),{recursive:true});
for (const file of dependencies) await copyFile(file,path.join(stage,'libs',path.basename(file)));
await writeFile(path.join(stage,'settings.gradle'),`pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name='KeJianLearningPassTests';include ':app'
`);
await writeFile(path.join(stage,'build.gradle'),`plugins { id 'com.android.application' version '8.7.3' apply false }\n`);
await writeFile(path.join(stage,'gradle.properties'),'org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8\nandroid.useAndroidX=true\n');
await writeFile(path.join(stage,'app/build.gradle'),`plugins { id 'com.android.application' }
android {
 namespace 'com.dan.kejian'; compileSdk 35; buildToolsVersion '35.0.0'
 defaultConfig { applicationId 'com.dan.kejian'; minSdk 26; targetSdk 35; versionCode 10902; versionName '1.9.2' }
 compileOptions { sourceCompatibility JavaVersion.VERSION_1_8; targetCompatibility JavaVersion.VERSION_1_8 }
 testOptions { unitTests.includeAndroidResources = true; unitTests.all {
  systemProperty 'robolectric.offline','true'
  systemProperty 'robolectric.dependency.dir',new File(gradle.gradleUserHomeDir,'robolectric').absolutePath
  systemProperty 'kejian.test.screenshots',rootProject.file('screenshots').absolutePath
  jvmArgs '--add-opens=java.base/java.lang=ALL-UNNAMED','--add-opens=java.base/java.util=ALL-UNNAMED','--add-opens=java.base/java.io=ALL-UNNAMED','--add-opens=java.base/java.net=ALL-UNNAMED','--add-opens=java.base/java.security=ALL-UNNAMED','--add-opens=java.base/java.text=ALL-UNNAMED','--add-opens=java.base/jdk.internal.access=ALL-UNNAMED','--add-opens=java.desktop/java.awt.font=ALL-UNNAMED','--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED'
 } }
}
dependencies {
 implementation files('../libs/core-3.5.3.jar','../libs/jsoup-1.18.3.jar')
 testImplementation 'junit:junit:4.13.2'; testImplementation 'org.robolectric:robolectric:4.17'
}
`);
const offline = path.join(root,'android/tests/learningpass-offline'), classes = path.join(stage,'offline-classes');
await mkdir(classes,{recursive:true});
async function javaFiles(folder) {
  const result=[]; for (const entry of await readdir(folder,{withFileTypes:true})) {
    if(entry.isDirectory()) result.push(...await javaFiles(path.join(folder,entry.name)));
    else if(entry.name.endsWith('.java')) result.push(path.join(folder,entry.name));
  } return result;
}
const module = path.join(root,'android/src/com/dan/qrbatch');
const inputs=[...await javaFiles(path.join(module,'core')),...await javaFiles(path.join(module,'network')),...await javaFiles(offline)];
const classpath=[classes,tools.androidJar,...dependencies].join(path.delimiter);
run(tools.javac,['-encoding','UTF-8','--release','17','-cp',classpath,'-d',classes,...inputs]);
for(const test of ['com.dan.qrbatch.core.CoreTest','com.dan.qrbatch.network.ClientTest']) console.log(run(tools.java,['-cp',classpath,test]).trim());
console.log(run(tools.java,['-cp',classpath,'ScannerTest',path.join(offline,'scanner/demo-qr.png'),path.join(offline,'scanner/demo-payload.txt')]).trim());
const gradle=path.join(gradleHome,'bin',process.platform==='win32'?'gradle.bat':'gradle');
if (!existsSync(gradle)) throw Error('Gradle 目录无效：'+gradleHome);
const options = {cwd:stage, timeout:240000, maxBuffer:3000000, env:{...process.env,JAVA_HOME:tools.jdk,GRADLE_USER_HOME:cache,ANDROID_HOME:tools.sdk,ANDROID_USER_HOME:path.join(stage,'.android')}};
const args=[':app:testDebugUnitTest','--offline','--no-daemon','--console=plain'];
try {
  const output = process.platform === 'win32' ? run(process.env.ComSpec || 'cmd.exe',['/d','/c',gradle,...args],options) : run(gradle,args,options);
  await writeFile(path.join(stage,'tests.log'),output); console.log(output);
} catch (error) {
  await writeFile(path.join(stage,'tests.log'),String(error)); throw error;
} finally { console.log('学习通整合测试记录：'+stage); }
