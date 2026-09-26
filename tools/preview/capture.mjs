// node capture.mjs <out.png> <variant> <t> [jsonOverrides] [w] [h]
// Serves the repo root, renders one frame with the real shaders in headless Chromium (SwiftShader).
import { chromium } from 'playwright-core';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.bin': 'application/octet-stream' };
const server = http.createServer((req, res) => {
  const p = path.join(root, decodeURIComponent(req.url.split('?')[0]));
  fs.readFile(p, (e, d) => { if (e) { res.writeHead(404); res.end(); return; } res.writeHead(200, { 'Content-Type': types[path.extname(p)] || 'text/plain' }); res.end(d); });
}).listen(0);
const port = server.address().port;

const jobs = JSON.parse(process.argv[2]); // [{out, variant, t, o, w, h}]
const browser = await chromium.launch({
  executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
  args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});
const page = await browser.newPage();
page.on('console', m => console.log('[page]', m.text()));
let loaded = null;
for (const j of jobs) {
  const w = j.w || 900, h = j.h || 2000;
  const key = `${j.n}-${w}-${h}`;
  if (loaded !== key) {
    await page.setViewportSize({ width: w, height: h });
    await page.goto(`http://localhost:${port}/tools/preview/index.html?w=${w}&h=${h}&n=${j.n || 150000}`);
    await page.waitForFunction(() => window.ready || window.error, null, { timeout: 120000 });
    const err = await page.evaluate(() => window.error);
    if (err) { console.error(err); process.exit(1); }
    loaded = key;
  }
  const t0 = Date.now();
  const hdr = await page.evaluate(([t, pr, o]) => window.run(t, pr, o), [j.t, j.preset, j.o || {}]);
  await page.locator('canvas').screenshot({ path: j.out, timeout: 600000 });
  console.log(j.out, `hdr=${hdr}`, `${Date.now() - t0}ms`);
}
await browser.close();
server.close();
