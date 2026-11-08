import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { anchors, checkFiles, defaultFiles } from './check-docs.mjs';

function fixture(t) {
    // 项目约定：运行测试时显式指定 EHREADER_TEST_TEMP 到工作区 temp。
    const base = path.resolve(process.env.EHREADER_TEST_TEMP || os.tmpdir());
    const root = mkdtempSync(path.join(base, 'ehreader-docs-'));
    t.after(() => {
        const relative = path.relative(base, path.resolve(root));
        assert.ok(relative.startsWith('ehreader-docs-') && !relative.includes(path.sep));
        rmSync(root, { recursive: true, force: true });
    });
    const write = (name, value) => {
        const file = path.join(root, name);
        mkdirSync(path.dirname(file), { recursive: true });
        writeFileSync(file, value);
        return file;
    };
    return { root, write };
}

test('有效链接：中文、转义空格、括号、图片、引用、目录、重复标题和 HTML 锚点', t => {
    const { write } = fixture(t);
    write('中文 (版本).md', '# 标题 A\n\n# 标题 A\n\n<a id="manual"></a>\n');
    write('image.png', 'image');
    const file = write('index.md', '[中文](<中文 (版本).md#标题-a>)\n[括号](中文%20(版本).md#标题-a-1)\n[显式][Ref]\n[ref][]\n[Ref]\n[目录](.)\n![图片](image.png)\n\n[ref]: <中文 (版本).md#manual> "说明"\n');
    const result = checkFiles([file]);
    assert.deepEqual(result.issues, []);
    assert.equal(result.linkCount, 7);
    assert.equal(result.anchorCount, 5);
});

test('错误链接返回文件、行号；缺失引用、锚点和错误 URL 编码均失败', t => {
    const { write } = fixture(t);
    write('target.md', '# 有效\n');
    const file = write('index.md', '# 入口\n[文件](missing.md)\n[锚点](target.md#不存在)\n[引用][missing]\n[编码](bad%XX.md)\n');
    const result = checkFiles([file]);
    assert.equal(result.issues.length, 4);
    assert.match(result.issues[0], /index\.md:2:/);
    assert.match(result.issues[1], /锚点不存在/);
    assert.match(result.issues[2], /引用定义缺失/);
});

test('示例代码、注释和外部链接不误报；同文件锚点有效', t => {
    const { write } = fixture(t);
    const file = write('index.md', '# 标题\n[本页](#标题)\n```md\n[x](missing.md)\n# 假标题\n```\n`[x](missing.md)`\n<!-- [x](missing.md) -->\n[x](https://example.invalid)\n[x](mailto:example@example.invalid)\n');
    const result = checkFiles([file]);
    assert.deepEqual(result.issues, []);
    assert.equal(result.linkCount, 1);
    assert.equal(anchors('# 标题\n```\n# 假标题\n```\n').has('假标题'), false);
});

test('标题格式、Setext、重复 slug 冲突与百分号编码锚点', t => {
    const { write } = fixture(t);
    const file = write('index.md', '# **标题** `A`\n# 标题 A-1\n# 标题 A\n\n第二标题\n===\n\n[x](#%E6%A0%87%E9%A2%98-a-2)\n[x](#第二标题)\n');
    assert.deepEqual(checkFiles([file]).issues, []);
});

test('无效 UTF-8、BOM、CRLF、末尾无换行及缺失输入失败', t => {
    const { root, write } = fixture(t);
    const files = [write('invalid.md', Buffer.from([0xff, 0x0a])), write('bom.md', '\ufeff# 标题\n'),
        write('crlf.md', '# 标题\r\n'), write('end.md', '# 标题'), path.join(root, 'absent.md')];
    assert.equal(checkFiles(files).issues.length, 5);
});

test('仅编码模式可检查发行模板，不将未渲染占位符当作文件链接', t => {
    const { write } = fixture(t);
    const file = write('template.md', '[下载]({{download_base}}/app.apk)\n');
    assert.equal(checkFiles([file]).issues.length, 1);
    assert.deepEqual(checkFiles([file], { encodingOnly: true }).issues, []);
    const result = spawnSync(process.execPath, [fileURLToPath(new URL('./check-docs.mjs', import.meta.url)), '--encoding-only', file], { encoding: 'utf8' });
    assert.equal(result.status, 0);
});

test('默认扫描递归包含技能；仓库独立克隆不依赖工作区；CLI 以退出码报告错误', t => {
    const { root, write } = fixture(t);
    write('AGENTS.md', '# 入口\n');
    write('README.md', '# 项目\n[工作区](../workspace-only-missing.md)\n');
    write('.agents/docs/rules.md', '# 规范\n');
    write('.agents/skills/docs/SKILL.md', '# 技能\n');
    assert.equal(defaultFiles(root).length, 4);
    assert.equal(defaultFiles(root, true).length, 7);
    assert.deepEqual(checkFiles(defaultFiles(root), { root }).issues, []);
    assert.equal(checkFiles(defaultFiles(root), { root }).workspaceLinks, 1);
    assert.equal(checkFiles(defaultFiles(root)).issues.length, 1);
    const file = write('broken.md', '[x](missing.md)\n');
    const result = spawnSync(process.execPath, [fileURLToPath(new URL('./check-docs.mjs', import.meta.url)), file], { encoding: 'utf8' });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /broken\.md:1:/);
    const success = spawnSync(process.execPath, [fileURLToPath(new URL('./check-docs.mjs', import.meta.url)), path.join(root, 'AGENTS.md')], { encoding: 'utf8' });
    assert.equal(success.status, 0);
});
