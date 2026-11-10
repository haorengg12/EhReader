// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import { planCI, ensureDevelopmentTag, REPOSITORY } from './ci.mjs';
import { parseAppVersion, versionPath, compareVersions, validateOfficialVersion } from './version-policy.mjs';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const before = 'a'.repeat(40);
const after = 'b'.repeat(40);
const source = (name, code = 102) => `versionName = "${name}"\nversionCode = ${code}\n`;
const version = (name, code = 102) => parseAppVersion(source(name, code));
const push = (ref = 'refs/heads/develop') => ({ repository: { full_name: REPOSITORY }, ref, before, after });
function plan(event, old = '0.1.2', current = '0.1.3', eventName = 'push', oldCode = 102, newCode = 103) {
    return planCI({ event, eventName, sha: after, repository: REPOSITORY,
        readVersion: sha => { assert([before, after].includes(sha)); return source(sha === before ? old : current, sha === before ? oldCode : newCode); },
        isAncestor: (base, head) => base === before && head === after,
    });
}

test('三/四段严格版本、整数比较和所有合法分流', () => {
    assert.equal(versionPath(version('0.1.2'), version('0.1.2')), 'save');
    assert.equal(versionPath(version('0.1.2'), version('0.1.3', 103)), 'development');
    assert.equal(versionPath(version('0.1.2'), version('0.2.0', 103)), 'official');
    assert.equal(versionPath(version('0.1.2'), version('1.0.0', 103)), 'official');
    assert.equal(versionPath(version('0.1.2'), version('0.1.2.12', 103)), 'testing');
    assert.equal(versionPath(version('0.1.2.12', 103), version('0.1.2.20', 104)), 'testing');
    assert.equal(compareVersions('0.1.10', '0.1.9'), 1);
    assert.equal(compareVersions('0.1.2', '0.1.2.0'), 0);
    for (const name of ['0.1.2.0', '0.1.2.01', '01.1.2', '0.1', '0.1.2-beta', '0.1.2.1.1', '9007199254740992.0.0']) assert.throws(() => version(name));
});

test('降版、混合四段、未清零、仅 versionCode 变化及非递增安装编号失败', () => {
    for (const [old, current] of [['0.1.2', '0.1.1'], ['0.1.2', '0.2.3'], ['0.1.2', '1.1.0'], ['0.1.2', '0.1.3.1'], ['0.1.2.12', '0.1.2.1']]) {
        assert.throws(() => versionPath(version(old), version(current, 103)));
    }
    assert.throws(() => versionPath(version('0.1.2'), version('0.1.2', 103)), /only versionCode/);
    assert.throws(() => versionPath(version('0.1.2'), version('0.1.3')), /versionCode must increase/);
    assert.throws(() => versionPath(version('0.1.2'), version('0.1.2.1', 101)), /versionCode must increase/);
});

test('用户指定 0.1.2 → 1.8.15 对齐走正式发行，例外不扩展到其他来源或目标', () => {
    assert.equal(versionPath(version('0.1.2', 102), version('1.8.15', 10815)), 'official');
    validateOfficialVersion(version('1.8.15', 10815), [version('0.1.2', 102)]);
    const route = plan(push(), '0.1.2', '1.8.15', 'push', 102, 10815);
    assert.equal(route.path, 'official');
    assert.equal(route.debug, false);
    for (const [from, fromCode, to, toCode] of [['0.1.1', 101, '1.8.15', 10815], ['0.1.2', 103, '1.8.15', 10815], ['0.1.2', 102, '1.8.16', 10816], ['1.8.15', 10815, '2.9.10', 20910]]) {
        assert.throws(() => versionPath(version(from, fromCode), version(to, toCode)), /reset/);
        assert.throws(() => validateOfficialVersion(version(to, toCode), [version(from, fromCode)]), /reset/);
    }
    assert.throws(() => versionPath(version('0.1.2', 102), version('1.8.15', 102)), /versionCode must increase/);
});

test('develop 不升版和第一/二位升版仅基础验证；第三位和第四位变化打 Debug', () => {
    assert.equal(plan(push()).debug, true);
    assert.equal(plan(push()).base, before);
    assert.equal(plan(push(), '0.1.2', '0.1.2', 'push', 102, 102).debug, false);
    for (const current of ['0.2.0', '1.0.0']) {
        const result = plan(push(), '0.1.2', current);
        assert.equal(result.path, 'official');
        assert.equal(result.debug, false);
    }
    assert.equal(plan(push(), '0.1.2', '0.1.2.1').debug, true);
});

test('brody 保存运行基础验证但不打包，分支版本和比较基准必须匹配', () => {
    const result = plan(push('refs/heads/brody-0.1.2.12'), '0.1.2', '0.1.2.12', 'push', 102, 103);
    assert.equal(result.path, 'snapshot');
    assert.equal(result.debug, false);
    assert.throws(() => plan(push('refs/heads/brody-0.1.2.2'), '0.1.2', '0.1.2.12'), /mismatch/);
    assert.throws(() => plan(push('refs/heads/brody-0.1.3.1'), '0.1.2', '0.1.3.1'), /Testing version/);
});

test('所有 PR 只读基础验证，按 PR head 而非合并引用检查', () => {
    for (const base of ['develop', 'master']) {
        const event = { repository: { full_name: REPOSITORY }, action: 'synchronize', pull_request: {
            head: { sha: after, ref: 'feature/test', repo: { full_name: 'fork/EhReader' } },
            base: { ref: base, repo: { full_name: REPOSITORY } },
        } };
        const result = plan(event, '0.1.2', '0.1.3', 'pull_request');
        assert.equal(result.debug, false);
        assert.equal(result.base, '');
        event.pull_request.head.sha = before;
        assert.throws(() => plan(event, '0.1.2', '0.1.3', 'pull_request'), /head SHA/);
    }
});

test('手动修复只允许 develop，必须明确原始基准；普通手动不触发构建', () => {
    const event = { ...push(), inputs: { repair_debug: 'true', base_sha: before } };
    assert.equal(plan(event, '0.1.2', '0.1.3', 'workflow_dispatch').debug, true);
    assert.equal(plan({ ...push(), inputs: {} }, '0.1.2', '0.1.3', 'workflow_dispatch').debug, false);
    assert.throws(() => plan({ ...event, inputs: { repair_debug: 'true' } }, '0.1.2', '0.1.3', 'workflow_dispatch'), /fixed non-zero/);
    assert.throws(() => plan(event, '0.1.3', '0.1.3', 'workflow_dispatch', 103, 103), /original pre-version-change/);
    assert.throws(() => plan({ ...event, ref: 'refs/heads/brody-0.1.2.12' }, '0.1.2', '0.1.2.12', 'workflow_dispatch'), /restricted/);
    assert.throws(() => plan({ ...event, ref: 'refs/heads/master' }, '0.1.2', '0.1.3', 'workflow_dispatch'), /Only develop/);
});

test('固定事件/源码/祖先基准门禁拒绝误触发、分支首次创建和删除', () => {
    assert.throws(() => plan({ ...push(), before: '0'.repeat(40) }), /fixed non-zero/);
    assert.throws(() => plan({ ...push(), before: after }), /ancestor/);
    assert.throws(() => plan({ ...push(), after: before }));
    assert.throws(() => plan({ ...push(), deleted: true }), /Deleted/);
    assert.throws(() => plan({ ...push(), repository: { full_name: 'other/EhReader' } }));
    assert.throws(() => plan(push('refs/tags/v0.1.3')), /Only develop/);
    assert.throws(() => plan(push(), '0.1.2', '0.1.3', 'release'), /Unsupported/);
});

test('开发 Tag 同 SHA 幂等、不同 SHA 失败；并发只能读取重核，不能覆盖', async () => {
    const route = plan(push());
    const calls = [];
    assert.equal(await ensureDevelopmentTag({ plan: route, findTag: () => undefined, createTag: (tag, sha) => calls.push([tag, sha]) }), 'created');
    assert.deepEqual(calls, [['v0.1.3', after]]);
    assert.equal(await ensureDevelopmentTag({ plan: route, findTag: () => after, createTag: () => assert.fail('must not create') }), 'existing');
    await assert.rejects(ensureDevelopmentTag({ plan: route, findTag: () => before, createTag: () => assert.fail('must not overwrite') }), /another SHA/);
    let reads = 0;
    assert.equal(await ensureDevelopmentTag({ plan: route, findTag: () => ++reads === 1 ? undefined : after, createTag: () => { throw new Error('race'); } }), 'existing');
    await assert.rejects(ensureDevelopmentTag({ plan: route, findTag: () => undefined, createTag: () => { throw new Error('network'); } }), /no tag was overwritten/);
    await assert.rejects(ensureDevelopmentTag({ plan: { ...route, debug: false }, findTag: () => undefined, createTag: () => assert.fail('no quality-only tag') }));
});

test('正式版本按最高公开正式版本数值比较，开发 Tag 不属于 Release 历史', () => {
    const history = [version('0.1.2'), version('0.1.9'), { ...version('0.2.0'), formal: false }];
    validateOfficialVersion(version('0.2.0', 110), history);
    validateOfficialVersion(version('1.0.0', 110), history);
    assert.throws(() => validateOfficialVersion(version('0.1.10', 110), history), /major or minor/);
    assert.throws(() => validateOfficialVersion(version('0.2.1', 110), history), /reset/);
    assert.throws(() => validateOfficialVersion(version('0.1.9.1', 110), history), /three components/);
});

test('真实 CLI 从固定 Git before/after 识别四段测试版，不查询远端 Tag 或写入仓库', t => {
    const base = path.resolve(process.env.EHREADER_TEST_TEMP || tmpdir());
    const repo = mkdtempSync(path.join(base, 'ci-plan-'));
    t.after(() => {
        const rel = path.relative(base, repo);
        assert.ok(rel.startsWith('ci-plan-') && !rel.includes(path.sep));
        rmSync(repo, { recursive: true, force: true, maxRetries: 6, retryDelay: 100 });
    });
    const git = (...args) => execFileSync('git', args, { cwd: repo, encoding: 'utf8' }).trim();
    git('init', '-q');
    mkdirSync(path.join(repo, 'app'));
    writeFileSync(path.join(repo, 'app/build.gradle.kts'), source('0.1.2'));
    git('add', '.');
    git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'base');
    const baseSha = git('rev-parse', 'HEAD');
    writeFileSync(path.join(repo, 'app/build.gradle.kts'), source('0.1.2.1', 103));
    git('add', '.');
    git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'candidate');
    const headSha = git('rev-parse', 'HEAD');
    const eventFile = path.join(repo, 'event.json');
    const output = path.join(repo, 'output.txt');
    writeFileSync(eventFile, JSON.stringify({ ...push(), before: baseSha, after: headSha }));
    execFileSync(process.execPath, [fileURLToPath(new URL('./ci.mjs', import.meta.url)), 'plan'], { cwd: repo, encoding: 'utf8', env: {
        ...process.env, GITHUB_EVENT_PATH: eventFile, GITHUB_EVENT_NAME: 'push', GITHUB_REPOSITORY: REPOSITORY,
        GITHUB_REF: 'refs/heads/develop', GITHUB_SHA: headSha, GITHUB_OUTPUT: output, GITHUB_STEP_SUMMARY: path.join(repo, 'summary.md'),
    } });
    assert.match(readFileSync(output, 'utf8'), /path=testing\ndebug=true/);
    assert.match(readFileSync(output, 'utf8'), new RegExp(`base=${baseSha}`));
    assert.equal(git('tag', '--list'), '');
    assert.equal(git('rev-parse', 'HEAD'), headSha);
});

test('四段测试版在 develop 递增安装编号、可修复打包，不能创建开发 Tag 或正式发行', async () => {
    const first = plan(push(), '1.8.15', '1.8.15.1', 'push', 10815, 10816);
    assert.equal(first.path, 'testing');
    assert.equal(first.debug, true);
    const workflow = readFileSync(new URL('../.github/workflows/ci.yml', import.meta.url), 'utf8');
    assert.match(workflow.split('  development-tag:')[1], /needs\.quality\.outputs\.path == 'development'/);
    assert.equal(plan(push(), '1.8.15.1', '1.8.15.2', 'push', 10816, 10817).debug, true);
    assert.equal(plan(push(), '1.8.15.1', '1.8.15.1', 'push', 10816, 10816).debug, false);
    assert.throws(() => plan(push(), '1.8.15', '1.8.15.1', 'push', 10815, 10815), /versionCode must increase/);
    const manual = { ...push(), inputs: { repair_debug: 'true', base_sha: before } };
    assert.equal(plan(manual, '1.8.15', '1.8.15.1', 'workflow_dispatch', 10815, 10816).debug, true);
    await assert.rejects(ensureDevelopmentTag({ plan: first,
        findTag: () => assert.fail('Testing must not read tag refs'),
        createTag: () => assert.fail('Testing must not create a tag'),
    }));
    assert.throws(() => validateOfficialVersion(version('1.8.15.1', 10816), []), /three components/);
    assert.equal(plan(push(), '1.8.15.1', '1.8.16', 'push', 10816, 10817).path, 'development');
});
