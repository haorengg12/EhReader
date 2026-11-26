/** 检查结果只按真实输入复用；不把 Git SHA、时间戳或旧 PASS 当作充分条件。 */
import { createHash } from 'node:crypto';
import { existsSync, lstatSync, readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { defaultFiles, links } from './check-docs.mjs';

const digest = value => createHash('sha256').update(value).digest('hex');
const hash = value => digest(JSON.stringify(value));
const implementation = ['local-task.mjs', 'check-results.mjs', 'check-docs.mjs', 'task-records.mjs', 'task-recovery.mjs'].map(name => fileURLToPath(new URL(name, import.meta.url)));
const inside = (root, file) => { const rel = path.relative(root, file); return rel !== '..' && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel); };

function walk(directory) {
    if (!existsSync(directory)) return [];
    return readdirSync(directory, { withFileTypes: true }).flatMap(entry => entry.isDirectory() ? walk(path.join(directory, entry.name)) : [path.join(directory, entry.name)]);
}

function inputs(files, existenceOnly = []) {
    const only = new Set(existenceOnly);
    return [...new Set([...files, ...existenceOnly])].sort().map(file => {
        if (!existsSync(file)) return { file, value: 'missing' };
        if (lstatSync(file).isSymbolicLink()) return { file, value: 'unverifiable-symlink' };
        return { file, value: only.has(file) || statSync(file).isDirectory() ? 'exists' : digest(readFileSync(file)) };
    });
}

function git(repo, args) {
    const outcome = spawnSync('git', args, { cwd: repo, encoding: 'utf8', windowsHide: true, maxBuffer: 16 * 1024 * 1024 });
    if (outcome.status !== 0) throw new Error(`无法取得检查输入：git ${args[0]}`);
    return outcome.stdout;
}

export function fingerprint(step, { repo, files, env = process.env, node = process.version }) {
    let targets = [...implementation];
    const existenceOnly = [];
    const details = { step, scope: files, platform: process.platform, arch: process.arch, node, executable: process.execPath };
    details.git = git(repo, ['--version']).trim();
    // 保存环境散列而非环境值。临时目录、沙箱临时 AppData 与工具调用编号不是项目输入。
    const transient = new Set(['TEMP', 'TMP', 'TMPDIR', 'EHREADER_TEST_TEMP', 'LOCALAPPDATA', 'CODEX_TOOL_CALL_ID']);
    details.environment = hash(Object.entries(env).filter(([name]) => !transient.has(name.toUpperCase())).sort(([a], [b]) => a.localeCompare(b)));
    if (step === 'docs') {
        const workspace = path.dirname(repo);
        const complete = ['AGENTS.md', '文档导航.md', '项目结构与AI协作评估.md'].every(name => existsSync(path.join(workspace, name)));
        const sources = [...new Set([...defaultFiles(repo, complete), ...files.filter(file => file.endsWith('.md') && existsSync(file))])];
        targets.push(...sources);
        for (const source of sources) {
            if (!existsSync(source)) continue;
            for (const link of links(readFileSync(source, 'utf8'))) {
                if (link.missing || /^[a-z][a-z\d+.-]*:/i.test(link.target) || link.target.startsWith('//')) continue;
                try {
                    const location = link.target.split('#')[0].split('?')[0];
                    const target = location ? path.resolve(path.dirname(source), decodeURIComponent(location)) : source;
                    if (!complete && !inside(repo, target)) continue;
                    if (link.target.includes('#') && target.endsWith('.md')) targets.push(target);
                    else existenceOnly.push(target);
                } catch { /* 无效 URL 将在文档检查中失败，不缓存失败结果。 */ }
            }
        }
    } else if (step === 'node-tests') {
        targets.push(...walk(path.join(repo, 'scripts')), path.join(repo, '.github/RELEASE_TEMPLATE.md'));
    } else if (step === 'node-syntax') {
        targets.push(...files.filter(file => /\.(?:mjs|js)$/.test(file)));
    } else {
        targets.push(...files);
        if (step === 'diff') {
            const names = files.filter(file => inside(repo, file)).map(file => path.relative(repo, file));
            details.diff = names.length ? hash([git(repo, ['diff', '--no-ext-diff', '--no-textconv', '--raw', '--', ...names]), git(repo, ['diff', '--cached', '--no-ext-diff', '--no-textconv', '--raw', '--', ...names])]) : 'outside-repo';
            targets.push(path.join(repo, '.gitattributes'), path.join(repo, '.editorconfig'));
        }
    }
    // 源文件也可能同时作为链接目标；以内容检查优先，不能降级为仅检查存在性。
    details.inputs = inputs(targets, existenceOnly.filter(file => !targets.includes(file)));
    const unverifiable = details.inputs.some(item => item.value === 'unverifiable-symlink');
    const externalNode = Boolean(env.NODE_OPTIONS || env.NODE_PATH);
    const reusable = step !== 'docker' && !unverifiable && !externalNode;
    return { key: hash(details), reusable, reason: step === 'docker' ? 'Docker 持久签名卷和完整工具证据未自动固定，构建结果不自动复用' : unverifiable ? '存在未核验符号链接输入' : externalNode ? '外部 Node 注入模块的输入未固定，不自动复用' : '', details };
}

export function reusableResult(records, current) {
    if (!current.reusable) return { reason: current.reason };
    for (const record of [...records].reverse()) {
        if (!['passed', 'reused'].includes(record.status) || record.fingerprint?.key !== current.key) continue;
        if (!record.log || !existsSync(record.log)) return { reason: '原日志证据缺失' };
        if (!record.logHash || digest(readFileSync(record.log)) !== record.logHash) return { reason: '原日志证据已变化' };
        return { record };
    }
    return { reason: '无相同输入、配置、工具环境及检查范围的有效成功结果' };
}

export const evidenceHash = file => digest(readFileSync(file));
