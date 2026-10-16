import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import test from 'node:test';
import { chromium } from 'playwright-core';

test('project panel: JWT login, owner isolation, admin workflow, public landing and mobile layout', {
    timeout: 120_000, skip: !process.env.LOCATOR_TEST_DATABASE_URL,
}, async () => {
    const root = fileURLToPath(new URL('../..', import.meta.url));
    const distribution = resolve(process.env.WEBSITE_DIST ?? resolve(root, 'website/build/install/website'));
    const output = resolve(process.env.BROWSER_OUTPUT ?? resolve(root, 'website/build/browser-check'));
    await mkdir(output, { recursive: true });
    const schema = `locator_browser_${randomUUID().replaceAll('-', '')}`;
    const java = process.env.JAVA_HOME ? resolve(process.env.JAVA_HOME, 'bin/java') : 'java';
    const dbArgs = ['-cp', resolve(distribution, 'lib/*'), resolve(root, 'deploy/tests/BrowserDatabase.java')];
    const database = action => execFileSync(java, [...dbArgs, action, schema], { stdio: ['ignore', 'pipe', 'pipe'] });
    database('create');
    const databaseUrl = new URL(process.env.LOCATOR_TEST_DATABASE_URL.slice(5));
    databaseUrl.searchParams.set('currentSchema', schema);
    const apiToken = randomBytes(32).toString('hex');
    const password = randomBytes(24).toString('hex');
    const base = 'http://127.0.0.1:4185';
    const panel = `${base}/dev/panel`;
    const server = spawn('bash', [resolve(distribution, 'bin/website')], {
        env: { ...process.env, PORT: '4185', BIND_HOST: '127.0.0.1', COOKIE_SECURE: 'false',
            LOCATOR_DATABASE_URL: `jdbc:${databaseUrl}`, LOCATOR_DATABASE_USER: process.env.LOCATOR_TEST_DATABASE_USER ?? 'locator',
            LOCATOR_DATABASE_PASSWORD: process.env.LOCATOR_TEST_DATABASE_PASSWORD || 'local-test-only',
            LOCATOR_ADMIN_TOKEN: apiToken, LOCATOR_JWT_SECRET: randomBytes(32).toString('hex'),
            LOCATOR_ADMIN_USERNAME: 'admin', LOCATOR_ADMIN_PASSWORD: password },
        stdio: ['ignore', 'pipe', 'pipe'],
    });
    let log = '';
    server.stdout.on('data', chunk => { log += chunk; });
    server.stderr.on('data', chunk => { log += chunk; });
    let browser;
    try {
        let ready = false;
        for (let attempt = 0; attempt < 80; attempt++) {
            if (server.exitCode !== null) throw new Error(`Panel server exited: ${log}`);
            try { ready = (await (await fetch(`${base}/healthz`)).text()) === 'ok'; if (ready) break; } catch { /* Starting. */ }
            await delay(250);
        }
        assert.ok(ready, `Panel server did not become ready: ${log}`);
        async function register(project, approved = false) {
            const response = await fetch(`${base}/api/project-locator/v1/projects`, { method: 'POST',
                headers: { 'Content-Type': 'application/json', ...(approved ? { Authorization: `Bearer ${apiToken}` } : {}) },
                body: JSON.stringify({ project, slug: project.replaceAll('-', '_') }) });
            assert.equal(response.status, 201);
            return response.json();
        }
        const own = await register('minor-website', true);
        const other = await register('another-project', true);
        const pending = await register('pending-project');
        browser = await chromium.launch({ executablePath: process.env.CHROME_EXECUTABLE, headless: true, args: ['--no-sandbox'] });
        const adminContext = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 1440, height: 1000 } });
        const admin = await adminContext.newPage();
        await admin.goto(panel);
        await admin.getByLabel('Логин', { exact: true }).fill('admin');
        await admin.getByLabel('Пароль', { exact: true }).fill(password);
        await admin.getByRole('button', { name: 'Войти', exact: true }).click();
        assert.equal(await admin.getByRole('heading', { name: 'Все проекты' }).count(), 1);
        const routes = ['/', '/projects', '/about', '/dev', '/dev/panel', '/dev/panel/users', '/dev/panel/account', '/dev/panel/new', `/dev/panel/${own.project.id}/edit`];
        for (const width of [320, 390, 719, 720, 768, 999, 1000, 1440]) {
            await admin.setViewportSize({ width, height: 1000 });
            let geometry;
            for (const theme of ['dark', 'light']) for (const route of routes) {
                const response = await admin.goto(`${base}${route}?theme=${theme}`);
                assert.equal(response.status(), 200, route);
                await admin.evaluate(() => document.fonts.ready);
                assert.equal(await admin.locator('#portfolio[data-shell-contract="1"]').count(), 1);
                assert.equal(await admin.locator('.app-header').count(), 1);
                assert.equal(await admin.locator('main#content').count(), 1);
                assert.equal(await admin.locator('.workspace-label').textContent(), route);
                const actual = await admin.locator('.app-header, .logo, .webring, .header-language, .desktop-theme, .header-github').evaluateAll(nodes => nodes.filter(n => n.getClientRects().length).map(n => {
                    const r = n.getBoundingClientRect(); return [n.className, ...[r.x, r.y, r.width, r.height].map(v => Math.round(v * 100) / 100)];
                }));
                geometry ??= actual;
                assert.deepEqual(actual, geometry, `Shared shell moved: ${route}, ${width}, ${theme}`);
                assert.ok(await admin.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `Overflow: ${route}, ${width}, ${theme}`);
                const images = await admin.locator('img').evaluateAll(nodes => nodes.filter(n => !n.complete || !n.naturalWidth).map(n => n.src));
                assert.deepEqual(images, [], route);
            }
        }
        await admin.setViewportSize({ width: 1440, height: 1000 });
        await admin.goto(panel);
        const jwtCookie = (await adminContext.cookies()).find(cookie => cookie.name === 'itzephir_locator_session');
        assert.ok(jwtCookie.httpOnly); assert.equal(jwtCookie.sameSite, 'Strict'); assert.equal(jwtCookie.value.split('.').length, 3);
        await admin.goto(`${panel}/users`);
        await admin.getByLabel('Логин', { exact: true }).fill('owner');
        await admin.getByLabel('Пароль', { exact: true }).fill(password);
        await admin.getByRole('button', { name: 'Добавить пользователя' }).click();
        await admin.goto(`${panel}/${own.project.id}/edit`);
        await admin.getByLabel('owner', { exact: true }).check();
        await admin.getByRole('button', { name: 'Сохранить владельцев' }).click();
        await admin.goto(panel);
        await admin.locator('article').filter({ hasText: pending.project.project }).getByRole('button', { name: 'Одобрить', exact: true }).click();
        const publicPage = await adminContext.newPage();
        await publicPage.goto(`${base}/dev`);
        assert.equal(await publicPage.locator('.project-card').count(), 3);
        assert.equal(await publicPage.locator(`a[href='/dev/project/${own.project.slug}/']`).count(), 1);
        await publicPage.screenshot({ path: resolve(output, 'development-1440.png'), fullPage: true });
        const ownerContext = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 900 } });
        const owner = await ownerContext.newPage();
        await owner.goto(panel);
        await owner.getByLabel('Логин', { exact: true }).fill('owner');
        await owner.getByLabel('Пароль', { exact: true }).fill(password);
        await owner.getByRole('button', { name: 'Войти', exact: true }).click();
        assert.equal(await owner.getByRole('heading', { name: 'Мои проекты' }).count(), 1);
        assert.equal(await owner.locator('article.project-row').count(), 1);
        assert.equal(await owner.getByText(other.project.project, { exact: true }).count(), 0);
        assert.equal(await owner.getByRole('button', { name: 'Одобрить', exact: true }).count(), 0);
        await owner.screenshot({ path: resolve(output, 'panel-owner-390.png'), fullPage: true });
        const hidden = await owner.goto(`${panel}/${other.project.id}/edit`);
        assert.equal(hidden.status(), 404);
        assert.equal(await owner.locator('#portfolio[data-shell-contract="1"]').count(), 1);
        assert.equal((await owner.goto(`${panel}/new`)).status(), 403);
        await owner.goto(`${panel}/${own.project.id}/edit`);
        await owner.getByLabel('Имя проекта', { exact: true }).fill('minor-updated');
        await owner.getByLabel('Slug в адресе', { exact: true }).fill('minor_updated');
        await owner.getByRole('button', { name: 'Сохранить изменения' }).click();
        assert.equal(await owner.locator('.badge.pending').count(), 1);
        assert.ok(await owner.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Owner form overflows on mobile');
        await owner.screenshot({ path: resolve(output, 'panel-edit-390.png'), fullPage: true });
        await publicPage.goto(`${base}/dev`);
        assert.equal(await publicPage.getByText('minor-updated', { exact: true }).count(), 0);
        await publicPage.setViewportSize({ width: 320, height: 900 });
        assert.ok(await publicPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Landing overflows at 320px');
        await publicPage.screenshot({ path: resolve(output, 'development-320.png'), fullPage: true });
        await admin.goto(panel);
        await admin.locator('article').filter({ hasText: 'minor-updated' }).getByRole('button', { name: 'Одобрить', exact: true }).click();
        await admin.screenshot({ path: resolve(output, 'panel-admin-1440.png'), fullPage: true });
        await publicPage.goto(`${base}/dev`);
        assert.equal(await publicPage.getByText('minor-updated', { exact: true }).count(), 1);
        await owner.getByRole('button', { name: 'Выйти', exact: true }).click();
        assert.equal(await owner.getByLabel('Логин', { exact: true }).count(), 1);
        assert.ok(!(await ownerContext.cookies()).some(cookie => cookie.name === 'itzephir_locator_session'));
        await admin.goto(`${panel}/new`);
        await admin.getByLabel('Имя проекта', { exact: true }).fill('ui-created');
        await admin.getByLabel('Slug в адресе', { exact: true }).fill('ui_created');
        await admin.getByRole('button', { name: 'Создать проект', exact: true }).click();
        assert.equal(await admin.getByRole('heading', { name: 'Проект создан', exact: true }).count(), 1);
        const issuedToken = await admin.locator('pre code').textContent();
        assert.match(issuedToken, /^itzp_[A-Za-z0-9_-]{43}$/);
        await admin.getByRole('link', { name: 'К настройкам проекта', exact: true }).click();
        assert.ok(!(await admin.locator('body').textContent()).includes(issuedToken), 'Token is shown only in creation response');
        await publicPage.goto(`${base}/dev`);
        assert.equal(await publicPage.getByText('ui-created', { exact: true }).count(), 1);
        // HTMX fragments and document restoration retain the same authenticated route and tab.
        const enhanced = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
        await enhanced.addCookies(await adminContext.cookies());
        const enhancedPage = await enhanced.newPage();
        for (const width of [1440, 390]) {
            await enhancedPage.setViewportSize({ width, height: 1000 });
            for (const route of ['/dev', '/dev/panel', `/dev/panel/${own.project.id}/edit`, '/dev/panel/users', '/dev/panel/account', '/dev/panel/new']) {
                await enhancedPage.goto(`${base}${route}?theme=dark`);
                await enhancedPage.evaluate(() => { window.devDocumentMarker = 'same'; });
                await enhancedPage.locator('.theme-toggle:visible').click();
                await enhancedPage.waitForSelector('#portfolio[data-theme="light"]');
                assert.equal(new URL(enhancedPage.url()).pathname, route);
                assert.equal(await enhancedPage.evaluate(() => window.devDocumentMarker), 'same');
                await enhancedPage.goto(`${base}${route}`);
                await enhancedPage.reload();
                assert.equal(await enhancedPage.locator('#portfolio').getAttribute('data-theme'), 'light');
                if (width === 1440) await enhancedPage.screenshot({ path: resolve(output, `dev-light-${route.split('/').at(-1) || 'home'}-1440.png`), fullPage: true });
            }
        }
        await enhancedPage.setViewportSize({ width: 1440, height: 1000 });
        await enhancedPage.goto(`${base}/dev`);
        const closeResponse = enhancedPage.waitForResponse(response => response.url().endsWith('/tabs/close') && response.request().method() === 'POST');
        await enhancedPage.locator('.editor-tab[data-tab="users"] .tab-close').click();
        const closeResult = await closeResponse;
        assert.equal(closeResult.status(), 303);
        assert.equal(new URL(enhancedPage.url()).pathname, '/dev');
        assert.equal(await enhancedPage.locator('.editor-tab[data-tab="users"]').count(), 0);
        await enhancedPage.locator('.editor-tab[data-tab="dev"] .tab-close').click();
        assert.equal(new URL(enhancedPage.url()).pathname, '/about');
        await enhancedPage.goto(`${base}/dev`);
        await enhancedPage.reload();
        assert.equal(await enhancedPage.locator('.editor-tab[data-tab="dev"]').count(), 1);
        await enhanced.close();
        await ownerContext.close(); await adminContext.close();
    } finally {
        await browser?.close();
        server.kill('SIGTERM');
        await new Promise(done => server.exitCode !== null ? done() : server.once('exit', done));
        database('drop');
    }
});
