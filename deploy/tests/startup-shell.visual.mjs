import assert from 'node:assert/strict';
import { mkdir, readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { extname, resolve, sep } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';
import pngjs from 'pngjs';

const { PNG } = pngjs;
const repository = fileURLToPath(new URL('../../', import.meta.url));
const distribution = resolve(process.env.VISUAL_DIST
  ?? `${repository}/website/build/dist/wasmJs/productionExecutable`);
const executablePath = process.env.CHROME_EXECUTABLE;
const debugDirectory = process.env.VISUAL_DEBUG_DIR;
const colors = {
  background: [23, 23, 25],
  surfaceVariant: [37, 35, 38],
  text: [240, 234, 239],
  secondary: [168, 199, 213],
  primary: [220, 165, 210],
  body: [201, 194, 200],
  primaryButton: [220, 165, 210],
  secondaryButton: [41, 75, 87],
};
const types = {
  '.html': 'text/html', '.js': 'application/javascript', '.wasm': 'application/wasm',
  '.css': 'text/css', '.svg': 'image/svg+xml', '.jpg': 'image/jpeg', '.ttf': 'font/ttf',
};

function pixelMatches(data, index, target, tolerance = 10) {
  return target.every((value, channel) => Math.abs(data[index + channel] - value) <= tolerance);
}

function colorBounds(image, target, [left, top, right, bottom], tolerance = 10) {
  let minX = right, minY = bottom, maxX = -1, maxY = -1, count = 0;
  for (let y = top; y < bottom; y++) {
    for (let x = left; x < right; x++) {
      if (!pixelMatches(image.data, (y * image.width + x) * 4, target, tolerance)) continue;
      minX = Math.min(minX, x); minY = Math.min(minY, y);
      maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); count++;
    }
  }
  assert.ok(count > 20, `Landmark color ${target} was not found in ${left},${top},${right},${bottom}`);
  return { x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1 };
}

function largestForeground(image, [left, top, right, bottom]) {
  const width = right - left;
  const height = bottom - top;
  const mask = new Uint8Array(width * height);
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const index = ((top + y) * image.width + left + x) * 4;
    if (!pixelMatches(image.data, index, colors.background, 14)) mask[y * width + x] = 1;
  }
  let best;
  for (let start = 0; start < mask.length; start++) {
    if (!mask[start]) continue;
    const queue = [start]; mask[start] = 0;
    let minX = start % width, maxX = minX, minY = Math.floor(start / width), maxY = minY;
    for (let cursor = 0; cursor < queue.length; cursor++) {
      const at = queue[cursor], x = at % width, y = Math.floor(at / width);
      minX = Math.min(minX, x); minY = Math.min(minY, y);
      maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
      for (const next of [at - 1, at + 1, at - width, at + width]) {
        if (next >= 0 && next < mask.length && mask[next] && Math.abs((next % width) - x) <= 1) {
          mask[next] = 0; queue.push(next);
        }
      }
    }
    if (!best || queue.length > best.count) {
      best = { count: queue.length, x: left + minX, y: top + minY,
        width: maxX - minX + 1, height: maxY - minY + 1 };
    }
  }
  assert.ok(best?.count > 1000, 'Portrait foreground was not found');
  delete best.count;
  return best;
}

function interiorLabelBounds(image, container, background, horizontalInset, verticalInset) {
  const left = container.x + horizontalInset, top = container.y + verticalInset;
  const right = container.x + container.width - horizontalInset;
  const bottom = container.y + container.height - verticalInset;
  let minX = right, minY = bottom, maxX = -1, maxY = -1, count = 0;
  for (let y = top; y < bottom; y++) for (let x = left; x < right; x++) {
    const index = (y * image.width + x) * 4;
    if (pixelMatches(image.data, index, background, 12)) continue;
    minX = Math.min(minX, x); minY = Math.min(minY, y);
    maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); count++;
  }
  assert.ok(count > 20, `Label was not found inside ${JSON.stringify(container)}`);
  return { x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1 };
}

function landmarks(buffer, compact) {
  const image = PNG.sync.read(buffer);
  const roi = compact ? {
    brand: [0, 0, 220, 90], theme: [250, 0, 390, 90], eyebrow: [0, 90, 200, 170],
    title: [0, 150, 390, 300], subtitle: [0, 290, 390, 350], role: [0, 320, 390, 380],
    lead: [0, 350, 390, 490], buttons: [0, 480, 390, 580], portrait: [0, 570, 390, 844],
  } : {
    brand: [0, 0, 300, 110], theme: [1100, 0, 1440, 110], eyebrow: [0, 130, 250, 220],
    title: [0, 200, 700, 400], subtitle: [0, 380, 700, 450], role: [0, 420, 500, 470],
    lead: [0, 450, 700, 540], buttons: [0, 520, 500, 640], portrait: [850, 150, 1400, 650],
  };
  const eyebrow = colorBounds(image, colors.surfaceVariant, roi.eyebrow, 3);
  const primaryButton = colorBounds(image, colors.primaryButton, roi.buttons, 3);
  const secondaryButton = colorBounds(image, colors.secondaryButton, roi.buttons, 3);
  return {
    brand: colorBounds(image, colors.text, roi.brand),
    theme: colorBounds(image, colors.surfaceVariant, roi.theme, 3),
    eyebrow,
    eyebrowLabel: interiorLabelBounds(image, eyebrow, colors.surfaceVariant, 8, 6),
    title: colorBounds(image, colors.text, roi.title),
    subtitle: colorBounds(image, colors.secondary, roi.subtitle),
    role: colorBounds(image, colors.primary, roi.role),
    lead: colorBounds(image, colors.body, roi.lead),
    primaryButton,
    primaryButtonLabel: interiorLabelBounds(image, primaryButton, colors.primaryButton, 12, 12),
    secondaryButton,
    secondaryButtonLabel: interiorLabelBounds(image, secondaryButton, colors.secondaryButton, 12, 12),
    portrait: largestForeground(image, roi.portrait),
  };
}

async function waitForLandmarks(page, compact) {
  let lastError;
  for (let attempt = 0; attempt < 50; attempt++) {
    try {
      return landmarks(await page.screenshot(), compact);
    } catch (error) {
      lastError = error;
      await page.waitForTimeout(100);
    }
  }
  throw lastError;
}

function visualDrifts(shell, compose, viewport) {
  const drifts = [];
  for (const name of Object.keys(shell)) {
    for (const field of ['x', 'y', 'width', 'height']) {
      const difference = Math.abs(shell[name][field] - compose[name][field]);
      if (difference > 1) {
        drifts.push(`${viewport} ${name}.${field} drifted by ${difference}px: HTML=${shell[name][field]}, Compose=${compose[name][field]}`);
      }
    }
  }
  if (shell.primaryButton.y !== shell.secondaryButton.y || compose.primaryButton.y !== compose.secondaryButton.y) {
    drifts.push(`${viewport} startup and Compose buttons must stay on one row`);
  }
  return drifts;
}

async function serve() {
  const server = createServer(async (request, response) => {
    try {
      const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
      const filename = resolve(distribution, `.${pathname === '/' ? '/index.html' : pathname}`);
      if (!filename.startsWith(`${distribution}${sep}`)) throw new Error('Invalid path');
      const content = await readFile(filename);
      response.writeHead(200, { 'Content-Type': types[extname(filename)] ?? 'application/octet-stream' });
      response.end(content);
    } catch { response.writeHead(404).end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return server;
}

test('startup shell is revealed atomically after fonts and portrait are ready', async () => {
  assert.ok(executablePath, 'Set CHROME_EXECUTABLE to a Chrome or Chromium binary');
  const server = await serve();
  const browser = await chromium.launch({ executablePath, headless: true });
  let releaseFonts;
  let releasePortrait;
  let releaseWasm;
  const fontGate = new Promise(resolve => { releaseFonts = resolve; });
  const portraitGate = new Promise(resolve => { releasePortrait = resolve; });
  const wasmGate = new Promise(resolve => { releaseWasm = resolve; });
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    await page.route('**/*.ttf', async route => { await fontGate; await route.continue(); });
    await page.route('**/avatar.jpg', async route => { await portraitGate; await route.continue(); });
    await page.route('**/*.wasm', async route => { await wasmGate; await route.continue(); });
    await page.goto(`http://127.0.0.1:${server.address().port}/`, { waitUntil: 'domcontentloaded' });
    assert.equal(await page.evaluate(() => document.fonts.status), 'loading');
    assert.equal(await page.locator('.shell-page').evaluate(element => getComputedStyle(element).visibility), 'hidden');

    // CDP captures the actual interim frame without Playwright waiting for web
    // fonts. No styled surface may leak through before all shell assets exist.
    const cdp = await context.newCDPSession(page);
    const screenshot = await cdp.send('Page.captureScreenshot', {
      format: 'png',
      captureBeyondViewport: false,
    });
    const image = PNG.sync.read(Buffer.from(screenshot.data, 'base64'));
    for (let y = 0; y < image.height; y += 20) for (let x = 0; x < image.width; x += 20) {
      assert.ok(pixelMatches(image.data, (y * image.width + x) * 4, colors.background, 2),
        `Partial startup shell leaked at ${x},${y}`);
    }

    releaseFonts();
    releasePortrait();
    await page.waitForFunction(() => document.getElementById('app')?.classList.contains('shell-ready'));
    await page.locator('.shell-portrait img').evaluate(image => image.decode());
    landmarks(await page.screenshot(), false);
  } finally {
    releaseFonts?.();
    releasePortrait?.();
    releaseWasm?.();
    await browser.close();
    await new Promise(resolve => server.close(resolve));
  }
});

test('HTML startup shell stays visually aligned with Compose on desktop and mobile', async () => {
  assert.ok(executablePath, 'Set CHROME_EXECUTABLE to a Chrome or Chromium binary');
  const server = await serve();
  const browser = await chromium.launch({ executablePath, headless: true });
  const drifts = [];
  try {
    if (debugDirectory) await mkdir(debugDirectory, { recursive: true });
    for (const [viewport, width, height] of [['desktop', 1440, 1000], ['mobile', 390, 844]]) {
      const context = await browser.newContext({ viewport: { width, height } });
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      let releaseWasm;
      const wasmGate = new Promise(resolve => { releaseWasm = resolve; });
      await page.route('**/*.wasm', async route => { await wasmGate; await route.continue(); });
      await page.goto(`http://127.0.0.1:${server.address().port}/`, { waitUntil: 'domcontentloaded' });
      await page.evaluate(() => document.fonts.ready);
      await page.locator('.shell-portrait img').evaluate(image => image.decode());
      const platformClass = process.platform === 'darwin' ? 'platform-macos'
        : process.platform === 'linux' ? 'platform-linux' : null;
      if (platformClass) {
        assert.equal(await page.evaluate(name => document.documentElement.classList.contains(name), platformClass), true);
      }
      assert.equal(await page.locator('.boot-screen').evaluate(element => getComputedStyle(element).transitionDuration), '0s');
      if (debugDirectory) await page.screenshot({ path: `${debugDirectory}/${viewport}-shell.png` });
      const shell = await waitForLandmarks(page, viewport === 'mobile');
      releaseWasm();
      await page.waitForFunction(() => document.getElementById('app')?.classList.contains('compose-ready'), null,
        { timeout: 60000 });
      await page.waitForFunction(() => !document.getElementById('boot-screen'), null, { timeout: 10000 });
      if (debugDirectory) await page.screenshot({ path: `${debugDirectory}/${viewport}-compose.png` });
      const compose = await waitForLandmarks(page, viewport === 'mobile');
      drifts.push(...visualDrifts(shell, compose, viewport));
      assert.deepEqual(errors, []);
      await context.close();
    }
    assert.deepEqual(drifts, []);
  } finally {
    await browser.close();
    await new Promise(resolve => server.close(resolve));
  }
});
