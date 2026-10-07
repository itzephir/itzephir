import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdtemp, mkdir, readFile, readlink, realpath, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const run = promisify(execFile);
const script = fileURLToPath(new URL('../activate-release.sh', import.meta.url));
const release = 'abcdef123456-1-1';

async function fixture(staticPrevious = false) {
    const root = await realpath(await mkdtemp(join(tmpdir(), 'itzephir-activation-')));
    const base = join(root, 'dev');
    const previous = join(base, 'releases', 'old');
    const candidate = join(base, 'releases', release);
    const bin = join(root, 'mock-bin');
    await mkdir(bin, { recursive: true });
    await mkdir(join(candidate, 'lib'), { recursive: true });
    await writeFile(join(candidate, 'lib', 'website.jar'), 'candidate');
    await mkdir(join(previous, 'lib'), { recursive: true });
    await writeFile(join(previous, staticPrevious ? 'index.html' : 'lib/website.jar'), 'previous');
    await symlink(previous, join(base, 'current'));
    await writeFile(join(bin, 'sudo'), '#!/bin/sh\nif [ "$1" = -n ]; then shift; fi\nprintf "%s\\n" "$*" >> "$ACTIVATION_LOG"\n', { mode: 0o755 });
    await writeFile(join(bin, 'curl'), '#!/bin/sh\ncase "$*" in\n  *healthz*) [ "$MOCK_HEALTH" = fail ] && exit 22; printf ok ;;\n  *) [ "$MOCK_HTTPS" = fail ] && exit 22; exit 0 ;;\nesac\n', { mode: 0o755 });
    await writeFile(join(bin, 'sleep'), '#!/bin/sh\nexit 0\n', { mode: 0o755 });
    const env = { ...process.env, PATH: `${bin}:${process.env.PATH}`, RELEASE_ROOT: root, ACTIVATION_LOG: join(root, 'operations') };
    return { root, base, previous, candidate, env };
}

test('healthy deployment activates the candidate and restarts only its environment', async () => {
    const f = await fixture();
    try {
        await run('bash', [script, 'dev', release], { env: f.env });
        assert.equal(await readlink(join(f.base, 'current')), f.candidate);
        assert.equal((await readFile(f.env.ACTIVATION_LOG, 'utf8')).trim(), '/usr/bin/systemctl restart itzephir@dev.service');
    } finally { await rm(f.root, { recursive: true, force: true }); }
});

test('failed application health restores and restarts the previous Ktor release', async () => {
    const f = await fixture();
    try {
        await assert.rejects(run('bash', [script, 'dev', release], { env: { ...f.env, MOCK_HEALTH: 'fail' } }));
        assert.equal(await readlink(join(f.base, 'current')), f.previous);
        assert.equal((await readFile(f.env.ACTIVATION_LOG, 'utf8')).trim().split('\n').length, 2);
    } finally { await rm(f.root, { recursive: true, force: true }); }
});

test('failed HTTPS check restores a legacy static release and stops Ktor', async () => {
    const f = await fixture(true);
    try {
        await assert.rejects(run('bash', [script, 'dev', release], { env: { ...f.env, MOCK_HTTPS: 'fail' } }));
        assert.equal(await readlink(join(f.base, 'current')), f.previous);
        assert.ok((await readFile(f.env.ACTIVATION_LOG, 'utf8')).includes('/usr/bin/systemctl stop itzephir@dev.service'));
    } finally { await rm(f.root, { recursive: true, force: true }); }
});

test('invalid environments and release paths are rejected before changing current', async () => {
    const f = await fixture();
    try {
        await assert.rejects(run('bash', [script, 'production', release], { env: f.env }));
        await assert.rejects(run('bash', [script, 'dev', '../other'], { env: f.env }));
        assert.equal(await readlink(join(f.base, 'current')), f.previous);
    } finally { await rm(f.root, { recursive: true, force: true }); }
});
