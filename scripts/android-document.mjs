import { readFile } from 'node:fs/promises';

export async function createAndroidDocument(standalone) {
  const [adapter, css] = await Promise.all(['mobile-adapter.js','mobile.css'].map(name=>readFile(new URL('../android/'+name,import.meta.url),'utf8')));
  return standalone.replace('<head>', '<head><script>/* KEJIAN_BOOTSTRAP */</script><script>' + adapter.replace(/<\/script/gi,'<\\/script') + '</script>')
    .replace('</head>', '<style>'+css+'</style></head>');
}
