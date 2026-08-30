// Optional browser benchmark. Requires Playwright and a Chromium installation.
// Usage: node deploy/benchmark-startup.mjs /path/to/before /path/to/after
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { extname, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';

const playwrightModule = process.env.PLAYWRIGHT_MODULE
  ? pathToFileURL(resolve(process.env.PLAYWRIGHT_MODULE)).href : 'playwright';
const { chromium } = await import(playwrightModule);
const directories = process.argv.slice(2);
if (!directories.length) throw new Error('Pass one or more prepared distribution directories.');
const repeats = Number(process.env.REPEATS ?? 3);
const types = { '.html': 'text/html', '.js': 'application/javascript', '.wasm': 'application/wasm',
  '.css': 'text/css', '.svg': 'image/svg+xml', '.jpg': 'image/jpeg' };
const servers = [];
let browser;

async function serve(directory) {
  const root = resolve(directory);
  const server = createServer(async (request, response) => {
    try {
      const path = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
      const filename = resolve(root, `.${path === '/' ? '/index.html' : path}`);
      if (!filename.startsWith(`${root}${sep}`)) throw new Error('Invalid path');
      let content;
      let encoding;
      for (const candidate of ['br', 'gzip']) {
        if (!request.headers['accept-encoding']?.includes(candidate)) continue;
        try {
          content = await readFile(`${filename}.${candidate === 'br' ? 'br' : 'gz'}`);
          encoding = candidate;
          break;
        } catch { /* An unsqueezed asset is also valid. */ }
      }
      content ??= await readFile(filename);
      response.setHeader('Content-Type', types[extname(filename)] ?? 'application/octet-stream');
      response.setHeader('Vary', 'Accept-Encoding');
      // Match the existing nginx policy, including the legacy no-store entry.
      response.setHeader('Cache-Control', /\/(index\.html|itzephir\.js)$/.test(filename)
        ? 'no-store' : filename.endsWith('.wasm') ? 'public, max-age=31536000, immutable' : 'public, max-age=3600');
      if (encoding) response.setHeader('Content-Encoding', encoding);
      response.setHeader('Content-Length', content.length);
      response.end(content);
    } catch {
      response.writeHead(404).end();
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  servers.push(server);
  return `http://127.0.0.1:${server.address().port}/`;
}

async function measure(page, url, label, trial, cache) {
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForFunction(() => window.__composeReadyAt, null, { timeout: 60000 });
  const metrics = await page.evaluate(() => ({
    firstContentMs: Math.round(performance.getEntriesByName('first-contentful-paint')[0]?.startTime ?? 0),
    readyMs: Math.round(window.__composeReadyAt),
    resources: performance.getEntriesByType('resource').filter(r => !r.name.endsWith('favicon.ico')).map(r => ({
      file: new URL(r.name).pathname,
      startMs: Math.round(r.startTime),
      durationMs: Math.round(r.duration),
      transferBytes: r.transferSize,
      encodedBytes: r.encodedBodySize,
    })),
  }));
  console.log(JSON.stringify({ directory: label, trial, cache, ...metrics }));
}

try {
  browser = await chromium.launch({ headless: true,
    ...(process.env.CHROME_EXECUTABLE ? { executablePath: process.env.CHROME_EXECUTABLE } : {}) });
  const sites = await Promise.all(directories.map(async directory => ({ directory, url: await serve(directory) })));
  for (let trial = 1; trial <= repeats; trial++) {
    // Alternate order to reduce the influence of machine warm-up.
    for (const site of trial % 2 ? sites : [...sites].reverse()) {
      const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.addInitScript(() => {
        const observer = new MutationObserver(() => {
          if (document.getElementById('app')?.classList.contains('compose-ready')) {
            window.__composeReadyAt = performance.now();
            observer.disconnect();
          }
        });
        observer.observe(document, { childList: true, attributes: true, subtree: true });
      });
      const cdp = await context.newCDPSession(page);
      await cdp.send('Network.enable');
      await cdp.send('Network.emulateNetworkConditions', {
        offline: false, latency: 100, downloadThroughput: 1250000, uploadThroughput: 1250000,
      });
      await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
      await measure(page, site.url, site.directory, trial, 'cold');
      await measure(page, site.url, site.directory, trial, 'warm');
      if (errors.length) throw new Error(errors.join('\n'));
      await context.close();
    }
  }
} finally {
  await browser?.close();
  await Promise.all(servers.map(server => new Promise(resolve => server.close(resolve))));
}
