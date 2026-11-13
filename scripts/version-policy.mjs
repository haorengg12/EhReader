// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
import assert from 'node:assert/strict';

/** 唯一版本来源；第四位用于测试包，不把版本数字猜测为 versionCode。 */
export function parseAppVersion(source) {
    const names = [...source.matchAll(/^\s*versionName\s*=\s*"([^"\n]+)"\s*$/gm)];
    const codes = [...source.matchAll(/^\s*versionCode\s*=\s*(\d+)\s*$/gm)];
    assert.equal(names.length, 1, 'Exactly one versionName is required');
    assert.equal(codes.length, 1, 'Exactly one versionCode is required');
    const name = names[0][1];
    assert.match(name, /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:\.([1-9]\d*))?$/);
    const parts = name.split('.').map(Number);
    assert(parts.every(Number.isSafeInteger), 'Version component is too large');
    const code = Number(codes[0][1]);
    assert(Number.isSafeInteger(code) && code > 0 && code <= 2100000000, 'Invalid versionCode');
    return { name, code, tag: `v${name}` };
}

export function compareVersions(a, b) {
    const left = a.split('.').map(Number);
    const right = b.split('.').map(Number);
    for (let i = 0; i < 4; i++) {
        const difference = (left[i] ?? 0) - (right[i] ?? 0);
        if (difference) return Math.sign(difference);
    }
    return 0;
}

/** 从左侧首个不同位选择唯一路径；右侧允许保留或改变，不强制归零。 */
export function versionPath(previous, current) {
    const before = previous.name.split('.').map(Number);
    const after = current.name.split('.').map(Number);
    if (previous.name === current.name) {
        assert.equal(current.code, previous.code, 'Changing only versionCode is not a version path');
        return 'save';
    }
    assert(compareVersions(current.name, previous.name) > 0, 'Version downgrade or equivalent spelling is forbidden');
    const index = after.findIndex((value, i) => value !== (before[i] ?? 0));
    assert(current.code > previous.code, 'New package versionCode must increase');
    return ['official', 'build', 'snapshot', 'testing'][index];
}

/** 上传前禁止第四位；本地测试结束须先准备递增的三段版本和安装编号。 */
export function validateUploadVersion(current) {
    assert.equal(current.name.split('.').length, 3, 'Fourth component is local only; raise a three-component version before upload');
}

/** 正式发行相对最高公开正式版本必须提高第一位；历史版本保持原状。 */
export function validateOfficialVersion(current, history) {
    assert.equal(current.name.split('.').length, 3, 'Official releases require three components');
    const formal = history.filter(old => old.formal !== false && old.tag !== current.tag);
    if (!formal.length) return;
    const highest = formal.reduce((a, b) => compareVersions(a.name, b.name) > 0 ? a : b);
    assert(Number(current.name.split('.')[0]) > Number(highest.name.split('.')[0]), 'Official release must raise the major component');
}
