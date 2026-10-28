import { readFile } from 'node:fs/promises';
import { Script } from 'node:vm';

const root = new URL('../qr-tool/', import.meta.url);
const read = name => readFile(new URL(name, root), 'utf8');

export async function buildQrTool() {
  let html = await read('dist/index.html');
  const [css, integrationCss] = await Promise.all([read('dist/styles.css'), read('integrated.css')]);
  html = html.replace(/<link rel="icon"[^>]+>/, '')
    .replace('<link rel="stylesheet" href="styles.css">', () => `<style>\n${css}\n${integrationCss}\n</style>`);
  for (const name of ['vendor/jsQR.js', 'vendor/qrcode.js', 'qr-core.js', 'image-reader.js', 'app.js']) {
    const source = await read('dist/' + name);
    new Script(source, { filename: name });
    html = html.replace(`<script defer src="${name}"></script>`, () => `<script defer>\n${source.replace(/<\/script/gi, '<\\/script')}\n</script>`);
  }
  // Inline scripts execute after the DOM exists, including when used as srcdoc.
  const scripts = [...html.matchAll(/<script defer>([\s\S]*?)<\/script>/g)].map(match => match[1]);
  html = html.replace(/<script defer>[\s\S]*?<\/script>\s*/g, '')
    .replace('</body>', () => scripts.map(source => `<script>${source}</script>`).join('\n') + '\n</body>');
  html = html.replace(/<header class="topbar">[\s\S]*?<\/header>/, '')
    .replace(/<div><div class="eyebrow">[\s\S]*?<\/p><\/div>/,
      '<p class="integrated-note">本机生成 · 无需原二维码</p>')
    .replace(/<footer>[\s\S]*?<\/footer>/, '')
    .replace("connect-src 'none'; object-src 'none'; base-uri 'self'; form-action 'none'",
      "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'");
  if (/<(?:script|link)\b[^>]*(?:src|href)=/.test(html)) throw new Error('二维码工具仍引用外部资源。');
  return html;
}
