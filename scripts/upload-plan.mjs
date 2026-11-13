// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseAppVersion, validateUploadVersion, versionPath } from './version-policy.mjs';

/** 只生成固定源码和比较基准的上传计划；不提交、建分支、推送或合并。 */
export function planUpload({ previous, current, localPrevious, base, sha, snapshotId }) {
    for (const value of [base, sha]) assert(/^[a-f0-9]{40}$/.test(value) && !/^0+$/.test(value), 'Fixed full SHAs are required');
    validateUploadVersion(current);
    if (localPrevious) {
        versionPath(localPrevious, current);
        assert(current.code >= localPrevious.code, 'Installation code cannot decrease when leaving local testing');
    }
    const route = versionPath(previous, current);
    let branch = 'develop';
    if (route === 'snapshot') {
        assert.match(snapshotId, /^[a-f0-9]{6}$/, 'Snapshot suffix must contain six UUID hex characters');
        branch = `develop-${snapshotId}`;
    }
    return { base, sha, version: current.name, code: current.code, path: route, branch,
        ci: ['official', 'build'].includes(route) ? 'debug' : route === 'save' ? 'quality' : 'none',
        afterCI: route === 'official' ? 'assistant-pr-merge-release' : 'report-only' };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    const args = process.argv.slice(2);
    assert(args.length === 2 && args[0] === '--base', 'Usage: node scripts/upload-plan.mjs --base FULL_ORIGIN_DEVELOP_SHA');
    const git = (...values) => execFileSync('git', values, { encoding: 'utf8' }).trim();
    assert.equal(git('branch', '--show-current'), 'develop', 'Prepare uploads from local develop');
    assert.equal(git('status', '--porcelain'), '', 'Commit the reviewed changes before planning an upload');
    const base = args[1];
    assert.equal(base, git('rev-parse', 'origin/develop'), 'Base must match fetched origin/develop; fetch and recheck before upload');
    const sha = git('rev-parse', 'HEAD');
    git('merge-base', '--is-ancestor', base, sha);
    const read = ref => parseAppVersion(git('show', `${ref}:app/build.gradle.kts`));
    const plan = planUpload({ previous: read(base), current: read(sha), localPrevious: read(`${sha}^1`),
        base, sha, snapshotId: randomUUID().replaceAll('-', '').slice(0, 6) });
    console.log(JSON.stringify(plan, null, 2));
}
