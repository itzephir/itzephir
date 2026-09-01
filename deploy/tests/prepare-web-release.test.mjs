import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { brotliDecompressSync, gunzipSync } from 'node:zlib';
import { prepareRelease } from '../prepare-web-release.mjs';

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'itzephir-release-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const html = '<head>\n<!-- WASM_PRELOADS_START -->\n<!-- WASM_PRELOADS_END -->\n'
    + '<link rel="stylesheet" href="styles.css">\n<link rel="preload" href="itzephir.js" as="script">'
    + '</head><script>script.src = "itzephir.js";</script>';
  await Promise.all(Object.entries({
    'index.html': html,
    'styles.css': '.shell { color: pink; } /* $& remains literal */',
    'itzephir.js': 'console.log("entry");\n//# sourceMappingURL=itzephir.js.map',
    'itzephir.js.map': '{}',
    'a.wasm': Buffer.from([0, 97, 115, 109, 1, 0, 0, 0]),
    'b.wasm': Buffer.from([0, 97, 115, 109, 1, 0, 0, 0]),
  }).map(([name, data]) => writeFile(join(root, name), data)));
  await mkdir(join(root, 'composeResources', 'font'), { recursive: true });
  await writeFile(join(root, 'composeResources', 'font', 'site.ttf'), Buffer.from('font fixture'));
  return root;
}

test('inlines styles, preloads each Wasm once and uses one content-hashed entry URL', async t => {
  const root = await fixture(t);
  const report = await prepareRelease(root);
  const html = await readFile(join(root, 'index.html'), 'utf8');
  assert.match(report.entryName, /^itzephir\.[a-f0-9]{20}\.js$/);
  assert.equal(html.match(/as="fetch"/g).length, 2);
  assert.equal(html.split(report.entryName).length - 1, 2);
  assert.match(html, /id="critical-css"/);
  assert.ok(html.includes('$& remains literal'));
  assert.ok(!html.includes('rel="stylesheet"'));
  assert.ok(!(await readdir(root)).includes('itzephir.js.map'));
  assert.ok(!(await readFile(join(root, report.entryName), 'utf8')).includes('sourceMappingURL'));
});

test('Brotli and gzip decode byte-for-byte to every source asset', async t => {
  const root = await fixture(t);
  const report = await prepareRelease(root);
  for (const asset of report.assets) {
    const original = await readFile(join(root, asset.file));
    assert.deepEqual(brotliDecompressSync(await readFile(join(root, `${asset.file}.br`))), original);
    assert.deepEqual(gunzipSync(await readFile(join(root, `${asset.file}.gz`))), original);
  }
  assert.ok(report.assets.some(asset => asset.file === join('composeResources', 'font', 'site.ttf')));
});

test('running preparation twice does not duplicate styles or preloads', async t => {
  const root = await fixture(t);
  const first = await prepareRelease(root);
  const firstHtml = await readFile(join(root, 'index.html'), 'utf8');
  const second = await prepareRelease(root);
  assert.equal(first.entryName, second.entryName);
  assert.equal(await readFile(join(root, 'index.html'), 'utf8'), firstHtml);
});

test('entry changes invalidate the URL and prune only obsolete generated entries', async t => {
  const root = await fixture(t);
  const first = await prepareRelease(root);
  await writeFile(join(root, 'itzephir.js'), 'console.log("new entry");');
  const second = await prepareRelease(root);
  assert.notEqual(first.entryName, second.entryName);
  assert.ok(!(await readdir(root)).includes(first.entryName));
  assert.ok((await readdir(root)).includes('itzephir.js'));
  assert.ok((await readFile(join(root, 'index.html'), 'utf8')).includes(second.entryName));
});

test('rejects incomplete HTML before changing the distribution', async t => {
  const root = await fixture(t);
  await writeFile(join(root, 'index.html'), '<html>missing markers</html>');
  await assert.rejects(prepareRelease(root), /Missing release preparation markers/);
  assert.equal(await readFile(join(root, 'index.html'), 'utf8'), '<html>missing markers</html>');
});

test('changed CSS is embedded on the next preparation without a stale cache URL', async t => {
  const root = await fixture(t);
  await prepareRelease(root);
  await writeFile(join(root, 'styles.css'), '.shell { color: blue; }');
  await prepareRelease(root);
  const html = await readFile(join(root, 'index.html'), 'utf8');
  assert.ok(html.includes('color: blue'));
  assert.ok(!html.includes('color: pink'));
  assert.equal(html.match(/id="critical-css"/g).length, 1);
});

test('rejects a missing Wasm bundle', async t => {
  const root = await fixture(t);
  await Promise.all(['a.wasm', 'b.wasm'].map(name => rm(join(root, name))));
  await assert.rejects(prepareRelease(root), /Expected flat, URL-safe Wasm files/);
});

test('rejects HTML without a loader reference even when the site name remains', async t => {
  const root = await fixture(t);
  const html = (await readFile(join(root, 'index.html'), 'utf8')).replaceAll('itzephir.js', 'missing.js')
    + '<title>itzephir</title>';
  await writeFile(join(root, 'index.html'), html);
  await assert.rejects(prepareRelease(root), /Missing release preparation markers/);
  assert.equal(await readFile(join(root, 'index.html'), 'utf8'), html);
});

test('rejects CSS that would close the inline style element', async t => {
  const root = await fixture(t);
  await writeFile(join(root, 'styles.css'), '/* </style> */');
  await assert.rejects(prepareRelease(root), /Cannot inline CSS/);
});
