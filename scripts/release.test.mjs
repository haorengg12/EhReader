import test from 'node:test';
import assert from 'node:assert/strict';
import { parseVersion, validateEvent, validateHistory, validateCandidateEvent, validateCandidateVersion, releaseBody, REPO } from './release.mjs';
const sha = 'a'.repeat(40);
const event = () => ({ action: 'closed', number: 4, repository: { full_name: REPO }, pull_request: {
    merged: true, merge_commit_sha: sha,
    head: { ref: 'develop', repo: { full_name: REPO } }, base: { ref: 'master', repo: { full_name: REPO } },
} });
test('only same-repository develop merge can publish exact merge SHA', () => {
    validateEvent(event(), 'pull_request', sha);
    for (const change of [e => e.pull_request.merged = false, e => e.pull_request.head.ref = 'feature/test',
        e => e.pull_request.head.repo.full_name = 'other/EhReader', e => e.pull_request.base.ref = 'develop',
        e => e.pull_request.base.repo.full_name = 'other/EhReader', e => e.repository.full_name = 'other/EhReader',
        e => e.action = 'opened']) {
        const e = event(); change(e); assert.throws(() => validateEvent(e, 'pull_request', sha));
    }
    assert.throws(() => validateEvent(event(), 'push', sha));
    assert.throws(() => validateEvent(event(), 'workflow_dispatch', sha));
    assert.throws(() => validateEvent(event(), 'pull_request', 'b'.repeat(40)));
});
test('versions reject malformed or ambiguous configuration', () => {
    assert.equal(parseVersion('versionName = "0.1.1"\nversionCode = 101').tag, 'v0.1.1');
    for (const source of ['versionName = "01.1.1"\nversionCode = 101', 'versionName = "0.1.1-beta"\nversionCode = 101',
        'versionName = "0.1.1"\nversionCode = 0', 'versionName = "0.1.1"\nversionCode = 101\nversionCode = 102']) {
        assert.throws(() => parseVersion(source));
    }
});
test('PR readiness checks exact same-repository develop head without allowing publication', () => {
    const e = event();
    e.action = 'opened';
    e.pull_request.merged = false;
    e.pull_request.head.sha = sha;
    validateCandidateEvent(e, 'pull_request', sha);
    assert.throws(() => validateEvent(e, 'pull_request', sha));
    assert.throws(() => validateCandidateEvent(e, 'workflow_dispatch', sha));
    assert.throws(() => validateCandidateEvent(e, 'pull_request', 'b'.repeat(40)));
    for (const change of [e => e.pull_request.head.ref = 'feature/test',
        e => e.pull_request.head.repo.full_name = 'other/EhReader', e => e.pull_request.base.ref = 'develop',
        e => e.pull_request.merged = true, e => e.action = 'closed']) {
        const changed = structuredClone(e); change(changed);
        assert.throws(() => validateCandidateEvent(changed, 'pull_request', sha));
    }
});
test('PR readiness rejects published versions, orphan tags and non-increasing version codes', () => {
    const old = { name: '0.1.1', code: 101, tag: 'v0.1.1', sha };
    const next = { name: '0.1.2', code: 102, tag: 'v0.1.2' };
    validateCandidateVersion(next, [old], sha, false);
    assert.throws(() => validateCandidateVersion(old, [old], sha, true), /Update versionName and versionCode/);
    assert.throws(() => validateCandidateVersion(old, [old], sha, false), /already exists/);
    assert.throws(() => validateCandidateVersion(next, [old], sha, true), /already exists/);
    assert.throws(() => validateCandidateVersion({ ...next, code: 101 }, [old], sha, false), /versionCode must exceed/);
});
test('published history prevents version reuse, downgrade and moved tags', () => {
    const old = { name: '0.1.0', code: 100, tag: 'v0.1.0', sha };
    validateHistory({ name: '0.1.1', code: 101, tag: 'v0.1.1' }, [old], sha);
    validateHistory(old, [old], sha);
    assert.throws(() => validateHistory(old, [old], 'b'.repeat(40)));
    assert.throws(() => validateHistory({ name: '0.1.1', code: 100, tag: 'v0.1.1' }, [old], sha));
    assert.throws(() => validateHistory({ name: '0.0.9', code: 101, tag: 'v0.0.9' }, [old], sha));
});
test('release page links all four Android packages and corresponding source', () => {
    const text = releaseBody({ name: '0.1.1', code: 101, tag: 'v0.1.1', sha, event: event() });
    for (const abi of ['arm64-v8a', 'armeabi-v7a', 'x86_64', 'universal']) assert(text.includes(`android-${abi}.apk`));
    assert(text.includes('EhReader-0.1.1-source.zip'));
    assert(text.includes('设备运行验证未执行'));
    assert(text.includes('# EhReader v0.1.1'));
    assert(text.includes(`https://github.com/${REPO}/commits/${sha}`));
    assert(!text.includes('{{'));
    assert(!text.includes('Aviewer'));
    assert(!text.includes('ios.ipa'));
});
test('release page uses merge PR notes and previous published version for full changes', () => {
    const e = event();
    e.pull_request.title = '发布 [修复] <阅读器>';
    e.pull_request.body = '## 修复\n\n- 修复翻页\n- 保留示例 {{version}} 和 $&';
    const text = releaseBody({ name: '0.1.1', code: 101, tag: 'v0.1.1', sha, event: e, previousTag: 'v0.1.0' });
    assert(text.includes('发布 \\[修复\\] \\<阅读器\\> (#4)'));
    assert(text.includes(e.pull_request.body));
    assert(text.includes(`https://github.com/${REPO}/compare/v0.1.0...v0.1.1`));
});
test('empty PR notes fall back to history and invalid template placeholders fail', () => {
    const e = event();
    e.pull_request.body = '  \n';
    const state = { name: '0.1.1', code: 101, tag: 'v0.1.1', sha, event: e };
    assert(releaseBody(state).includes('详细变更见该 PR 和提交历史。'));
    assert.throws(() => releaseBody(state, '{{versoin}}'), /Unknown release template placeholder: versoin/);
});
