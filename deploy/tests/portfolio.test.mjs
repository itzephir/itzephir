import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium } from 'playwright-core';

async function headerGeometry(page) {
    await page.evaluate(() => document.fonts.ready);
    return page.locator('.app-header, .brand, .workspace-label, .header-actions, .webring, .webring a, .header-language, .desktop-theme, .header-github, .editor-tabs').evaluateAll(elements =>
        elements.filter(element => element.getClientRects().length).map(element => {
            const { x, y, width, height } = element.getBoundingClientRect();
            return { element: element.classList[0], box: [x, y, width, height].map(value => Math.round(value * 100) / 100) };
        }));
}

async function assertShellDimensions(page, width) {
    const mobile = width < 720;
    const box = selector => page.locator(selector).boundingBox();
    const near = (actual, expected, label) => assert.ok(Math.abs(actual - expected) < 0.03, `${label}: expected ${expected}, got ${actual}`);
    const header = await box('.app-header');
    near(header.x, 0, 'Header left'); near(header.y, 0, 'Header top');
    near(header.height, mobile ? 69 : 60, 'Header height');
    assert.equal(await page.locator('#portfolio').getAttribute('data-shell-contract'), '1');
    assert.equal(await page.locator('.brand').isVisible(), !mobile);
    assert.equal(await page.locator('.editor-tabs').isVisible(), !mobile);
    assert.equal(await page.locator('.workspace-label').isVisible(), width >= 1000);
    assert.equal(await page.locator('.header-language').isVisible(), width >= 1000);
    for (const selector of ['.desktop-theme', '.header-github']) {
        assert.equal(await page.locator(selector).isVisible(), !mobile);
        if (!mobile) { const control = await box(selector); near(control.width, 44, `${selector} width`); near(control.height, 44, `${selector} height`); }
    }
    const ring = await box('.webring');
    near(ring.y, 12, 'Webring top');
    near(ring.height, mobile ? 44 : 36, 'Webring height');
    near(ring.width, mobile ? header.width - 48 : 344, 'Webring width');
    if (mobile) near(ring.x, 24, 'Mobile webring left');
    else {
        const brand = await box('.brand'), tabs = await box('.editor-tabs');
        near(brand.x, width < 1000 ? 24 : 32, 'Brand left');
        near(brand.height, 32, 'Brand height');
        near(tabs.y, 60, 'Tabs top'); near(tabs.height, 45, 'Tabs height');
    }
}

test('Figma baseline: responsive themes, stateful tabs and no-JavaScript navigation', { timeout: 120_000 }, async () => {
    const port = process.env.TEST_PORT ?? '4173';
    const base = `http://127.0.0.1:${port}`;
    const root = fileURLToPath(new URL('../..', import.meta.url));
    // Exercise the same typed page registry as Ktor, including newly registered pages.
    const source = await readFile(resolve(root, 'website/src/main/kotlin/com/itzephir/website/Portfolio.kt'), 'utf8');
    const registry = source.slice(source.indexOf('enum class PortfolioPage'), source.indexOf('private const val telegram'));
    const routes = [...registry.matchAll(/^\s+\w+\("([^"]+)",/gm)].map(match => match[1]);
    assert.ok(routes.length >= 3 && routes.includes('/'), 'Portfolio page registry must be discoverable');
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
        // The shared shell must not move when the page, theme or scrollbar changes.
        for (const width of [1440, 1000, 999, 768, 720, 719, 390, 320]) {
            await page.setViewportSize({ width, height: 1000 });
            let expectedHeader;
            for (const theme of ['dark', 'light']) {
                for (const path of routes) {
                    await page.goto(`${base}${path}?theme=${theme}`, { waitUntil: 'load' });
                    const actualHeader = await headerGeometry(page);
                    await assertShellDimensions(page, width);
                    assert.equal(await page.locator('.workspace-label').textContent(), path);
                    assert.ok((await page.locator('.logo-artwork').getAttribute('src')).includes(`/design/${theme}/wordmark.svg?`));
                    expectedHeader ??= actualHeader;
                    assert.deepEqual(actualHeader, expectedHeader, `Header moved: ${path} ${theme} ${width}px`);
                    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `Overflow: ${path} ${theme} ${width}px`);
                }
            }
        }
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
                    if (!mobile) {
                        const slot = await page.locator('.logo').boundingBox();
                        const artwork = await page.locator('.logo-artwork').boundingBox();
                        assert.ok(Math.abs(slot.width - 89.36) < 0.02);
                        assert.equal(slot.height, 32);
                        assert.ok(Math.abs(artwork.width - 89.6) < 0.02);
                        assert.equal(artwork.height, 32);
                        assert.ok((await page.locator('.logo-artwork').getAttribute('src')).includes(`/design/${theme}/wordmark.svg?`));
                    }
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
        assert.ok((await page.locator('.profile-panel').textContent()).includes('Лектор ШМР Яндекса · 2026'));
        assert.ok(requests.every(url => url.startsWith(base)), 'Every browser asset must be self-hosted');
        assert.deepEqual(errors, []);
        assert.deepEqual(failedResponses, []);
        await context.close();

        const tabsContext = await browser.newContext({ viewport: { width: 1440, height: 1120 } });
        const workspace = await tabsContext.newPage();
        const tabKeys = () => workspace.locator('.editor-tab').evaluateAll(tabs => tabs.map(tab => tab.dataset.tab));
        const activeTab = () => workspace.locator('.editor-tab-link[aria-current="page"]').textContent();
        await workspace.goto(base, { waitUntil: 'networkidle' });
        const workspaceHeader = await headerGeometry(workspace);
        assert.deepEqual(await tabKeys(), ['welcome']);
        assert.equal(await workspace.locator('.tab-close').count(), 0);
        await workspace.locator('.shortcut[href="/projects"]').click();
        await workspace.waitForURL(`${base}/projects`);
        assert.deepEqual(await headerGeometry(workspace), workspaceHeader);
        assert.deepEqual(await tabKeys(), ['welcome', 'projects']);
        await workspace.locator('.editor-tab-link[href="/"]').click();
        await workspace.locator('.shortcut[href="/about"]').click();
        await workspace.waitForURL(`${base}/about`);
        assert.deepEqual(await headerGeometry(workspace), workspaceHeader);
        assert.deepEqual(await tabKeys(), ['welcome', 'projects', 'profile']);
        assert.equal((await activeTab()).trim(), 'about.md');
        await workspace.evaluate(() => document.fonts.ready);
        await workspace.screenshot({ path: resolve(screenshots, 'tabs-open-dark.png'), fullPage: true });
        await workspace.locator('.editor-tab-link[href="/projects"]').click();
        assert.deepEqual(await tabKeys(), ['welcome', 'projects', 'profile']);
        await workspace.locator('.theme-toggle:visible').click();
        await workspace.waitForSelector('#portfolio[data-theme="light"]');
        assert.deepEqual(await headerGeometry(workspace), workspaceHeader);
        assert.deepEqual(await tabKeys(), ['welcome', 'projects', 'profile']);
        await workspace.reload({ waitUntil: 'networkidle' });
        assert.deepEqual(await tabKeys(), ['welcome', 'projects', 'profile']);
        assert.ok((await tabsContext.cookies()).some(cookie => cookie.name === 'workspace_tabs' && cookie.httpOnly));
        await workspace.screenshot({ path: resolve(screenshots, 'tabs-open-light.png'), fullPage: true });
        // Closing an inactive tab must preserve the active page and its content.
        await workspace.getByRole('button', { name: 'Закрыть about.md', exact: true }).click();
        assert.equal(new URL(workspace.url()).pathname, '/projects');
        assert.deepEqual(await tabKeys(), ['welcome', 'projects']);
        assert.equal((await activeTab()).trim(), 'projects');
        await workspace.getByRole('button', { name: 'Закрыть projects', exact: true }).click();
        await workspace.waitForURL(`${base}/`);
        assert.deepEqual(await headerGeometry(workspace), workspaceHeader);
        assert.deepEqual(await tabKeys(), ['welcome']);
        assert.equal(await workspace.locator('#portfolio').getAttribute('data-theme'), 'light');
        await workspace.reload({ waitUntil: 'networkidle' });
        assert.deepEqual(await tabKeys(), ['welcome']);
        await workspace.locator('.shortcut[href="/about"]').click();
        await workspace.getByRole('button', { name: 'Закрыть welcome.md', exact: true }).click();
        assert.equal(new URL(workspace.url()).pathname, '/about');
        assert.deepEqual(await tabKeys(), ['profile']);
        await workspace.getByRole('button', { name: 'Закрыть about.md', exact: true }).click();
        await workspace.waitForURL(`${base}/`);
        assert.deepEqual(await tabKeys(), ['welcome']);
        await workspace.goBack({ waitUntil: 'networkidle' });
        assert.equal(new URL(workspace.url()).pathname, '/about');
        assert.equal((await activeTab()).trim(), 'about.md');
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
        await plain.getByRole('button', { name: 'Закрыть about.md', exact: true }).click();
        assert.equal(new URL(plain.url()).pathname, '/');
        assert.equal(await plain.locator('#portfolio').getAttribute('data-theme'), 'dark');
        await noJs.close();
    } finally {
        await browser?.close();
        server.kill('SIGTERM');
        await new Promise(resolveExit => { if (server.exitCode !== null) resolveExit(); else server.once('exit', resolveExit); });
    }
});
