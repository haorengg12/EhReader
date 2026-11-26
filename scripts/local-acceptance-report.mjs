import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** 按交付时的需求记录生成提醒。每次读取当前状态，不读取历史报告。 */
export function acceptanceReport(repo) {
    const source = path.join(path.dirname(repo), '需求分析记录整理.md');
    const section = readFileSync(source, 'utf8').split(/^## /m)
        .find(text => /^1\. 已实现需求[ \t]*\r?\n/.test(text));
    const [header, separator, ...rows] = (section ?? '').split(/\r?\n/)
        .map(line => line.trim()).filter(line => line.startsWith('|'));
    if (!/^\|\s*编号 \/ 范围\s*\|\s*简短描述\s*\|\s*验收状态\s*\|$/.test(header ?? '')
        || !/^\|(?:\s*-{3,}\s*\|){3}$/.test(separator ?? '') || !rows.length) {
        throw new Error(`找不到完整的已实现需求验收状态表：${source}`);
    }

    const pending = new Set();
    for (const row of rows) {
        const cells = row.slice(1, -1).split(/(?<!\\)\|/).map(cell => cell.trim());
        const id = cells[0].match(/^F\d{2,}(?=\s|$)/)?.[0];
        const status = cells[2]?.match(/^(待验收|已验收)(?=$|[：:\s])/)?.[1];
        if (!row.endsWith('|') || cells.length !== 3 || !id || !status) {
            throw new Error(`无法识别需求的验收状态：${row}`);
        }
        if (status === '待验收') pending.add(id);
    }
    const ids = [...pending].sort((a, b) => a.localeCompare(b, 'en', { numeric: true }));
    return ids.length ? `${ids.join('、')} 尚未验收。\n` : '当前已交付需求均已验收。\n';
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        const [output] = process.argv.slice(2);
        if (!output) throw new Error('请指定本次打包的输出目录');
        const repo = fileURLToPath(new URL('..', import.meta.url));
        writeFileSync(path.join(output, '验收报告.md'), acceptanceReport(path.resolve(repo)), { flag: 'wx' });
    } catch (error) {
        console.error(error.message);
        process.exitCode = 1;
    }
}
