// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { appendFileSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { setTimeout } from 'node:timers/promises';
import { pathToFileURL } from 'node:url';
import { REPO, validateCandidateEvent } from './release.mjs';

const workflow = '.github/workflows/ci.yml';

/** 只接受指定仓库、develop、精确源码 SHA 的开发工作流；PR 和其他分支构建不作证据。 */
export function latestDevelopmentRun(runs, sha) {
    return runs.filter(run => run.repository?.full_name === REPO && run.head_repository?.full_name === REPO &&
        run.head_branch === 'develop' && run.head_sha === sha &&
        ['push', 'workflow_dispatch'].includes(run.event) && run.path?.split('@')[0] === workflow)
        .sort((a, b) => b.id - a.id)[0];
}

/** 整体工作流成功不代表实际打包：基础检查和 Debug 任务必须在同次尝试中真实成功。 */
export function inspectDevelopmentRun(run, jobs, sha) {
    assert.equal(latestDevelopmentRun([run], sha), run, 'Development run identity mismatch');
    if (run.status !== 'completed') return 'waiting';
    assert.equal(run.conclusion, 'success', `Development run ${run.id} ended with ${run.conclusion}`);
    for (const name of ['Basic quality', 'Development Debug']) {
        const found = jobs.filter(job => job.name === name);
        assert.equal(found.length, 1, `Exactly one ${name} job is required`);
        const job = found[0];
        assert.equal(job.run_id, run.id, 'Job belongs to another run');
        assert.equal(job.head_sha, sha, 'Job belongs to another source SHA');
        assert.equal(job.status, 'completed', `${name} did not finish`);
        assert.equal(job.conclusion, 'success', `${name} must actually succeed; skipped builds are forbidden`);
    }
    return 'success';
}

/** 等待期间输入改变或 PR 已关闭即停止，不把旧提交证据用于当前 PR。 */
export function validateLivePR(pr, event) {
    assert.equal(pr.state, 'open', 'PR is no longer open');
    assert.equal(pr.merged, false, 'PR was already merged');
    validateCandidateEvent({ ...event, pull_request: pr }, 'pull_request', event.pull_request.head.sha);
    assert.equal(pr.base.sha, event.pull_request.base.sha, 'PR base changed; refresh the branch and checks');
}

/** 只读轮询；最新运行或最新尝试失败不会回退复用旧成功，超时仍阻止合并。 */
export async function waitForDevelopmentBuild({ event, api, sleep = setTimeout, now = Date.now,
    timeoutMs = 30 * 60 * 1000, intervalMs = 15000, report = console.log }) {
    const sha = event.pull_request.head.sha;
    validateCandidateEvent(event, 'pull_request', sha);
    assert(Number.isSafeInteger(event.number) && event.number > 0, 'PR number is required');
    const deadline = now() + timeoutMs;
    while (now() < deadline) {
        validateLivePR(await api(`pulls/${event.number}`), event);
        const pages = await api(`actions/workflows/ci.yml/runs?branch=develop&head_sha=${sha}&per_page=100`, true);
        const run = latestDevelopmentRun(pages.flatMap(page => page.workflow_runs), sha);
        if (run) {
            assert(Number.isSafeInteger(run.id) && run.id > 0 && Number.isSafeInteger(run.run_attempt) && run.run_attempt > 0);
            const jobs = run.status === 'completed' ? (await api(`actions/runs/${run.id}/attempts/${run.run_attempt}/jobs?per_page=100`, true)).flatMap(page => page.jobs) : [];
            const status = inspectDevelopmentRun(run, jobs, sha);
            report(`Development build ${run.id} attempt ${run.run_attempt}: ${status}`);
            if (status === 'success') {
                // 证据读取过程中重跑或新运行开始时重新等待，禁止沿用先前状态。
                const current = await api(`actions/runs/${run.id}`);
                if (current.run_attempt !== run.run_attempt || current.status !== 'completed' || current.conclusion !== 'success') {
                    await sleep(Math.min(intervalMs, Math.max(0, deadline - now())));
                    continue;
                }
                const refreshed = latestDevelopmentRun((await api(`actions/workflows/ci.yml/runs?branch=develop&head_sha=${sha}&per_page=100`, true)).flatMap(page => page.workflow_runs), sha);
                if (!refreshed || refreshed.id !== run.id || refreshed.run_attempt !== run.run_attempt) {
                    await sleep(Math.min(intervalMs, Math.max(0, deadline - now())));
                    continue;
                }
                validateLivePR(await api(`pulls/${event.number}`), event);
                return { sha, run: run.id, attempt: run.run_attempt, url: `https://github.com/${REPO}/actions/runs/${run.id}` };
            }
        } else report(`Waiting for a develop build at ${sha}`);
        await sleep(Math.min(intervalMs, Math.max(0, deadline - now())));
    }
    throw new Error(`Timed out waiting for a successful develop build at ${sha}; merge remains blocked`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    assert.equal(process.env.GITHUB_REPOSITORY, REPO);
    const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
    validateCandidateEvent(event, process.env.GITHUB_EVENT_NAME,
        execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim());
    const api = (endpoint, paginate = false) => JSON.parse(execFileSync('gh', ['api',
        ...(paginate ? ['--paginate', '--slurp'] : []), `repos/${REPO}/${endpoint}`], { encoding: 'utf8', timeout: 60000 }));
    const result = await waitForDevelopmentBuild({ event, api });
    if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY,
        `\nDevelopment build verified: [run ${result.run}, attempt ${result.attempt}](${result.url})\nSource: ${result.sha}\n`);
}
