import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium } from 'playwright-core';

test('packaged Ktor portfolio: responsive content, HTMX and no-JavaScript fallback', { timeout: 90_000 }, async () => {
    const port = process.env.TEST_PORT ?? '4173';
    const base = `http://127.0.0.1:${port}`;
    const root = fileURLToPath(new URL('../..', import.meta.url));
    const distribution = resolve(process.env.WEBSITE_DIST ?? resolve(root, 'website/build/install/website'));
    const screenshots = resolve(process.env.BROWSER_OUTPUT ?? resolve(root, 'website/build/browser-check'));
    await mkdir(screenshots, { recursive: true });
    const server = spawn('bash', [resolve(distribution, 'bin/website')], {
        env: { ...process.env, PORT: port, BIND_HOST: '127.0.0.1', COOKIE_SECURE: 'false' },
        stdio: ['ignore', 'pipe', 'pipe'],
    });
    let serverLog = '';
    server.stdout.on('data', chunk => { serverLog += chunk; });
    server.stderr.on('data', chunk => { serverLog += chunk; });
    let browser;
    try {
        let ready = false;
        for (let attempt = 0; attempt < 80; attempt++) {
            if (server.exitCode !== null) throw new Error(`Ktor exited: ${serverLog}`);
            try {
                ready = await (await fetch(`${base}/healthz`)).text() === 'ok';
                if (ready) break;
            } catch { /* Server is still starting. */ }
            await delay(250);
        }
        assert.ok(ready, `Ktor did not become ready: ${serverLog}`);
        browser = await chromium.launch({
            executablePath: process.env.CHROME_EXECUTABLE,
            headless: true,
            args: ['--no-sandbox'],
        });
        const context = await browser.newContext();
        const page = await context.newPage();
        const errors = [];
        const failedResponses = [];
        const requests = [];
        page.on('pageerror', error => errors.push(error.message));
        page.on('response', response => { if (response.status() >= 400) failedResponses.push(response.url()); });
        page.on('request', request => requests.push(request.url()));
        for (const width of [1440, 768, 390, 320]) {
            await page.setViewportSize({ width, height: 1000 });
            await page.goto(base, { waitUntil: 'networkidle' });
            await page.evaluate(() => document.fonts.ready);
            assert.equal(await page.locator('h1').count(), 1);
            assert.equal(await page.locator('.project').count(), 6);
            assert.equal(await page.locator('.contact-link').count(), 3);
            assert.equal(await page.locator('.webring a').count(), 3);
            assert.ok(await page.locator('.portrait').evaluate(img => img.complete && img.naturalWidth > 0));
            assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `Overflow at ${width}px`);
            await page.screenshot({ path: resolve(screenshots, `dark-${width}.png`), fullPage: true });
        }
        await page.setViewportSize({ width: 1440, height: 1000 });
        await page.goto(base, { waitUntil: 'networkidle' });
        await page.evaluate(() => { window.portfolioDocumentMarker = 'same-document'; });
        await page.locator('#theme-toggle').click();
        await page.waitForSelector('#portfolio[data-theme="light"]');
        assert.equal(await page.evaluate(() => window.portfolioDocumentMarker), 'same-document');
        assert.equal(new URL(page.url()).search, '');
        assert.ok((await context.cookies()).some(cookie => cookie.name === 'theme' && cookie.value === 'light'));
        assert.equal(await page.locator('.project').count(), 6);
        await page.screenshot({ path: resolve(screenshots, 'light-1440.png'), fullPage: true });
        await page.reload({ waitUntil: 'networkidle' });
        assert.equal(await page.locator('#portfolio').getAttribute('data-theme'), 'light');
        await page.locator('#theme-toggle').click();
        await page.waitForSelector('#portfolio[data-theme="dark"]');
        assert.ok(requests.every(url => !url.includes('.wasm')));
        assert.ok(requests.every(url => url.startsWith(base)), 'Every initial asset must be self-hosted');
        assert.deepEqual(errors, []);
        assert.deepEqual(failedResponses, []);
        await context.close();

        const noJs = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 1000 } });
        const plainPage = await noJs.newPage();
        await plainPage.goto(base);
        assert.equal(await plainPage.locator('.project').count(), 6);
        await plainPage.locator('#theme-toggle').click();
        assert.equal(await plainPage.locator('#portfolio').getAttribute('data-theme'), 'light');
        await plainPage.goto(base);
        assert.equal(await plainPage.locator('#portfolio').getAttribute('data-theme'), 'light');
        await plainPage.screenshot({ path: resolve(screenshots, 'no-js-390.png'), fullPage: true });
        await noJs.close();
    } finally {
        await browser?.close();
        server.kill('SIGTERM');
        await new Promise(resolveExit => {
            if (server.exitCode !== null) resolveExit();
            else server.once('exit', resolveExit);
        });
    }
});
