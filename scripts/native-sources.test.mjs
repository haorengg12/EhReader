import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { inspectNettleSources } from './native-sources.mjs';

const abis = ['arm64-v8a', 'armeabi-v7a', 'x86_64'];
function fixture(t, buildType = 'Debug') {
    const root = mkdtempSync(join(tmpdir(), 'ehreader-nettle-'));
    t.after(() => {
        assert(root.startsWith(join(tmpdir(), 'ehreader-nettle-')), 'Cleanup must remain in the fixture directory');
        rmSync(root, { recursive: true, force: true });
    });
    const dirs = abis.map(abi => {
        const dir = join(root, buildType, 'build-id', abi);
        const source = join(dir, '_deps/nettle');
        mkdirSync(source, { recursive: true });
        writeFileSync(join(source, 'aes128-encrypt.c'), 'same upstream source\n');
        writeFileSync(join(source, 'COPYING.LESSERv3'), 'license\n');
        writeFileSync(join(dir, 'CMakeCache.txt'), `EHREADER_NETTLE_SOURCE_DIR:INTERNAL=${source}\n`);
        return { dir, source };
    });
    return { root, dirs };
}

test('collects isolated sources across every supported ABI for release packaging', t => {
    const { root, dirs } = fixture(t);
    const sources = inspectNettleSources(root, 'Debug', abis);
    assert.equal(sources.length, 3);
    assert.deepEqual(new Set(sources.map(s => s.source)), new Set(dirs.map(d => d.source)));
});
test('release packaging finds AGP RelWithDebInfo caches instead of assuming a Release directory', t => {
    const { root } = fixture(t, 'RelWithDebInfo');
    assert.equal(inspectNettleSources(root, 'Release', abis).length, 3);
    assert.throws(() => inspectNettleSources(root, 'Debug', abis), /Missing Debug CMake configuration/);
});
test('rejects a shared extraction directory even when all source files exist', t => {
    const { root, dirs } = fixture(t);
    writeFileSync(join(dirs[1].dir, 'CMakeCache.txt'), `EHREADER_NETTLE_SOURCE_DIR:INTERNAL=${dirs[0].source}\n`);
    assert.throws(() => inspectNettleSources(root, 'Debug', abis), /must be ABI-local/);
});
test('rejects inconsistent source trees, missing licenses and missing ABI configurations', t => {
    const { root, dirs } = fixture(t);
    writeFileSync(join(dirs[2].source, 'aes128-encrypt.c'), 'different source\n');
    assert.throws(() => inspectNettleSources(root, 'Debug', abis), /differs between ABIs/);
    rmSync(join(dirs[2].source, 'COPYING.LESSERv3'));
    assert.throws(() => inspectNettleSources(root, 'Debug', abis), /Missing Nettle license/);
    rmSync(join(dirs[2].dir, 'CMakeCache.txt'));
    assert.throws(() => inspectNettleSources(root, 'Debug', abis), /Missing Nettle ABI configuration/);
});
