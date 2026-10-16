import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium } from 'playwright-core';

test('Figma baseline: responsive themes, stateful tabs and no-JavaScript navigation', { timeout: 120_000 }, async () => {
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
            try { ready = await (await fetch(`${base}/healthz`)).text() === 'ok'; if (ready) break; }
            catch { /* Server is still starting. */ }
            await delay(250);
        }
        assert.ok(ready, `Ktor did not become ready: ${serverLog}`);
        browser = await chromium.launch({ executablePath: process.env.CHROME_EXECUTABLE, headless: true, args: ['--no-sandbox'] });
        const context = await browser.newContext();
        const page = await context.newPage();
        const errors = [], failedResponses = [], requests = [];
        page.on('pageerror', error => errors.push(error.message));
        page.on('response', response => { if (response.status() >= 400) failedResponses.push(response.url()); });
        page.on('request', request => requests.push(request.url()));
        for (const theme of ['dark', 'light']) {
            await page.emulateMedia({ colorScheme: theme });
            for (const width of [1440, 768, 720, 719, 390, 320]) {
                for (const path of ['/', '/projects']) {
                    const mobile = width < 720;
                    const height = width === 1440 ? (path === '/' ? 1120 : 1522) : (mobile ? 972 : 1000);
                    await page.setViewportSize({ width, height });
                    await page.goto(`${base}${path}?theme=${theme}`, { waitUntil: 'networkidle' });
                    await page.evaluate(() => document.fonts.ready);
                    assert.equal(await page.locator('h1').count(), 1);
                    assert.equal(await page.locator('#portfolio').getAttribute('data-theme'), theme);
                    assert.equal(await page.locator('.theme-toggle:visible').count(), 1);
                    assert.equal(await page.locator('a.project').count(), path === '/projects' ? 6 : 0);
                    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `Overflow: ${path} ${theme} ${width}px`);
                    const rings = await page.locator('.webring a').evaluateAll(links => links.map(a => {
                        const r = a.getBoundingClientRect();
                        return { href: a.href, text: a.textContent.trim(), x: r.x, y: r.y, right: r.right, bottom: r.bottom, h: r.height };
                    }));
                    assert.deepEqual(rings.map(a => a.href), ['https://webring.otomir23.me/itzephir/prev', 'https://webring.otomir23.me/', 'https://webring.otomir23.me/itzephir/next']);
                    for (const link of rings) {
                        assert.ok(link.text);
                        assert.ok(link.x >= 0 && link.right <= width && link.y >= 0 && link.bottom <= height, 'Webring must fit in the first screen');
                        if (mobile) assert.ok(link.h >= 44, 'Mobile ring links must be at least 44px high');
                    }
                    assert.equal(await page.locator('.brand').isVisible(), !mobile);
                    assert.equal(await page.locator('.editor-tabs').isVisible(), !mobile);
                    const brokenImages = await page.locator('img:visible').evaluateAll(images => images.filter(img => !img.complete || !img.naturalWidth).map(img => img.src));
                    assert.deepEqual(brokenImages, []);
                    if (path === '/') {
                        const portrait = await page.locator('.portrait').boundingBox();
                        assert.equal(portrait.width, mobile ? 48 : 56);
                        assert.equal(portrait.height, mobile ? 48 : 56);
                    } else if (mobile) assert.ok(await page.locator('.back-link').isVisible());
                    const logos = mobile ? null : await page.locator('.logo-artwork').boundingBox();
                    if (logos) { assert.equal(logos.width, 28); assert.equal(logos.height, 28); }
                    if ([1440, 390, 320].includes(width)) await page.screenshot({
                        path: resolve(screenshots, `${theme}-${path === '/' ? 'welcome' : 'projects'}-${width}.png`), fullPage: true,
                    });
                }
            }
        }
        // Switching theme is enhanced by HTMX and must preserve the current page/document.
        for (const width of [1440, 390]) {
            await page.setViewportSize({ width, height: 972 });
            for (const path of ['/', '/projects', '/about']) {
                await page.goto(`${base}${path}?theme=dark`, { waitUntil: 'networkidle' });
                await page.evaluate(() => { window.portfolioDocumentMarker = 'same-document'; });
                await page.locator('.theme-toggle:visible').click();
                await page.waitForSelector('#portfolio[data-theme="light"]');
                assert.equal(await page.evaluate(() => window.portfolioDocumentMarker), 'same-document');
                assert.equal(new URL(page.url()).pathname, path);
                assert.ok((await context.cookies()).some(cookie => cookie.name === 'theme' && cookie.value === 'light'));
                // A clean URL must use the persisted cookie, not the preceding query parameter.
                await page.goto(`${base}${path}`, { waitUntil: 'networkidle' });
                assert.equal(await page.locator('#portfolio').getAttribute('data-theme'), 'light');
                await page.reload({ waitUntil: 'networkidle' });
                assert.equal(await page.locator('#portfolio').getAttribute('data-theme'), 'light');
            }
        }
        await page.goto(base, { waitUntil: 'networkidle' });
        await page.locator('.shortcut[href="/projects"]').click();
        await page.waitForURL(`${base}/projects`);
        assert.equal(await page.locator('a.project').count(), 6);
        await page.locator('.back-link').click();
        await page.waitForURL(`${base}/`);
        await page.goBack();
        assert.equal(new URL(page.url()).pathname, '/projects');
        assert.equal(await page.locator('#portfolio').getAttribute('data-theme'), 'light');
        await page.goto(`${base}/about`, { waitUntil: 'networkidle' });
        assert.equal(await page.locator('h1').textContent(), 'Обо мне');
        assert.ok((await page.locator('.profile-panel').textContent()).includes('одной лекцией'));
        assert.ok(requests.every(url => url.startsWith(base)), 'Every browser asset must be self-hosted');
        assert.deepEqual(errors, []);
        assert.deepEqual(failedResponses, []);
        await context.close();

        const tabsContext = await browser.newContext({ viewport: { width: 1440, height: 1120 } });
        const workspace = await tabsContext.newPage();
        const tabKeys = () => workspace.locator('.editor-tab').evaluateAll(tabs => tabs.map(tab => tab.dataset.tab));
        const activeTab = () => workspace.locator('.editor-tab-link[aria-current="page"]').textContent();
        await workspace.goto(base, { waitUntil: 'networkidle' });
        assert.deepEqual(await tabKeys(), ['welcome']);
        assert.equal(await workspace.locator('.tab-close').count(), 0);
        await workspace.locator('.shortcut[href="/projects"]').click();
        await workspace.waitForURL(`${base}/projects`);
        assert.deepEqual(await tabKeys(), ['welcome', 'projects']);
        await workspace.locator('.editor-tab-link[href="/"]').click();
        await workspace.locator('.shortcut[href="/about"]').click();
        await workspace.waitForURL(`${base}/about`);
        assert.deepEqual(await tabKeys(), ['welcome', 'projects', 'profile']);
        assert.equal((await activeTab()).trim(), 'profile.kt');
        await workspace.evaluate(() => document.fonts.ready);
        await workspace.screenshot({ path: resolve(screenshots, 'tabs-open-dark.png'), fullPage: true });
        await workspace.locator('.editor-tab-link[href="/projects"]').click();
        assert.deepEqual(await tabKeys(), ['welcome', 'projects', 'profile']);
        await workspace.locator('.theme-toggle:visible').click();
        await workspace.waitForSelector('#portfolio[data-theme="light"]');
        assert.deepEqual(await tabKeys(), ['welcome', 'projects', 'profile']);
        await workspace.reload({ waitUntil: 'networkidle' });
        assert.deepEqual(await tabKeys(), ['welcome', 'projects', 'profile']);
        assert.ok((await tabsContext.cookies()).some(cookie => cookie.name === 'workspace_tabs' && cookie.httpOnly));
        await workspace.screenshot({ path: resolve(screenshots, 'tabs-open-light.png'), fullPage: true });
        // Closing an inactive tab must preserve the active page and its content.
        await workspace.getByRole('button', { name: 'Закрыть profile.kt', exact: true }).click();
        assert.equal(new URL(workspace.url()).pathname, '/projects');
        assert.deepEqual(await tabKeys(), ['welcome', 'projects']);
        assert.equal((await activeTab()).trim(), 'projects');
        await workspace.getByRole('button', { name: 'Закрыть projects', exact: true }).click();
        await workspace.waitForURL(`${base}/`);
        assert.deepEqual(await tabKeys(), ['welcome']);
        assert.equal(await workspace.locator('#portfolio').getAttribute('data-theme'), 'light');
        await workspace.reload({ waitUntil: 'networkidle' });
        assert.deepEqual(await tabKeys(), ['welcome']);
        await workspace.locator('.shortcut[href="/about"]').click();
        await workspace.getByRole('button', { name: 'Закрыть welcome.md', exact: true }).click();
        assert.equal(new URL(workspace.url()).pathname, '/about');
        assert.deepEqual(await tabKeys(), ['profile']);
        await workspace.getByRole('button', { name: 'Закрыть profile.kt', exact: true }).click();
        await workspace.waitForURL(`${base}/`);
        assert.deepEqual(await tabKeys(), ['welcome']);
        await workspace.goBack({ waitUntil: 'networkidle' });
        assert.equal(new URL(workspace.url()).pathname, '/about');
        assert.equal((await activeTab()).trim(), 'profile.kt');
        await tabsContext.close();

        const noJs = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 844 } });
        const plain = await noJs.newPage();
        await plain.goto(base);
        assert.equal(await plain.locator('.webring a:visible').count(), 3);
        await plain.locator('.theme-toggle:visible').click();
        assert.equal(await plain.locator('#portfolio').getAttribute('data-theme'), 'light');
        await plain.locator('.shortcut[href="/projects"]').click();
        assert.equal(await plain.locator('a.project').count(), 6);
        assert.equal(await plain.locator('#portfolio').getAttribute('data-theme'), 'light');
        await plain.locator('.theme-toggle:visible').click();
        assert.equal(await plain.locator('#portfolio').getAttribute('data-theme'), 'dark');
        await plain.locator('.back-link').click();
        assert.equal(await plain.locator('#portfolio').getAttribute('data-theme'), 'dark');
        await plain.screenshot({ path: resolve(screenshots, 'no-js-390.png'), fullPage: true });
        await plain.setViewportSize({ width: 1440, height: 1000 });
        await plain.getByRole('button', { name: 'Закрыть projects', exact: true }).click();
        assert.equal(new URL(plain.url()).pathname, '/');
        assert.deepEqual(await plain.locator('.editor-tab').evaluateAll(tabs => tabs.map(tab => tab.dataset.tab)), ['welcome']);
        await plain.locator('.shortcut[href="/about"]').click();
        await plain.getByRole('button', { name: 'Закрыть profile.kt', exact: true }).click();
        assert.equal(new URL(plain.url()).pathname, '/');
        assert.equal(await plain.locator('#portfolio').getAttribute('data-theme'), 'dark');
        await noJs.close();
    } finally {
        await browser?.close();
        server.kill('SIGTERM');
        await new Promise(resolveExit => { if (server.exitCode !== null) resolveExit(); else server.once('exit', resolveExit); });
    }
});
