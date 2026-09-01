import { createHash } from 'node:crypto';
import { readFile, readdir, unlink, writeFile } from 'node:fs/promises';
import { join, relative, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { promisify } from 'node:util';
import { brotliCompress, constants, gzip } from 'node:zlib';

const compressBrotli = promisify(brotliCompress);
const compressGzip = promisify(gzip);
const preloadBlock = /<!-- WASM_PRELOADS_START -->[\s\S]*?<!-- WASM_PRELOADS_END -->/;
const styleBlock = /<link rel="stylesheet" href="styles\.css(?:\?v=[a-f0-9]+)?">|<style id="critical-css">[\s\S]*?<\/style>/;
const entryReference = /itzephir(?:\.[a-f0-9]{20})?\.js/g;

async function compressibleAssets(root, directory = root) {
  const entries = await readdir(directory, { withFileTypes: true });
  const assets = [];
  for (const entry of entries) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) assets.push(...await compressibleAssets(root, path));
    else if (entry.isFile() && /\.(html|css|js|wasm|svg|ttf)$/.test(entry.name)) {
      assets.push(relative(root, path));
    }
  }
  return assets.sort();
}

export async function prepareRelease(directory) {
  const root = resolve(directory);
  const [originalHtml, css, originalEntry, files] = await Promise.all([
    readFile(join(root, 'index.html'), 'utf8'),
    readFile(join(root, 'styles.css'), 'utf8'),
    readFile(join(root, 'itzephir.js'), 'utf8'),
    readdir(root, { withFileTypes: true }),
  ]);
  const names = files.filter(file => file.isFile()).map(file => file.name);
  const wasm = names.filter(name => name.endsWith('.wasm')).sort();
  if (!wasm.length || wasm.some(name => !/^[\w.-]+\.wasm$/.test(name))) {
    throw new Error('Expected flat, URL-safe Wasm files in the production distribution.');
  }
  if (!preloadBlock.test(originalHtml) || !styleBlock.test(originalHtml) || !originalHtml.match(entryReference)) {
    throw new Error('Missing release preparation markers in index.html.');
  }
  if (/<\/style/i.test(css)) throw new Error('Cannot inline CSS containing a closing style tag.');
  // Source maps are not deployed. Hash the actual bytes served to the browser.
  const entry = originalEntry.replace(/^\/\/# sourceMappingURL=.*$/gm, '');
  const digest = createHash('sha256').update(entry).digest('hex').slice(0, 20);
  const entryName = `itzephir.${digest}.js`;
  const preloads = wasm.map(name =>
    `    <link rel="preload" href="/${name}" as="fetch" type="application/wasm" crossorigin>`);
  const html = originalHtml
    .replace(preloadBlock, ['<!-- WASM_PRELOADS_START -->', ...preloads, '    <!-- WASM_PRELOADS_END -->'].join('\n'))
    // Callbacks preserve literal dollar signs in CSS/JS when replacing HTML.
    .replace(styleBlock, () => `<style id="critical-css">\n${css}\n    </style>`)
    .replace(entryReference, () => entryName);

  await writeFile(join(root, entryName), entry);
  await writeFile(join(root, 'index.html'), html);
  // Remove only generated files in this distribution, never source resources.
  for (const name of names) {
    const obsoleteEntry = /^itzephir\.[a-f0-9]{20}\.js(?:\.br|\.gz)?$/.test(name)
      && ![entryName, `${entryName}.br`, `${entryName}.gz`].includes(name);
    if (name.endsWith('.map') || obsoleteEntry) await unlink(join(root, name));
  }

  const assets = await compressibleAssets(root);
  const report = [];
  for (const name of assets) {
    const content = await readFile(join(root, name));
    const [br, gz] = await Promise.all([
      compressBrotli(content, { params: { [constants.BROTLI_PARAM_QUALITY]: 11 } }),
      compressGzip(content, { level: 9 }),
    ]);
    await Promise.all([
      writeFile(join(root, `${name}.br`), br),
      writeFile(join(root, `${name}.gz`), gz),
    ]);
    report.push({ file: name, bytes: content.length, brotliBytes: br.length, gzipBytes: gz.length });
  }
  return { entryName, inlineCssBytes: Buffer.byteLength(css), assets: report };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const report = await prepareRelease(process.argv[2] ?? 'website/build/dist/wasmJs/productionExecutable');
    console.log(JSON.stringify(report, null, 2));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
