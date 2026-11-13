// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import { planCI, REPOSITORY } from './ci.mjs';
import { planUpload } from './upload-plan.mjs';
import { parseAppVersion, versionPath, compareVersions, validateOfficialVersion } from './version-policy.mjs';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const before = 'a'.repeat(40), after = 'b'.repeat(40);
const source = (name, code = 102) => 'versionName = "' + name + '"\nversionCode = ' + code + '\n';
const version = (name, code = 102) => parseAppVersion(source(name, code));
const push = () => ({ repository: { full_name: REPOSITORY }, ref: 'refs/heads/develop', before, after });
function plan(event = push(), old = '1.8.15', current = '2.9.16', eventName = 'push', oldCode = 102, newCode = 103) {
    return planCI({ event, eventName, sha: after, repository: REPOSITORY,
        readVersion: sha => { assert([before, after].includes(sha)); return source(sha === before ? old : current, sha === before ? oldCode : newCode); },
        isAncestor: (base, head) => base === before && head === after });
}
test('版本解析、整数比较与四位优先级；右侧允许保留、增加或清零', () => {
    for (const [from, to, route] of [
        ['1.8.15.2', '2.9.16.3', 'official'], ['1.8.15.2', '1.9.16.3', 'build'],
        ['1.8.15.2', '1.8.16.3', 'snapshot'], ['1.8.15.2', '1.8.15.3', 'testing'],
        ['1.8.15.2', '2.1.0', 'official'], ['1.8.15.2', '1.9.0', 'build'],
        ['1.8.15.2', '1.8.16', 'snapshot'], ['0.1.2', '1.8.15', 'official']]) {
        assert.equal(versionPath(version(from), version(to, 103)), route);
    }
    assert.equal(compareVersions('1.8.10', '1.8.9'), 1);
    assert.equal(compareVersions('1.8.15', '1.8.15.0'), 0);
    assert.equal(versionPath(version('1.8.15'), version('1.8.15')), 'save');
    for (const name of ['1.8.15.0', '1.8.15.01', '01.8.15', '1.8', '1.8.15-beta', '1.8.15.1.1', '9007199254740992.0.0']) assert.throws(() => version(name));
});
test('降版、仅去掉第四位、非递增安装编号和只改编号均失败', () => {
    for (const [from,to] of [['1.8.15.2','1.8.15'], ['1.8.15.2','1.8.15.1'], ['1.8.15','1.7.99']]) assert.throws(() => versionPath(version(from), version(to,103)), /downgrade/);
    assert.throws(() => versionPath(version('1.8.15'),version('1.8.15',103)), /only versionCode/);
    assert.throws(() => versionPath(version('1.8.15'),version('2.0.0')), /versionCode must increase/);
});
test('develop 第一位和第二位均打包；不升版仅基础检查；第三位及所有四段版本拒绝上传', () => {
    assert.equal(plan().path,'official'); assert.equal(plan().debug,true);
    assert.equal(plan(push(),'1.8.15','1.9.16').path,'build');
    assert.equal(plan(push(),'1.8.15','1.9.16').debug,true);
    assert.equal(plan(push(),'1.8.15','1.8.15','push',102,102).debug,false);
    assert.throws(() => plan(push(),'1.8.15','1.8.16'), /snapshot branch/);
    for (const current of ['1.8.15.1','1.9.16.1','2.9.16.1']) assert.throws(() => plan(push(),'1.8.15',current), /local only/);
});
test('上传计划固定远端基准、UUID临时分支及两种 develop 后续行为', () => {
    const upload = (name, id='a1b2c3') => planUpload({previous:version('1.8.15'),current:version(name,104),localPrevious:version('1.8.15.2',103),base:before,sha:after,snapshotId:id});
    assert.deepEqual([upload('1.8.16').branch,upload('1.8.16').ci],['develop-a1b2c3','none']);
    assert.equal(upload('2.9.16').afterCI,'assistant-pr-merge-release');
    assert.equal(upload('1.9.16').afterCI,'report-only');
    assert.equal(upload('1.9.16').branch,'develop');
    assert.throws(() => upload('1.8.16','abcdef7'), /six UUID/);
    assert.throws(() => upload('1.8.16.1'), /local only/);
    assert.throws(() => upload('1.8.15'), /downgrade/);
    assert.throws(() => planUpload({previous:version('1.8.15',100),current:version('1.8.16',102),localPrevious:version('1.8.15.2',103),base:before,sha:after,snapshotId:'abcdef'}), /versionCode must increase/);
});
test('所有 PR 固定 head 只读检查，第四位不能通过远端检查', () => {
    for (const base of ['develop','master']) {
        const event={repository:{full_name:REPOSITORY},action:'synchronize',pull_request:{head:{sha:after},base:{ref:base,repo:{full_name:REPOSITORY}}}};
        assert.equal(plan(event,'1.8.15','2.1.1','pull_request').debug,false);
        assert.throws(() => plan(event,'1.8.15','1.8.15.2','pull_request'), /local only/);
        event.pull_request.head.sha=before;
        assert.throws(() => plan(event,'1.8.15','2.1.1','pull_request'), /head SHA/);
    }
});
test('手动修复仅第一或第二位，必须提供原始基准，不能自动合并或发布', () => {
    const event={...push(),inputs:{repair_debug:'true',base_sha:before}};
    assert.equal(plan(event,'1.8.15','2.1.1','workflow_dispatch').debug,true);
    assert.equal(plan({...push(),inputs:{}},'1.8.15','2.1.1','workflow_dispatch').debug,false);
    assert.throws(() => plan({...event,inputs:{repair_debug:'true'}},'1.8.15','2.1.1','workflow_dispatch'), /fixed non-zero/);
    assert.throws(() => plan(event,'2.1.1','2.1.1','workflow_dispatch',103,103), /original pre-version-change/);
    assert.throws(() => plan(event,'1.8.15','1.8.16','workflow_dispatch'), /snapshot branch/);
});
test('固定事件SHA和祖先门禁拒绝误触发、首次建 develop、删除与临时分支', () => {
    assert.throws(() => plan({...push(),before:'0'.repeat(40)}), /fixed non-zero/);
    assert.throws(() => plan({...push(),before:after}), /ancestor/);
    assert.throws(() => plan({...push(),after:before}));
    assert.throws(() => plan({...push(),deleted:true}), /Deleted/);
    for (const ref of ['refs/heads/develop-abcdef','refs/heads/master','refs/heads/brody-1.8.15.1','refs/tags/v2.0.0']) assert.throws(() => plan({...push(),ref}), /Only develop/);
    assert.throws(() => plan(push(),'1.8.15','2.0.0','release'), /Unsupported/);
});
test('正式版本只接受第一位提升，开发标签不混入正式历史', () => {
    const history=[version('1.8.15'),{...version('8.0.0'),formal:false}];
    validateOfficialVersion(version('2.9.16',103),history);
    assert.throws(() => validateOfficialVersion(version('1.9.16',103),history), /major component/);
    assert.throws(() => validateOfficialVersion(version('1.8.16',103),history), /major component/);
    assert.throws(() => validateOfficialVersion(version('2.9.16.1',103),history), /three components/);
});
test('真实 CLI 仅输出固定 major 计划，不调用 gh、不创建分支或 Tag；四段误推失败', t => {
    const base=path.resolve(process.env.EHREADER_TEST_TEMP||tmpdir());
    const repo=mkdtempSync(path.join(base,'ci-plan-'));
    t.after(()=>{const rel=path.relative(base,repo);assert(rel.startsWith('ci-plan-')&&!rel.includes(path.sep));rmSync(repo,{recursive:true,force:true,maxRetries:6,retryDelay:100});});
    const git=(...args)=>execFileSync('git',args,{cwd:repo,encoding:'utf8'}).trim();
    git('init','-q','-b','develop'); mkdirSync(path.join(repo,'app'));
    writeFileSync(path.join(repo,'.git/info/exclude'),'event.json\noutput.txt\n');
    const commit=(name,code)=>{writeFileSync(path.join(repo,'app/build.gradle.kts'),source(name,code));git('add','.');git('-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','-qm','fixture');return git('rev-parse','HEAD');};
    const baseSha=commit('1.8.15',102);git('update-ref','refs/remotes/origin/develop',baseSha);
    const headSha=commit('2.9.16',103);
    const eventFile=path.join(repo,'event.json'); writeFileSync(eventFile,JSON.stringify({...push(),before:baseSha,after:headSha}));
    const output=path.join(repo,'output.txt');
    const env={...process.env,GITHUB_EVENT_PATH:eventFile,GITHUB_EVENT_NAME:'push',GITHUB_REPOSITORY:REPOSITORY,GITHUB_SHA:headSha,GITHUB_OUTPUT:output};
    const cli=fileURLToPath(new URL('./ci.mjs',import.meta.url));
    execFileSync(process.execPath,[cli,'plan'],{cwd:repo,env});
    assert.match(readFileSync(output,'utf8'),/path=official\ndebug=true/);
    const upload=JSON.parse(execFileSync(process.execPath,[fileURLToPath(new URL('./upload-plan.mjs',import.meta.url)),'--base',baseSha],{cwd:repo,encoding:'utf8'}));
    assert.equal(upload.branch,'develop');assert.equal(upload.sha,headSha);assert.equal(git('tag','--list'),'');
    env.GITHUB_SHA=commit('2.9.16.1',104);writeFileSync(eventFile,JSON.stringify({...push(),before:headSha,after:env.GITHUB_SHA}));
    assert.throws(()=>execFileSync(process.execPath,[cli,'plan'],{cwd:repo,env,stdio:'pipe'}),/local only/);
});
