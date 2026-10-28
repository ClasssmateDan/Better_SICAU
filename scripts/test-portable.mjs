import { mkdir } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root=fileURLToPath(new URL('../',import.meta.url));
const folder=path.join(root,'desktop','.test-profile-p-'+Date.now().toString(36));
await mkdir(folder,{recursive:true});
const compiler=path.join(process.env.SystemRoot||'C:/Windows','Microsoft.NET/Framework64/v4.0.30319/csc.exe');
const exe=path.join(folder,'PortableDataTests.exe');
function run(command,args){const result=spawnSync(command,args,{cwd:root,encoding:'utf8',windowsHide:true});if(result.error)throw result.error;process.stdout.write(result.stdout);process.stderr.write(result.stderr);if(result.status!==0)throw Error('便携数据检查失败');}
run(compiler,['/nologo','/reference:System.Core.dll','/out:'+exe,path.join(root,'desktop/PortableData.cs'),path.join(root,'desktop/PortableDataTests.cs')]);
run(exe,[path.join(folder,'fixtures')]);
