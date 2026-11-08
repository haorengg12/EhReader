// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
import assert from 'node:assert/strict';

/** 唯一版本来源；第四位只用于 brody 保存，不把版本数字猜测为 versionCode。 */
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

/** 用户指定的单次版本对齐；不放宽其他版本跃迁的归零要求。 */
function isVersionAlignment(previous, current) {
    return previous.name === '0.1.2' && previous.code === 102 && current.name === '1.8.15' && current.code > previous.code;
}

/** 返回 save / development / official / snapshot；非法变化失败，不能静默跳过。 */
export function versionPath(previous, current) {
    const before = previous.name.split('.').map(Number);
    const after = current.name.split('.').map(Number);
    if (previous.name === current.name) {
        assert.equal(current.code, previous.code, 'Changing only versionCode is not a version path');
        return 'save';
    }
    assert(compareVersions(current.name, previous.name) > 0, 'Version downgrade or equivalent spelling is forbidden');
    const index = after.findIndex((value, i) => value !== (before[i] ?? 0));
    if (after.length === 4) {
        assert(index === 3 && after.slice(0, 3).every((value, i) => value === before[i]), 'Snapshot must change only the fourth component');
        assert(current.code >= previous.code, 'Snapshot versionCode cannot decrease');
        return 'snapshot';
    }
    assert(current.code > previous.code, 'New package versionCode must increase');
    if (index === 0 || index === 1) {
        assert(isVersionAlignment(previous, current) || after.slice(index + 1).every(value => value === 0), 'Major/minor increments must reset following components');
        return 'official';
    }
    assert.equal(index, 2, 'Invalid development version change');
    return 'development';
}

/** 正式发行相对最高公开正式版本必须提高第一或第二位；开发 Tag 不参与历史。 */
export function validateOfficialVersion(current, history) {
    assert.equal(current.name.split('.').length, 3, 'Official releases require three components');
    const formal = history.filter(old => old.formal !== false && old.tag !== current.tag);
    if (!formal.length) return;
    const highest = formal.reduce((a, b) => compareVersions(a.name, b.name) > 0 ? a : b);
    const before = highest.name.split('.').map(Number);
    const after = current.name.split('.').map(Number);
    const index = after.findIndex((value, i) => value !== before[i]);
    assert((index === 0 || index === 1) && after[index] > before[index], 'Official release must raise the major or minor component');
    assert(isVersionAlignment(highest, current) || after.slice(index + 1).every(value => value === 0), 'Official release must reset following components');
}
