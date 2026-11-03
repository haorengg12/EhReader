// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, copyFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export const REPO = 'haorengg12/EhReader';
export const CERT = '5496f263be3a31268e9677720c1bbb41a05c0c0705960e1e9ae1051b641c7135';
const run = (cmd, args, options = {}) => execFileSync(cmd, args, { encoding: 'utf8', ...options }).trim();
const git = (...args) => run('git', args);
const api = (path, method = 'GET', data) => JSON.parse(run('gh', ['api', `repos/${REPO}/${path}`, '--method', method,
    ...(data ? ['--input', '-'] : [])], data ? { input: JSON.stringify(data) } : {}));
const digest = (file) => createHash('sha256').update(readFileSync(file)).digest('hex');
const write = (file, text) => writeFileSync(file, `${text.trimEnd()}\n`, 'utf8');

/** 严格解析唯一版本来源；不猜测、不自动修改发行版本。 */
export function parseVersion(source) {
    const names = [...source.matchAll(/^\s*versionName\s*=\s*"([^"\n]+)"\s*$/gm)];
    const codes = [...source.matchAll(/^\s*versionCode\s*=\s*(\d+)\s*$/gm)];
    assert.equal(names.length, 1, 'Exactly one versionName is required');
    assert.equal(codes.length, 1, 'Exactly one versionCode is required');
    assert.match(names[0][1], /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/);
    const code = Number(codes[0][1]);
    assert(Number.isSafeInteger(code) && code > 0 && code <= 2100000000, 'Invalid versionCode');
    return { name: names[0][1], code, tag: `v${names[0][1]}` };
}

/** 只接受同仓库 develop 合入 master；固定事件中的合并结果。 */
export function validateEvent(event, eventName, sha) {
    const pr = event.pull_request;
    assert.equal(eventName, 'pull_request');
    assert.equal(event.action, 'closed');
    assert.equal(event.repository.full_name, REPO);
    assert.equal(pr?.merged, true);
    assert.equal(pr.head.repo.full_name, REPO);
    assert.equal(pr.base.repo.full_name, REPO);
    assert.equal(pr.head.ref, 'develop');
    assert.equal(pr.base.ref, 'master');
    assert.match(pr.merge_commit_sha, /^[a-f0-9]{40}$/);
    assert.equal(sha, pr.merge_commit_sha);
}

/** 相同版本仅允许相同提交恢复；新版本必须严格大于已发布序列。 */
export function validateHistory(current, history, sha) {
    for (const old of history) {
        if (old.tag === current.tag) {
            assert.equal(old.sha, sha, 'Tag already belongs to another commit');
        } else {
            assert(current.code > old.code, 'versionCode must exceed every published release');
            const a = current.name.split('.').map(Number);
            const b = old.name.split('.').map(Number);
            const first = a.findIndex((n, i) => n !== b[i]);
            assert(first >= 0 && a[first] > b[first], 'versionName must increase');
        }
    }
}

function releases() {
    return JSON.parse(run('gh', ['api', '--paginate', '--slurp', `repos/${REPO}/releases?per_page=100`])).flat();
}

/** 已公开版本只读核验，不能用重跑覆盖资产；旧附件缺失需要人工审查。 */
function verifyPublished(state) {
    const assets = state.existing.assets;
    const expected = ['arm64-v8a', 'armeabi-v7a', 'x86_64', 'universal'].map(abi => `EhReader-${state.name}-android-${abi}.apk`);
    expected.push(`EhReader-${state.name}-source.zip`, 'LICENSE', 'NOTICE', 'VALIDATION.txt', 'SHA256SUMS.txt');
    assert.deepEqual(assets.map(a => a.name).sort(), expected.sort(), 'Incomplete public release requires manual audit');
    const sumsAsset = assets.find(a => a.name === 'SHA256SUMS.txt');
    const sums = run('gh', ['api', `repos/${REPO}/releases/assets/${sumsAsset.id}`, '-H', 'Accept: application/octet-stream']);
    const entries = sums.split(/\r?\n/).map(line => {
        const match = line.match(/^([a-f0-9]{64})  (.+)$/);
        assert(match, 'Invalid public checksum manifest');
        return { hash: match[1], name: match[2] };
    });
    assert.deepEqual(entries.map(e => e.name).sort(), expected.filter(n => n !== 'SHA256SUMS.txt').sort());
    for (const entry of entries) {
        const asset = assets.find(a => a.name === entry.name);
        assert(asset.size > 0 && asset.state === 'uploaded');
        assert.equal(asset.digest, `sha256:${entry.hash}`);
    }
}

/** 发布前及公开前都复查版本和远端标签，阻止竞争或重跑覆盖。 */
function inspect() {
    const sha = git('rev-parse', 'HEAD');
    const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
    validateEvent(event, process.env.GITHUB_EVENT_NAME, sha);
    git('fetch', 'origin', 'master', '--tags');
    git('merge-base', '--is-ancestor', sha, 'origin/master');
    const version = parseVersion(readFileSync('app/build.gradle.kts', 'utf8'));
    const all = releases();
    const history = all.filter(r => !r.draft).map(r => {
        assert.match(r.tag_name, /^v\d+\.\d+\.\d+$/, 'Unexpected release tag; manual audit required');
        const oldSha = git('rev-parse', `${r.tag_name}^{commit}`);
        const old = parseVersion(git('show', `${oldSha}:app/build.gradle.kts`));
        assert.equal(old.tag, r.tag_name);
        return { ...old, sha: oldSha };
    });
    validateHistory(version, history, sha);
    const tagExists = git('tag', '--list', version.tag) === version.tag;
    if (tagExists) assert.equal(git('rev-parse', `${version.tag}^{commit}`), sha, 'Tag target conflict');
    const existing = all.find(r => r.tag_name === version.tag);
    if (existing) assert(tagExists, 'Existing release without expected tag');
    const previous = history.filter(r => r.tag !== version.tag).sort((a, b) => b.code - a.code)[0];
    return { ...version, sha, existing, tagExists, event, previousTag: previous?.tag };
}

function files(dir) {
    return readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? files(join(dir, e.name)) : [join(dir, e.name)]);
}

/** 在签名包通过身份、ABI、证书及对齐验证后，整理正式附件。 */
function bundle(state) {
    const out = resolve('release-assets');
    assert(!existsSync(out), 'Use a fresh workspace for release assets');
    mkdirSync(out);
    const metadata = JSON.parse(readFileSync('app/build/outputs/apk/release/output-metadata.json', 'utf8'));
    assert.equal(metadata.applicationId, 'com.haorengg12.ehreader');
    const expected = ['arm64-v8a', 'armeabi-v7a', 'x86_64', 'universal'];
    const found = [];
    const sdk = process.env.ANDROID_HOME;
    const tool = name => join(sdk, 'build-tools/36.0.0', name);
    const details = [];
    for (const item of metadata.elements) {
        const abi = item.filters.find(f => f.filterType === 'ABI')?.value ?? 'universal';
        assert(expected.includes(abi));
        assert(!found.includes(abi));
        found.push(abi);
        assert.equal(item.versionCode, state.code);
        assert.equal(item.versionName, state.name);
        assert.equal(item.outputFile, item.outputFile.split('/').pop());
        const apk = join('app/build/outputs/apk/release', item.outputFile);
        const certs = run(tool('apksigner'), ['verify', '--verbose', '--print-certs', apk]);
        assert(certs.toLowerCase().includes(`certificate sha-256 digest: ${CERT}`), 'Signing certificate mismatch');
        const badging = run(tool('aapt'), ['dump', 'badging', apk]);
        assert(badging.includes("package: name='com.haorengg12.ehreader'"));
        assert(badging.includes(`versionCode='${state.code}' versionName='${state.name}'`));
        const vcs = run('unzip', ['-p', apk, 'META-INF/version-control-info.textproto']);
        assert(vcs.includes(state.sha), 'APK source SHA mismatch');
        const natives = [...(badging.match(/^native-code: (.+)$/m)?.[1] ?? '').matchAll(/'([^']+)'/g)].map(m => m[1]).sort();
        assert.deepEqual(natives, (abi === 'universal' ? expected.filter(x => x !== 'universal') : [abi]).sort());
        run(tool('zipalign'), ['-c', '-P', '16', '-v', '4', apk]);
        copyFileSync(apk, join(out, `EhReader-${state.name}-android-${abi}.apk`));
        details.push(`${abi}: package/version/ABI/certificate/zipalign PASS`);
    }
    assert.deepEqual(found.sort(), expected.sort());
    // 原生依赖取本次构建实际使用的源码，包含 libarchive 已应用的补丁。
    const source = resolve('release-source');
    mkdirSync(source);
    run('git', ['archive', '--format=tar', '-o', join(source, 'main.tar'), state.sha]);
    run('tar', ['-xf', join(source, 'main.tar'), '-C', source]);
    run('rm', [join(source, 'main.tar')]);
    const deps = files('app/.cxx').filter(p => /[/\\]_deps[/\\](liblzma|libarchive)-src[/\\]CMakeLists.txt$/.test(p));
    for (const dep of ['liblzma', 'libarchive']) {
        const paths = deps.filter(p => p.includes(`${dep}-src`)).map(p => resolve(p, '..'));
        assert(paths.length > 0, `Missing ${dep} source`);
        const heads = paths.map(p => run('git', ['-C', p, 'rev-parse', 'HEAD']));
        assert.equal(new Set(heads).size, 1, 'Native source differs between ABIs');
        const dest = join(source, 'third-party', dep);
        mkdirSync(dest, { recursive: true });
        run('tar', ['--exclude=.git', '-cf', join(source, `${dep}.tar`), '-C', paths[0], '.']);
        run('tar', ['-xf', join(source, `${dep}.tar`), '-C', dest]);
        run('rm', [join(source, `${dep}.tar`)]);
        details.push(`${dep}: ${heads[0]} (build source, patches included)`);
    }
    const nettle = 'app/src/main/cpp/nettle/nettle';
    assert(existsSync(`${nettle}/aes128-encrypt.c`) && readdirSync(nettle).some(p => p.startsWith('COPYING')), 'Missing nettle source');
    mkdirSync(join(source, 'third-party/nettle'), { recursive: true });
    run('cp', ['-a', `${nettle}/.`, join(source, 'third-party/nettle')]);
    write(join(source, 'BUILD-SOURCE.txt'), `Commit: ${state.sha}\nBuild: JDK 21, SDK 36, NDK 29.0.14206865; ./gradlew assembleRelease lintRelease\nNative dependencies used by this build are in third-party (nettle 3.10.2).\nSee app/src/main/cpp/CMakeLists.txt for FetchContent configuration.\nTo use bundled native sources offline, pass FETCHCONTENT_SOURCE_DIR_LIBLZMA, FETCHCONTENT_SOURCE_DIR_LIBARCHIVE and FETCHCONTENT_SOURCE_DIR_NETTLE as CMake absolute paths.\n${details.join('\n')}`);
    run('zip', ['-qr', join(out, `EhReader-${state.name}-source.zip`), '.'], { cwd: source });
    for (const name of ['LICENSE', 'NOTICE']) copyFileSync(name, join(out, name));
    const lint = readFileSync('app/build/reports/lint-results-release.xml', 'utf8');
    assert(!/severity="(?:Error|Fatal)"/.test(lint));
    const warnings = [...lint.matchAll(/severity="Warning"/g)].length;
    const tests = existsSync('app/build/test-results') ? files('app/build/test-results').filter(p => p.endsWith('.xml')) : [];
    write(join(out, 'VALIDATION.txt'), `Commit: ${state.sha}\nVersion: ${state.name} (${state.code})\nRun: ${process.env.GITHUB_SERVER_URL}/${REPO}/actions/runs/${process.env.GITHUB_RUN_ID}\nSpotless, lintRelease, assembleRelease: PASS\nLint warnings: ${warnings}\nUnit test XML reports: ${tests.length}${tests.length ? ' (see CI reports)' : ' (NO-SOURCE; no test coverage claimed)'}\nDevice tests: NOT RUN\nCertificate SHA256: ${CERT}\n${details.join('\n')}`);
    write(join(out, 'SHA256SUMS.txt'), files(out).sort().map(p => `${digest(p)}  ${p.split(/[/\\]/).pop()}`).join('\n'));
}

/** 只替换模板占位符一次，PR 内容作为数据保留；模板拼写错误必须在创建 Tag 前失败。 */
export function releaseBody(state, template = readFileSync(new URL('../.github/RELEASE_TEMPLATE.md', import.meta.url), 'utf8')) {
    const repoUrl = 'https://github.com/' + REPO;
    const pr = state.event.pull_request;
    const title = (pr.title || '发行合并').replace(/[\\`*_{}[\]()<>#!|]/g, '\\$&').replace(/[\r\n]+/g, ' ');
    const summary = pr.body?.trim();
    const values = {
        repository: REPO, tag: state.tag, version: state.name, version_code: state.code,
        download_base: repoUrl + '/releases/download/' + state.tag, sha: state.sha,
        run_url: (process.env.GITHUB_SERVER_URL || 'https://github.com') + '/' + REPO + '/actions/runs/' + process.env.GITHUB_RUN_ID,
        changes: '本次合并：[' + title + ' (#' + state.event.number + ')](' + repoUrl + '/pull/' + state.event.number + ')。' +
            (summary ? '\n\n' + summary : '\n\n详细变更见该 PR 和提交历史。'),
        history: state.previousTag ? '[完整变更](' + repoUrl + '/compare/' + state.previousTag + '...' + state.tag + ')' :
            '[提交历史](' + repoUrl + '/commits/' + state.sha + ')',
    };
    return template.replace(/{{\s*([^{}]+?)\s*}}/g, (_, key) => {
        assert(Object.hasOwn(values, key), 'Unknown release template placeholder: ' + key);
        return String(values[key]);
    });
}

/** 仅恢复同提交草稿；校验远端每个附件后才公开，绝不覆盖已公开版本。 */
function publish() {
    const state = inspect();
    assert(!state.existing || state.existing.draft, 'Already published; rerun preflight instead');
    const body = releaseBody(state);
    if (!state.tagExists) api('git/refs', 'POST', { ref: `refs/tags/${state.tag}`, sha: state.sha });
    const release = state.existing ?? api('releases', 'POST', {
        tag_name: state.tag, target_commitish: state.sha, name: `EhReader ${state.tag}`, draft: true, body,
    });
    const assets = files('release-assets');
    run('gh', ['release', 'upload', state.tag, ...assets, '--repo', REPO, '--clobber']);
    const remote = api(`releases/${release.id}`);
    assert.equal(remote.assets.length, assets.length, 'Unexpected or missing release asset');
    for (const file of assets) {
        const asset = remote.assets.find(a => a.name === file.split(/[/\\]/).pop());
        assert(asset && asset.size === readFileSync(file).length);
        assert.equal(asset.digest, `sha256:${digest(file)}`, 'Remote checksum mismatch');
    }
    api(`releases/${release.id}`, 'PATCH', { draft: false, name: `EhReader ${state.tag}`, body, make_latest: 'true' });
    console.log(`Published https://github.com/${REPO}/releases/tag/${state.tag}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    const command = process.argv[2];
    if (command === 'preflight') {
        const state = inspect();
        releaseBody(state);
        if (state.existing && !state.existing.draft) {
            verifyPublished(state);
            console.log('This exact commit is already published; leaving immutable assets unchanged.');
        }
        writeFileSync(process.env.GITHUB_OUTPUT, `published=${Boolean(state.existing && !state.existing.draft)}\n`, { flag: 'a' });
    } else if (command === 'bundle') bundle(inspect());
    else if (command === 'publish') publish();
    else throw new Error('Expected preflight, bundle or publish');
}
