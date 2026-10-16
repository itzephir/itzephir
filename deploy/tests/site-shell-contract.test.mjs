import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../..', import.meta.url));
const protectedClasses = /\.(?:skip-link|app-header|brand|logo(?:-artwork)?|workspace-label|header-actions|header-language|header-github|desktop-theme|webring|ring-[\w-]+|editor-tabs?|editor-tab-link|tab-close(?:-form)?)(?![\w-])/;

async function files(directory) {
    const entries = await readdir(directory, { withFileTypes: true });
    return (await Promise.all(entries.map(entry => entry.isDirectory()
        ? files(resolve(directory, entry.name)) : resolve(directory, entry.name)))).flat();
}

test('site shell has one rendering owner and one stylesheet owner', async () => {
    const staticRoot = resolve(root, 'website/src/main/resources/static');
    for (const path of (await files(staticRoot)).filter(path => path.endsWith('.css') && !path.endsWith('/site-shell.css'))) {
        assert.doesNotMatch(await readFile(path, 'utf8'), protectedClasses, `Shell selectors belong in site-shell.css: ${path}`);
    }
    const shellCss = await readFile(resolve(staticRoot, 'site-shell.css'), 'utf8');
    assert.doesNotMatch(shellCss, /\.page-[\w-]+|\[data-page/, 'Shell CSS must not depend on the current page');
    const kotlinRoot = resolve(root, 'website/src/main/kotlin');
    for (const path of (await files(kotlinRoot)).filter(path => path.endsWith('.kt') && !path.endsWith('/SiteShell.kt'))) {
        assert.doesNotMatch(await readFile(path, 'utf8'), /["'](?:app-header|editor-tabs mono|header-actions|workspace-label)["']/, `Shell markup belongs in SiteShell.kt: ${path}`);
    }
});
