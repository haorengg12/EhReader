// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { appendFileSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseAppVersion, versionPath, validateUploadVersion } from './version-policy.mjs';

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
    validateUploadVersion(current);
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
    assert.equal(ref, 'refs/heads/develop', 'Only develop may use this entry; snapshot branches have no CI');
    if (eventName === 'push') {
        assert(!event.deleted, 'Deleted branches cannot build');
        assert.equal(event.after, sha);
        validSha(event.before);
        result.base = event.before;
    } else {
        const repair = event.inputs?.repair_debug === true || event.inputs?.repair_debug === 'true';
        if (!repair && !event.inputs?.base_sha) return result;
        validSha(event.inputs?.base_sha);
        result.base = event.inputs.base_sha;
    }
    assert(isAncestor(result.base, sha), 'Base SHA must be an ancestor of checked source');
    const previous = parseAppVersion(readVersion(result.base));
    result.path = versionPath(previous, current);
    assert(['save', 'official', 'build'].includes(result.path), 'Third component must be uploaded to a develop-UUID snapshot branch');
    result.debug = ['official', 'build'].includes(result.path);
    if (eventName === 'workflow_dispatch') {
        const repair = event.inputs?.repair_debug === true || event.inputs?.repair_debug === 'true';
        if (repair) assert(['official', 'build'].includes(result.path), 'Repair must use the original pre-version-change base');
        result.debug = repair;
    }
    return result;
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
        if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, Object.entries(plan).map(([key, value]) => `${key}=${value}`).join('\n') + '\n');
        console.log(JSON.stringify(plan, null, 2));
        if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `Basic quality: ${plan.sha}\nVersion: ${plan.version} (${plan.code})\nPath: ${plan.path}; Debug: ${plan.debug}\nBase: ${plan.base || 'not used'}\n`);
    } else throw new Error('Expected plan; development tags are disabled');
}
