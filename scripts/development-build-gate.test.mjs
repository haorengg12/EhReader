// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { latestDevelopmentRun, inspectDevelopmentRun, validateLivePR, waitForDevelopmentBuild } from './development-build-gate.mjs';
import { REPO } from './release.mjs';

const sha = 'a'.repeat(40);
const event = () => ({ number: 12, action: 'opened', repository: { full_name: REPO }, pull_request: {
    state: 'open', merged: false,
    head: { ref: 'develop', sha, repo: { full_name: REPO } },
    base: { ref: 'master', sha: 'b'.repeat(40), repo: { full_name: REPO } },
} });
const run = (extra = {}) => ({ id: 101, run_attempt: 2, repository: { full_name: REPO },
    head_repository: { full_name: REPO }, head_branch: 'develop', head_sha: sha,
    path: '.github/workflows/ci.yml', event: 'push', status: 'completed', conclusion: 'success', ...extra });
const jobs = (id = 101) => ['Basic quality', 'Development Debug'].map(name => ({ name, run_id: id,
    head_sha: sha, status: 'completed', conclusion: 'success' }));

test('existing required readiness job wires the build gate with read-only API permissions', () => {
    const text = readFileSync(new URL('../.github/workflows/release-readiness.yml', import.meta.url), 'utf8');
    for (const permission of ['contents', 'actions', 'pull-requests']) assert.match(text, new RegExp(`  ${permission}: read`));
    assert(!text.includes(': write'));
    assert.match(text, /name: Release readiness/);
    assert(text.indexOf('node scripts/development-build-gate.mjs') > text.indexOf('node scripts/release.mjs readiness'));
});

test('only exact repository, workflow, develop SHA and build events qualify; newest run wins', () => {
    const good = run();
    const bad = [{ head_sha: 'c'.repeat(40) }, { head_branch: 'develop-abcdef' },
        { event: 'pull_request' }, { path: '.github/workflows/other.yml' },
        { repository: { full_name: 'fork/EhReader' } }, { head_repository: { full_name: 'fork/EhReader' } }];
    assert.equal(latestDevelopmentRun([good, ...bad.map(value => run({ id: 200, ...value }))], sha), good);
    assert.equal(latestDevelopmentRun([good, run({ id: 102, event: 'workflow_dispatch', path: '.github/workflows/ci.yml@develop' })], sha).id, 102);
});

test('workflow success alone cannot hide skipped, missing, duplicate or mismatched jobs', () => {
    assert.equal(inspectDevelopmentRun(run(), jobs(), sha), 'success');
    assert.equal(inspectDevelopmentRun(run({ status: 'in_progress', conclusion: null }), [], sha), 'waiting');
    for (const conclusion of ['failure', 'cancelled', 'skipped', 'neutral', 'timed_out']) {
        assert.throws(() => inspectDevelopmentRun(run({ conclusion }), jobs(), sha));
        const changed = jobs(); changed[1].conclusion = conclusion;
        assert.throws(() => inspectDevelopmentRun(run(), changed, sha), /actually succeed/);
    }
    assert.throws(() => inspectDevelopmentRun(run(), jobs().slice(0, 1), sha), /Exactly one/);
    assert.throws(() => inspectDevelopmentRun(run(), [...jobs(), jobs()[1]], sha), /Exactly one/);
    for (const change of [{ run_id: 99 }, { head_sha: 'd'.repeat(40) }, { status: 'in_progress' }]) {
        assert.throws(() => inspectDevelopmentRun(run(), [jobs()[0], { ...jobs()[1], ...change }], sha));
    }
});

test('PR source/base changes and closed PRs invalidate the captured input', () => {
    validateLivePR(event().pull_request, event());
    for (const change of [pr => pr.head.sha = 'c'.repeat(40), pr => pr.head.ref = 'feature',
        pr => pr.base.sha = 'd'.repeat(40), pr => pr.base.ref = 'develop',
        pr => pr.state = 'closed', pr => pr.merged = true]) {
        const pr = event().pull_request; change(pr);
        assert.throws(() => validateLivePR(pr, event()));
    }
});

function fixture({ runs = [run()], jobList = jobs(), livePR = event().pull_request, transform } = {}) {
    let clock = 0;
    const calls = [];
    const options = { event: event(), now: () => clock, timeoutMs: 40, intervalMs: 10,
        sleep: async ms => { clock += ms; }, report: () => {},
        api: async (endpoint, paginated) => {
            calls.push([endpoint, paginated]);
            if (transform) {
                const value = transform(endpoint, calls);
                if (value !== undefined) return value;
            }
            if (endpoint === 'pulls/12') return livePR;
            if (endpoint.startsWith('actions/workflows/ci.yml/runs?')) return [{ workflow_runs: runs }];
            if (endpoint === 'actions/runs/101/attempts/2/jobs?per_page=100') return [{ jobs: jobList }];
            if (endpoint === 'actions/runs/101') return run();
            assert.fail(`Unexpected API request: ${endpoint}`);
        } };
    return { options, calls };
}

test('successful proof comes from exact run attempt and is rechecked before returning', async () => {
    const { options, calls } = fixture();
    assert.deepEqual(await waitForDevelopmentBuild(options), { sha, run: 101, attempt: 2,
        url: `https://github.com/${REPO}/actions/runs/101` });
    assert(calls.some(([url, paginated]) => url.includes('/attempts/2/jobs?') && paginated));
    assert.equal(calls.filter(([url]) => url === 'pulls/12').length, 2);
});

test('gate waits for queued build, then accepts completed evidence without rebuilding', async () => {
    let lists = 0;
    const { options } = fixture({ transform: endpoint => {
        if (endpoint.startsWith('actions/workflows/')) return [{ workflow_runs: [run(++lists === 1 ? { status: 'queued', conclusion: null } : {})] }];
    } });
    assert.equal((await waitForDevelopmentBuild(options)).sha, sha);
    assert(lists >= 3);
});

test('no run or an indefinitely running build times out and cannot allow merge', async () => {
    for (const runs of [[], [run({ status: 'in_progress', conclusion: null })]]) {
        await assert.rejects(waitForDevelopmentBuild(fixture({ runs }).options), /Timed out/);
    }
});

test('new failed run shadows old success; quality-only dispatch cannot substitute a build', async () => {
    const failed = run({ id: 102, conclusion: 'failure' });
    const { options, calls } = fixture({ runs: [run(), failed], transform: endpoint => {
        if (endpoint === 'actions/runs/102/attempts/2/jobs?per_page=100') return [{ jobs: jobs(102) }];
    } });
    await assert.rejects(waitForDevelopmentBuild(options), /ended with failure/);
    assert(!calls.some(([url]) => url.includes('/101/attempts/')));
    const skipped = jobs(); skipped[1].conclusion = 'skipped';
    await assert.rejects(waitForDevelopmentBuild(fixture({ runs: [run({ event: 'workflow_dispatch' })], jobList: skipped }).options), /actually succeed/);
});

test('rerun begun while reading evidence invalidates the previously successful attempt', async () => {
    let reads = 0;
    const { options } = fixture({ transform: endpoint => {
        if (endpoint === 'actions/runs/101') return run({ run_attempt: 3, status: 'in_progress', conclusion: null });
        if (endpoint.startsWith('actions/workflows/')) return [{ workflow_runs: [run(++reads === 1 ? {} : { run_attempt: 3, status: 'in_progress', conclusion: null })] }];
    } });
    await assert.rejects(waitForDevelopmentBuild(options), /Timed out/);
});
