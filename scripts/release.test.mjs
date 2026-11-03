import test from 'node:test';
import assert from 'node:assert/strict';
import { parseVersion, validateEvent, validateHistory, releaseBody, REPO } from './release.mjs';
const sha = 'a'.repeat(40);
const event = () => ({ action: 'closed', number: 4, repository: { full_name: REPO }, pull_request: {
    merged: true, merge_commit_sha: sha,
    head: { ref: 'develop', repo: { full_name: REPO } }, base: { ref: 'master', repo: { full_name: REPO } },
} });
test('only same-repository develop merge can publish exact merge SHA', () => {
    validateEvent(event(), 'pull_request', sha);
    for (const change of [e => e.pull_request.merged = false, e => e.pull_request.head.ref = 'feature/test',
        e => e.pull_request.head.repo.full_name = 'other/EhReader', e => e.pull_request.base.ref = 'develop']) {
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
});
