import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { command, runTask, readTask, selectSteps } from './local-task.mjs';
import { fingerprint } from './check-results.mjs';
import { recordDraft, writeRecord } from './task-records.mjs';
import { failureKind, retryable } from './task-recovery.mjs';

function fixture(t) {
    const base = path.resolve(process.env.EHREADER_TEST_TEMP || tmpdir());
    const workspace = mkdtempSync(path.join(base, 'local-task-'));
    t.after(() => {
        const rel = path.relative(base, workspace);
        assert.ok(rel.startsWith('local-task-') && !rel.includes(path.sep));
        rmSync(workspace, { recursive: true, force: true, maxRetries: 6, retryDelay: 100 });
    });
    const repo = path.join(workspace, '含空格 EhReader');
    mkdirSync(repo);
    const write = (name, contents) => {
        const file = path.join(repo, name);
        mkdirSync(path.dirname(file), { recursive: true });
        writeFileSync(file, contents);
        return file;
    };
    write('AGENTS.md', '# 入口\n');
    write('README.md', '# 项目\n');
    write('.agents/docs/rules.md', '# 规范\n');
    write('scripts/sample.test.mjs', 'export {};\n');
    write('app/build.gradle.kts', 'versionName = "0.1.2"\nversionCode = 102\n');
    for (const args of [['init', '-q'], ['add', '.'], ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture']]) {
        assert.equal(command('git', args, repo).code, 0);
    }
    return { repo, workspace, write };
}

const good = () => ({ code: 0, stdout: '', stderr: '' });

test('只调度明确范围：文档不打包，生成状态、日志和本地审核单，不写 Git', async t => {
    const { repo, write, workspace } = fixture(t);
    const file = write('notes.md', '# 本次\n');
    const before = command('git', ['status', '--porcelain'], repo).stdout;
    const calls = [];
    const state = await runTask({ repo, task: 'docs', files: [file], executor: (program, args) => { calls.push([program, args]); return good(); } });
    assert.equal(state.status, 'awaiting-review');
    assert.deepEqual(state.results.map(item => item.step), ['text', 'docs', 'diff']);
    assert.ok(calls.every(([program, args]) => program === 'git' && args[0] === 'diff'));
    assert.equal(command('git', ['status', '--porcelain'], repo).stdout, before);
    assert.equal(readTask(repo, 'docs').version, '0.1.2 / 102');
    assert.ok(existsSync(path.join(workspace, 'temp/docs/review.md')));
    assert.ok(state.results.every(item => existsSync(item.log)));
});

test('真实失败保留退出码并停止后续阶段；同一任务可恢复，范围不能静默扩大', async t => {
    const { repo, write } = fixture(t);
    const file = write('scripts/change.mjs', 'export {};\n');
    const state = await runTask({ repo, task: 'failure', files: [file], executor: (program, args) => args[0] === '--test' ? { code: 7, stdout: '', stderr: 'failed test' } : good() });
    assert.equal(state.status, 'failed');
    assert.equal(state.results.at(-1).code, 7);
    assert.equal(state.results.at(-1).step, 'node-tests');
    assert.match(readFileSync(state.results.at(-1).log, 'utf8'), /failed test/);
    const resumed = await runTask({ repo, task: 'failure', executor: good });
    assert.equal(resumed.status, 'awaiting-review');
    assert.equal(resumed.history.length, 1);
    await assert.rejects(runTask({ repo, task: 'failure', files: [file, 'README.md'], executor: good }), /范围发生变化/);
});

test('错误路径、任务名、缺失文件与并发任务锁均拒绝，不猜测范围', async t => {
    const { repo, workspace } = fixture(t);
    await assert.rejects(runTask({ repo, task: '../bad', files: ['README.md'] }), /任务名/);
    await assert.rejects(runTask({ repo, task: 'empty' }), /明确本次文件/);
    await assert.rejects(runTask({ repo, task: 'outside', files: ['../../outside.md'] }), /范围仅限/);
    await assert.rejects(runTask({ repo, task: 'missing', files: ['typo.md'] }), /不存在/);
    mkdirSync(path.join(workspace, 'temp/locked'), { recursive: true });
    writeFileSync(path.join(workspace, 'temp/locked/.lock'), '');
    await assert.rejects(runTask({ repo, task: 'locked', files: ['README.md'] }), /EEXIST/);
});

test('文档失败和未实现的静态检查如实报告；混合 Android 范围不掩盖 YAML 缺项', async t => {
    const { repo, write } = fixture(t);
    write('broken.md', '[x](absent.md)\n');
    const failed = await runTask({ repo, task: 'broken', files: ['broken.md'], executor: good });
    assert.equal(failed.status, 'failed');
    assert.equal(failed.results.at(-1).step, 'docs');
    write('.github/workflows/test.yml', 'name: Example\n');
    const manual = await runTask({ repo, task: 'workflow', files: ['.github/workflows/test.yml'], executor: good });
    assert.equal(manual.status, 'needs-manual-validation');
    assert.match(manual.gaps[0], /静态检查/);
    assert.equal(selectSteps([path.join(repo, 'app/X.kt'), path.join(repo, '.github/workflows/test.yml')], repo).gaps.length, 1);
});

function fakeDocker(write, { mutate = false } = {}) {
    return (program, args) => {
        if (program === 'docker') return { ...good(), stdout: args[0] === 'image' ? `sha256:${'a'.repeat(64)}\n` : '' };
        if (args.includes('-File')) {
            assert.equal(args[args.indexOf('-Mode') + 1], 'Debug');
            assert.ok(!args.includes('-SigningDirectory'));
            const directory = path.join(args[args.indexOf('-OutputRoot') + 1], 'current-build');
            mkdirSync(directory);
            const apk = Buffer.from('mock-only-not-an-actual-apk');
            writeFileSync(path.join(directory, 'EhReader-local-arm64-v8a.apk'), apk);
            writeFileSync(path.join(directory, 'SHA256SUMS.txt'), `${createHash('sha256').update(apk).digest('hex')}  EhReader-local-arm64-v8a.apk\n`);
            for (const file of ['VALIDATION.txt', 'source-sha.txt', 'source-status.txt']) writeFileSync(path.join(directory, file), 'mock evidence\n');
            if (mutate) write('app/X.kt', '// changed while building\n');
        }
        return good();
    };
}

test('隔离模拟 Docker：固定 Debug 参数、核验散列、日志留 temp、交付留 release', async t => {
    const { repo, write, workspace } = fixture(t);
    write('app/X.kt', '// source\n');
    write('../build-tools/ehreader-docker/build.ps1', '# mock only\n');
    const state = await runTask({ repo, task: 'docker', files: ['app/X.kt'], executor: fakeDocker(write) });
    assert.equal(state.status, 'needs-manual-validation');
    assert.ok(state.delivery.startsWith(path.join(workspace, 'release')));
    assert.ok(existsSync(path.join(state.delivery, 'SOURCE.json')));
    assert.ok(!existsSync(path.join(state.delivery, 'docker.log')));
    assert.match(readFileSync(path.join(state.delivery, 'REVIEW.md'), 'utf8'), /needs-manual-validation/);
    assert.match(state.gaps[0], /设备测试未运行/);
});

test('构建期间源码变化或构建入口缺失时不交付，不自动还原源码', async t => {
    const { repo, write, workspace } = fixture(t);
    write('app/X.kt', '// source\n');
    const missing = await runTask({ repo, task: 'no-builder', files: ['app/X.kt'], executor: good });
    assert.equal(missing.status, 'failed');
    assert.match(missing.results.at(-1).error, /入口缺失/);
    write('../build-tools/ehreader-docker/build.ps1', '# mock only\n');
    const changed = await runTask({ repo, task: 'changed', files: ['app/X.kt'], executor: fakeDocker(write, { mutate: true }) });
    assert.equal(changed.status, 'failed');
    assert.match(changed.results.at(-1).error, /宿主源码发生变化/);
    assert.equal(existsSync(path.join(workspace, 'release')), false);
    assert.match(readFileSync(path.join(repo, 'app/X.kt'), 'utf8'), /changed/);
});

test('相同输入复用成功结果；文档改动仅重跑文本、文档和差异检查', async t => {
    const { repo, write } = fixture(t);
    write('scripts/change.mjs', 'export {};\n');
    write('notes.md', '# 第一版\n');
    const files = ['scripts/change.mjs', 'notes.md'];
    const first = await runTask({ repo, task: 'reuse', files, executor: good });
    assert.equal(first.status, 'awaiting-review');
    const unchanged = await runTask({ repo, task: 'reuse', executor: () => { throw new Error('相同输入不应执行命令'); } });
    assert.ok(unchanged.results.every(result => result.status === 'reused'));
    write('notes.md', '# 第二版\n');
    assert.equal(readTask(repo, 'reuse').status, 'stale');
    const changed = await runTask({ repo, task: 'reuse', executor: good });
    assert.equal(changed.results.find(result => result.step === 'docs').status, 'passed');
    assert.equal(changed.results.find(result => result.step === 'node-tests').status, 'reused');
    assert.equal(changed.results.find(result => result.step === 'node-syntax').status, 'reused');
});

test('依赖文件、模板和 Git 配置变化分别使受影响的结果失效', async t => {
    const { repo, write } = fixture(t);
    write('scripts/change.mjs', 'export {};\n');
    await runTask({ repo, task: 'dependencies', files: ['scripts/change.mjs'], executor: good });
    write('scripts/lib/dependency.mjs', 'export const value = 2;\n');
    const changed = await runTask({ repo, task: 'dependencies', executor: good });
    assert.equal(changed.results.find(item => item.step === 'node-tests').status, 'passed');
    assert.equal(changed.results.find(item => item.step === 'node-syntax').status, 'reused');
    write('.github/RELEASE_TEMPLATE.md', '# New template\n');
    const template = await runTask({ repo, task: 'dependencies', executor: good });
    assert.equal(template.results.find(item => item.step === 'node-tests').status, 'passed');
    write('.editorconfig', 'root = true\n');
    const config = await runTask({ repo, task: 'dependencies', executor: good });
    assert.equal(config.results.find(item => item.step === 'diff').status, 'passed');
    assert.equal(config.results.find(item => item.step === 'node-tests').status, 'reused');
});

test('关联锚点内容变化使文档检查失效；不能沿用旧通过结论', async t => {
    const { repo, write } = fixture(t);
    write('linked.md', '# 旧标题\n');
    write('notes.md', '[查看](linked.md#旧标题)\n');
    await runTask({ repo, task: 'anchor', files: ['notes.md'], executor: good });
    write('linked.md', '# 新标题\n');
    const changed = await runTask({ repo, task: 'anchor', executor: good });
    assert.equal(changed.status, 'failed');
    assert.equal(changed.results.at(-1).step, 'docs');
    assert.equal(changed.results[0].status, 'reused');
});

test('原始日志被修改或丢失会重跑；失败结果不会复用', async t => {
    const { repo } = fixture(t);
    const first = await runTask({ repo, task: 'evidence', files: ['README.md'], executor: good });
    writeFileSync(first.results[0].log, 'tampered evidence\n');
    const changed = await runTask({ repo, task: 'evidence', executor: good });
    assert.equal(changed.results[0].status, 'passed');
    assert.match(changed.results[0].rerunReason, /日志证据已变化/);
    rmSync(changed.results.find(result => result.step === 'docs').log);
    const missing = await runTask({ repo, task: 'evidence', executor: good });
    assert.equal(missing.results.find(result => result.step === 'docs').status, 'passed');
    assert.match(missing.results.find(result => result.step === 'docs').rerunReason, /证据缺失/);
});

test('检查过程中输入改变导致结果失败失效，保存真实证据', async t => {
    const { repo, write } = fixture(t);
    write('scripts/change.mjs', 'export {};\n');
    const state = await runTask({ repo, task: 'during-check', files: ['scripts/change.mjs'], executor: (program, args) => {
        if (args[0] === '--check') write('scripts/change.mjs', 'export const changed = true;\n');
        return good();
    } });
    assert.equal(state.status, 'failed');
    assert.match(state.results.at(-1).error, /输入发生变化/);
});

test('工具版本/环境参与标识，环境只留散列；Docker 不自动复用', async t => {
    const { repo, write } = fixture(t);
    const files = [path.join(repo, 'README.md')];
    const old = fingerprint('text', { repo, files, node: 'v22.0.0', env: { TOKEN: 'secret-value', TEMP: 'old' } });
    assert.notEqual(old.key, fingerprint('text', { repo, files, node: 'v24.0.0', env: { TOKEN: 'secret-value' } }).key);
    assert.notEqual(old.key, fingerprint('text', { repo, files, node: 'v22.0.0', env: { TOKEN: 'changed' } }).key);
    assert.equal(old.key, fingerprint('text', { repo, files, node: 'v22.0.0', env: { TOKEN: 'secret-value', TEMP: 'new' } }).key);
    assert.equal(old.key, fingerprint('text', { repo, files, node: 'v22.0.0', env: { TOKEN: 'secret-value', CODEX_TOOL_CALL_ID: 'new-call', LOCALAPPDATA: 'sandbox-temp-appdata' } }).key);
    assert.ok(!JSON.stringify(old).includes('secret-value'));
    assert.equal(fingerprint('node-tests', { repo, files, env: { NODE_OPTIONS: '--require /unknown/module' } }).reusable, false);
    write('app/X.kt', '// source\n');
    write('../build-tools/ehreader-docker/build.ps1', '# mock only\n');
    let builds = 0;
    const fake = fakeDocker(write);
    const executor = (program, args) => { if (args.includes('-File')) builds++; return fake(program, args); };
    await runTask({ repo, task: 'no-docker-reuse', files: ['app/X.kt'], executor });
    const resumed = await runTask({ repo, task: 'no-docker-reuse', executor });
    assert.equal(builds, 2);
    assert.equal(resumed.results.find(item => item.step === 'text').status, 'reused');
    assert.match(resumed.results.at(-1).rerunReason, /不自动复用/);
});

test('暂存区新增空白错误也会使差异检查失败，不能只查工作树', async t => {
    const { repo, write } = fixture(t);
    write('README.md', '# Project  \n');
    assert.equal(command('git', ['add', 'README.md'], repo).code, 0);
    const state = await runTask({ repo, task: 'staged', files: ['README.md'] });
    assert.equal(state.status, 'failed');
    assert.equal(state.results.at(-1).step, 'diff');
    assert.notEqual(state.results.at(-1).code, 0);
});

test('自动记录草稿只写真实结果，显式追加同一任务至多一次，不因追写再改仓库', async t => {
    const { repo, workspace, write } = fixture(t);
    write('.agents/docs/ACCEPTANCE.md', '# 历史\n');
    const state = await runTask({ repo, task: 'record', files: ['README.md'], summary: '新增记录流程', executor: good });
    const directory = path.join(workspace, 'temp/record');
    assert.ok(existsSync(path.join(directory, 'acceptance-draft.md')));
    const draft = recordDraft(state, new Date('2026-11-06T17:00:00Z'));
    assert.match(draft, /2026-11-07/);
    assert.match(draft, /未据此取得人工测试或推送许可/);
    assert.match(draft, /新增记录流程/);
    assert.equal(readFileSync(path.join(repo, '.agents/docs/ACCEPTANCE.md'), 'utf8'), '# 历史\n');
    assert.equal(writeRecord(state, directory, { append: true }).appended, true);
    const accepted = readFileSync(path.join(repo, '.agents/docs/ACCEPTANCE.md'), 'utf8');
    assert.match(accepted, /^# 历史/);
    assert.equal(command('git', ['add', '.agents/docs/ACCEPTANCE.md'], repo).code, 0);
    assert.equal(command('git', ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'record once'], repo).code, 0);
    const head = command('git', ['rev-parse', 'HEAD'], repo).stdout;
    assert.equal(writeRecord({ ...state, status: 'failed' }, directory, { append: true }).alreadyRecorded, true);
    assert.equal(readFileSync(path.join(repo, '.agents/docs/ACCEPTANCE.md'), 'utf8'), accepted);
    assert.equal(command('git', ['status', '--porcelain', '--', '.agents/docs/ACCEPTANCE.md'], repo).stdout, '');
    assert.equal(command('git', ['rev-parse', 'HEAD'], repo).stdout, head);
    assert.match(readFileSync(path.join(directory, 'acceptance-draft.md'), 'utf8'), /失败/);
});

test('可指定暂停点，恢复后复核并复用已完成项，不执行 Git 写入', async t => {
    const { repo, write } = fixture(t);
    write('scripts/change.mjs', 'export {};\n');
    const paused = await runTask({ repo, task: 'pause', files: ['scripts/change.mjs'], pauseBefore: 'node-tests', executor: good });
    assert.equal(paused.status, 'paused');
    assert.ok(paused.pending.includes('node-tests'));
    assert.ok(!paused.results.some(item => item.step === 'node-tests'));
    assert.match(recordDraft(paused), /已暂停/);
    const resumed = await runTask({ repo, task: 'pause', executor: good });
    assert.equal(resumed.status, 'awaiting-review');
    assert.equal(resumed.results.find(item => item.step === 'node-syntax').status, 'reused');
    assert.equal(resumed.results.find(item => item.step === 'node-tests').status, 'passed');
    assert.ok(resumed.history.some(item => item.status === 'paused'));
});

test('相同 SHA/输入的临时只读故障最多重试一次，并保留两次退出状态', async t => {
    const { repo, write } = fixture(t);
    write('scripts/change.mjs', 'export {};\n');
    let calls = 0;
    const recovered = await runTask({ repo, task: 'retry', files: ['scripts/change.mjs'], executor: (program, args) => {
        if (args[0] === '--check' && ++calls === 1) return { code: 1, stderr: 'ECONNRESET', stdout: '' };
        return good();
    } });
    assert.equal(recovered.status, 'awaiting-review');
    const syntax = recovered.results.find(item => item.step === 'node-syntax');
    assert.deepEqual(syntax.commands.map(item => item.code), [1, 0]);
    assert.match(readFileSync(syntax.log, 'utf8'), /重试 1\/1/);
    calls = 0;
    const failed = await runTask({ repo, task: 'retry-limit', files: ['scripts/change.mjs'], executor: () => { calls++; return { code: 1, stderr: 'ETIMEDOUT' }; } });
    assert.equal(failed.status, 'failed');
    assert.equal(calls, 2);
    calls = 0;
    await runTask({ repo, task: 'retry-limit', executor: () => { calls++; return { code: 1, stderr: 'ETIMEDOUT' }; } });
    assert.equal(calls, 1, '继续同一输入不能刷新已经用尽的自动重试预算');
});

test('输入变化、取消、签名/标签/权限问题不能自动重试，构建也不盲重试', async t => {
    const { repo, write } = fixture(t);
    write('scripts/change.mjs', 'export {};\n');
    let calls = 0;
    const changed = await runTask({ repo, task: 'retry-input', files: ['scripts/change.mjs'], executor: () => {
        calls++; write('scripts/change.mjs', 'export const changed = true;\n'); return { code: 1, stderr: 'ETIMEDOUT' };
    } });
    assert.equal(calls, 1);
    assert.equal(changed.results.at(-1).failureKind, 'input-changed');
    assert.equal(failureKind({ code: 130 }), 'cancelled');
    assert.equal(failureKind({ code: 1, stderr: 'Signing keystore missing' }), 'sensitive');
    assert.equal(failureKind({ code: 1, stderr: 'tag conflict' }), 'sensitive');
    assert.equal(retryable('powershell.exe', ['-File', 'build.ps1'], 'transient'), false);
    assert.equal(retryable('git', ['push'], 'transient'), false);
    assert.equal(retryable(process.execPath, ['--test'], 'sensitive'), false);
    const cancelled = await runTask({ repo, task: 'cancelled', files: ['scripts/change.mjs'], executor: () => ({ code: 130, signal: 'SIGINT' }) });
    assert.equal(cancelled.results.at(-1).status, 'cancelled');
});

test('后续检查期间较早检查的输入改变，最终状态必须失效而非报告全部就绪', async t => {
    const { repo, write } = fixture(t);
    write('notes.md', '# Before\n');
    write('scripts/change.mjs', 'export {};\n');
    const state = await runTask({ repo, task: 'late-change', files: ['notes.md', 'scripts/change.mjs'], executor: (program, args) => {
        if (args[0] === '--test') write('notes.md', '# Changed during later check\n');
        return good();
    } });
    assert.equal(state.status, 'stale');
    assert.equal(state.results.find(item => item.step === 'docs').currentValidity, 'stale');
    assert.match(recordDraft(state), /已有结果失效/);
});
