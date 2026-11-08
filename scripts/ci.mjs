// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { appendFileSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseAppVersion, versionPath } from './version-policy.mjs';

export const REPOSITORY = 'haorengg12/EhReader';
const shaPattern = /^[a-f0-9]{40}$/;
const validSha = sha => assert(shaPattern.test(sha || '') && !/^0+$/.test(sha), 'A fixed non-zero source/base SHA is required');
const run = (program, args) => execFileSync(program, args, { encoding: 'utf8' }).trim();
const git = (...args) => run('git', args);

/** 事件身份和版本一起分流；所有 PR 仅基础校验，绝不创建开发 Tag。 */
export function planCI({ event, eventName, sha, repository, readVersion, isAncestor }) {
    assert.equal(repository, REPOSITORY);
    assert.equal(event.repository?.full_name, REPOSITORY);
    validSha(sha);
    const current = parseAppVersion(readVersion(sha));
    const result = { sha, version: current.name, code: current.code, tag: current.tag, path: 'quality-only', debug: false, base: '' };
    if (eventName === 'pull_request') {
        assert(['opened', 'synchronize', 'reopened', 'ready_for_review', 'edited'].includes(event.action));
        assert(['develop', 'master'].includes(event.pull_request?.base.ref));
        assert.equal(event.pull_request.base.repo.full_name, REPOSITORY);
        assert.equal(event.pull_request.head.sha, sha, 'Quality must check exact PR head SHA');
        return result;
    }
    assert(['push', 'workflow_dispatch'].includes(eventName), 'Unsupported CI event');
    const ref = event.ref;
    assert(ref === 'refs/heads/develop' || ref?.startsWith('refs/heads/brody-'), 'Only develop and brody branches may use this entry');
    const development = ref === 'refs/heads/develop';
    assert.equal(current.name.split('.').length, development ? 3 : 4, 'Version shape does not match branch');
    if (!development) assert.equal(ref, `refs/heads/brody-${current.name}`, 'Snapshot branch/version mismatch');
    if (eventName === 'push') {
        assert(!event.deleted, 'Deleted branches cannot build');
        assert.equal(event.after, sha);
        validSha(event.before);
        result.base = event.before;
    } else {
        const repair = event.inputs?.repair_debug === true || event.inputs?.repair_debug === 'true';
        assert(!repair || development, 'Debug repair is restricted to develop');
        if (!repair && !event.inputs?.base_sha) return result;
        validSha(event.inputs?.base_sha);
        result.base = event.inputs.base_sha;
    }
    assert(isAncestor(result.base, sha), 'Base SHA must be an ancestor of checked source');
    const previous = parseAppVersion(readVersion(result.base));
    if (development) assert.equal(previous.name.split('.').length, 3, 'develop base cannot be a snapshot');
    result.path = versionPath(previous, current);
    assert(development ? result.path !== 'snapshot' : ['snapshot', 'save'].includes(result.path), 'Version path does not match branch');
    result.debug = development && result.path === 'development';
    if (eventName === 'workflow_dispatch') {
        const repair = event.inputs?.repair_debug === true || event.inputs?.repair_debug === 'true';
        if (repair) assert.equal(result.path, 'development', 'Repair must use the original pre-version-change base');
        result.debug = repair;
    }
    return result;
}

/** 原子创建开发 Tag；并发冲突只允许同 SHA 幂等，不移动 Tag、不创建 Release。 */
export async function ensureDevelopmentTag({ plan, findTag, createTag }) {
    assert(plan.debug && plan.path === 'development');
    const old = await findTag(plan.tag);
    if (old) { assert.equal(old, plan.sha, 'Development tag already belongs to another SHA; raise a new version'); return 'existing'; }
    try { await createTag(plan.tag, plan.sha); }
    catch (error) { assert.equal(await findTag(plan.tag), plan.sha, 'Concurrent tag conflict; no tag was overwritten'); return 'existing'; }
    return 'created';
}

function api(endpoint, method = 'GET', data) {
    const response = JSON.parse(execFileSync('gh', ['api', `repos/${REPOSITORY}/${endpoint}`, '--method', method, ...(data ? ['--input', '-'] : [])], { encoding: 'utf8', ...(data ? { input: JSON.stringify(data) } : {}) }));
    return response;
}

function remoteTag(tag) {
    let object;
    try { object = api(`git/ref/tags/${tag}`).object; }
    catch (error) { if (/HTTP 404/.test(String(error.stderr))) return undefined; throw error; }
    for (let i = 0; i < 8 && object.type === 'tag'; i++) object = api(`git/tags/${object.sha}`).object;
    assert.equal(object.type, 'commit', 'Unresolvable tag target');
    return object.sha;
}

function fromEnvironment() {
    const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
    if (process.env.GITHUB_EVENT_NAME === 'workflow_dispatch') event.ref = process.env.GITHUB_REF;
    const sha = git('rev-parse', 'HEAD');
    const expected = process.env.GITHUB_EVENT_NAME === 'pull_request' ? event.pull_request?.head.sha : process.env.GITHUB_SHA;
    assert.equal(sha, expected, 'Checkout does not match event source');
    return planCI({ event, eventName: process.env.GITHUB_EVENT_NAME, sha, repository: process.env.GITHUB_REPOSITORY,
        readVersion: ref => git('show', `${ref}:app/build.gradle.kts`),
        isAncestor: (base, head) => { try { git('merge-base', '--is-ancestor', base, head); return true; } catch { return false; } },
    });
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    const action = process.argv[2];
    const plan = fromEnvironment();
    if (action === 'plan') {
        if (plan.debug) {
            const target = remoteTag(plan.tag);
            assert(!target || target === plan.sha, 'Existing development tag requires a new version for repaired source');
        }
        if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, Object.entries(plan).map(([key, value]) => `${key}=${value}`).join('\n') + '\n');
        console.log(JSON.stringify(plan, null, 2));
        if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `Basic quality: ${plan.sha}\nVersion: ${plan.version} (${plan.code})\nPath: ${plan.path}; Debug: ${plan.debug}\nBase: ${plan.base || 'not used'}\n`);
    } else if (action === 'tag') {
        assert.notEqual(process.env.GITHUB_EVENT_NAME, 'pull_request');
        assert.equal(process.env.QUALITY_RESULT, 'success');
        assert.equal(process.env.DEBUG_RESULT, 'success', 'Tag requires this SHA Debug build and package checks');
        const status = await ensureDevelopmentTag({ plan, findTag: remoteTag, createTag: (tag, sha) => api('git/refs', 'POST', { ref: `refs/tags/${tag}`, sha }) });
        console.log(`${status}: ${plan.tag} -> ${plan.sha}; no Release created`);
        if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `Development tag ${status}: ${plan.tag}\nSource: ${plan.sha}\nDebug and package checks: success\nNo Release created.\n`);
    } else throw new Error('Expected plan or tag');
}
