import { readFile } from 'node:fs/promises';

export async function createAndroidDocument(standalone) {
  const [adapter, css, manifest] = await Promise.all(['mobile-adapter.js','mobile.css','AndroidManifest.xml'].map(name=>readFile(new URL('../android/'+name,import.meta.url),'utf8')));
  const version = manifest.match(/android:versionName="([\d.]+)"/)[1];
  return standalone.replace('<head>', '<head><script>window.__KEJIAN_VERSION__='+JSON.stringify(version)+';/* KEJIAN_BOOTSTRAP */</script><script>' + adapter.replace(/<\/script/gi,'<\\/script') + '</script>')
    .replace('</head>', '<style>'+css+'</style></head>');
}
