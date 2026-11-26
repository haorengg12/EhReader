/** 记录来自已观察状态；默认只生成 temp 草稿，明确追加也不重复改写既有任务记录。 */
import { appendFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const display = text => String(text).replace(/[\r\n]/g, ' ').replace(/`/g, '\\`');
export function recordDraft(state, now = new Date()) {
    const date = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
    const label = { 'awaiting-review': '所调度自动检查完成，未据此取得人工测试或推送许可', 'needs-manual-validation': '部分完成，仍有未验证项目', failed: '失败，未完成后续步骤', blocked: '受阻', paused: '已暂停，未运行后续步骤', stale: '已有结果失效，待重新检查', running: '执行中，尚无最终结论' }[state.status] || state.status;
    const results = state.results.map(item => `${item.step}=${item.currentValidity === 'stale' ? 'stale' : item.status}`).join('；') || '未运行检查';
    return `<!-- local-task-record:${state.task} -->\n## ${date}：${state.task}\n\n- **内容**：${display(state.summary || '本地任务检查与材料准备')}；状态：${display(state.status)}。\n- **文件**：${state.files.map(file => '`' + display(path.relative(state.repo, file).split(path.sep).join('/')) + '`').join('、')}。\n- **结果**：${label}；${results}。版本 ${display(state.version)}。${state.gaps.length ? '缺项：' + state.gaps.map(display).join('；') + '。' : ''}证据位于工作区 \`temp/${state.task}/\`。未由本入口执行提交、推送、CI 或正式发行。\n`;
}

export function writeRecord(state, directory, { append = false } = {}) {
    const draft = path.join(directory, 'acceptance-draft.md');
    const text = recordDraft(state);
    writeFileSync(draft, text);
    if (!append) return { draft, appended: false };
    const target = path.join(state.repo, '.agents/docs/ACCEPTANCE.md');
    if (!existsSync(target)) throw new Error('ACCEPTANCE.md 不存在，不另建同用途文件');
    if (readFileSync(target, 'utf8').includes(`<!-- local-task-record:${state.task} -->`)) return { draft, appended: false, alreadyRecorded: true };
    if (state.status === 'running') throw new Error('执行中不追加最终记录，草稿仍在 temp');
    appendFileSync(target, '\n' + text);
    return { draft, appended: true };
}
