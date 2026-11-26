// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { basename, dirname, join, relative, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const files = dir => readdirSync(dir, { withFileTypes: true }).flatMap(entry =>
    entry.isDirectory() ? files(join(dir, entry.name)) : [join(dir, entry.name)]);

/** 核对每个 ABI 的实际 CMake 源码位置及内容，防止共享解压目录回归和发行源码串包。 */
export function inspectNettleSources(cxxRoot, buildType, expectedAbis) {
    // AGP 的 Release 变体通常使用 RelWithDebInfo，不能把 Android 变体名当作 CMake 目录名。
    const types = buildType === 'Release' ? ['Release', 'RelWithDebInfo', 'MinSizeRel'] : [buildType];
    const roots = types.map(type => join(cxxRoot, type)).filter(existsSync);
    assert(roots.length > 0, `Missing ${buildType} CMake configuration`);
    const caches = roots.flatMap(files).filter(p => basename(p) === 'CMakeCache.txt');
    const sources = [];
    for (const cache of caches) {
        const abi = basename(dirname(cache));
        if (!expectedAbis.includes(abi)) continue;
        const match = readFileSync(cache, 'utf8').match(/^EHREADER_NETTLE_SOURCE_DIR:INTERNAL=(.+)\r?$/m);
        if (!match) continue;
        const source = resolve(match[1].trim());
        assert.equal(source, resolve(dirname(cache), '_deps/nettle'), `Nettle source must be ABI-local: ${abi}`);
        const entries = files(source).sort();
        assert(entries.includes(join(source, 'aes128-encrypt.c')), `Missing Nettle source: ${abi}`);
        assert(entries.some(p => basename(p).startsWith('COPYING')), `Missing Nettle license: ${abi}`);
        const hash = createHash('sha256');
        for (const file of entries) {
            hash.update(relative(source, file).replaceAll('\\', '/'));
            hash.update('\0');
            hash.update(createHash('sha256').update(readFileSync(file)).digest());
        }
        sources.push({ abi, source, digest: hash.digest('hex') });
    }
    assert(sources.length > 0, 'Missing ABI-local Nettle CMake configuration');
    assert.deepEqual([...new Set(sources.map(s => s.abi))].sort(), [...expectedAbis].sort(), 'Missing Nettle ABI configuration');
    assert.equal(new Set(sources.map(s => s.source)).size, sources.length, 'ABIs share a Nettle extraction directory');
    assert.equal(new Set(sources.map(s => s.digest)).size, 1, 'Nettle source differs between ABIs');
    return sources;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    const buildType = process.argv[2] ?? 'Debug';
    assert(['Debug', 'Release'].includes(buildType), 'Expected Debug or Release native configuration');
    const abis = process.env.GITHUB_ACTIONS === 'true' ? ['arm64-v8a', 'armeabi-v7a', 'x86_64'] : ['arm64-v8a'];
    const sources = inspectNettleSources('app/.cxx', buildType, abis);
    console.log(`Verified ${buildType} ABI-local Nettle sources: ${sources.map(s => s.abi).join(', ')}`);
}
