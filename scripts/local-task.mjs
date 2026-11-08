/** 本地任务调度；只准备检查、Debug 构建和交付材料，不执行 Git 写入或远端操作。 */
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { closeSync, copyFileSync, existsSync, lstatSync, mkdirSync, openSync, readFileSync, readdirSync, renameSync, statSync, unlinkSync, writeFileSync, constants } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkFiles, defaultFiles } from './check-docs.mjs';
import { evidenceHash, fingerprint, reusableResult } from './check-results.mjs';
import { writeRecord } from './task-records.mjs';
import { failureKind, retryable } from './task-recovery.mjs';

export const IMAGE = 'ehreader-build:ubuntu24-jdk21-sdk36-ndk29';
const defaultRepo = fileURLToPath(new URL('../', import.meta.url));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const json = value => JSON.stringify(value, null, 2) + '\n';
const relative = (root, file) => path.relative(root, file).split(path.sep).join('/');
const inside = (root, file) => { const rel = path.relative(root, file); return rel !== '..' && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel); };

export function command(command, args, cwd, env = {}) {
    const result = spawnSync(command, args, { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, windowsHide: true, env: { ...process.env, ...env } });
    return { code: result.status ?? 1, rawExitCode: result.status, signal: result.signal, errorCode: result.error?.code, stdout: result.stdout || '', stderr: (result.stderr || '') + (result.error ? result.error.message : '') };
}

function git(repo, args) {
    const result = command('git', args, repo);
    if (result.code) throw new Error(`git ${args[0]}: ${result.stderr}`);
    return result.stdout;
}

function hashFiles(files) {
    return [...new Set(files)].sort().map(file => {
        if (existsSync(file) && lstatSync(file).isSymbolicLink()) throw new Error(`输入符号链接需人工核验：${file}`);
        if (existsSync(file) && statSync(file).isDirectory()) throw new Error(`目录或子模块输入需人工核验：${file}`);
        return { file, hash: existsSync(file) ? sha(readFileSync(file)) : 'missing' };
    });
}

function sourceSnapshot(repo) {
    const names = git(repo, ['ls-files', '-z', '--cached', '--others', '--exclude-standard']).split('\0').filter(Boolean);
    return hashFiles(names.map(name => path.join(repo, name)));
}

export function selectSteps(files, repo) {
    const names = files.map(file => relative(repo, file));
    const isAndroid = name => name.startsWith('app/') || name.startsWith('gradle/') || /(?:^|\/)(?:gradlew(?:\.bat)?|gradle\.properties|.*\.gradle\.kts)$/.test(name);
    const android = names.some(isAndroid);
    const scripts = names.some(name => /\.(?:mjs|js|py|ps1|sh)$/.test(name));
    const docs = names.some(name => name.endsWith('.md'));
    const gaps = names.filter(name => !name.endsWith('.md') && !/\.(?:mjs|js)$/.test(name) && !isAndroid(name))
        .map(name => `需人工判断或额外静态检查：${name}`);
    const nodeSyntax = names.some(name => /\.(?:mjs|js)$/.test(name));
    return { steps: ['text', ...(docs ? ['docs'] : []), ...(nodeSyntax ? ['node-syntax'] : []), ...(scripts ? ['node-tests'] : []), 'diff', ...(android ? ['docker'] : [])], gaps };
}

function taskDirectory(repo, task) {
    if (!/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}$/.test(task)) throw new Error('任务名只允许字母、数字、下划线和连字符，最长 80 字符');
    return path.join(path.dirname(repo), 'temp', task);
}

function save(state, directory) {
    const file = path.join(directory, 'state.json');
    writeFileSync(`${file}.new`, json(state));
    renameSync(`${file}.new`, file);
    const lines = [`# ${state.task} 本地审核材料`, '', `状态：${state.status}`, `版本：${state.version}`, ...(state.pending ? [`暂停原因：${state.pauseReason}`, `剩余步骤：${state.pending.join('、')}`] : []), '', '## 本次范围', '', ...state.files.map(file => `- ${file}`), '', '## 检查结果', '', ...state.results.map(item => `- ${item.step}：${item.status}；退出码 ${item.code ?? '未运行'}；证据 ${item.log || '无'}`), '', '## 缺项', '', ...state.gaps.map(gap => `- ${gap}`), '', '仅本地交付；人工测试、提交推送及 master 合并权限均未由本入口取得。', ''];
    writeFileSync(path.join(directory, 'review.md'), lines.join('\n'));
    writeRecord(state, directory);
}

/** 收尾再次检查较早完成项，防止后续步骤期间的并发改动留下过期成功状态。 */
function validateResults(state) {
    for (const result of state.results.filter(item => ['passed', 'reused'].includes(item.status))) {
        const current = fingerprint(result.step, { repo: state.repo, files: state.files });
        const reuse = reusableResult([result], current);
        result.currentValidity = reuse.record ? 'valid' : current.reusable ? 'stale' : 'unverified';
        result.validityReason = reuse.reason || '';
        if (result.currentValidity === 'stale' && !['failed', 'blocked', 'paused'].includes(state.status)) state.status = 'stale';
    }
    return state;
}

function publish(buildOutput, repo, task, attempt) {
    const entries = readdirSync(buildOutput);
    const apks = entries.filter(name => name.endsWith('.apk'));
    if (apks.length !== 1 || !entries.includes('VALIDATION.txt')) throw new Error('Debug 交付必须有一个 APK 和 VALIDATION.txt');
    const sums = readFileSync(path.join(buildOutput, 'SHA256SUMS.txt'), 'utf8').trim().split('\n');
    if (sums.length !== 1) throw new Error('Debug 散列清单数量不符');
    const match = sums[0].match(/^([a-f\d]{64})\s+([^/\\]+\.apk)$/i);
    if (!match || match[2] !== apks[0] || sha(readFileSync(path.join(buildOutput, apks[0]))) !== match[1].toLowerCase()) throw new Error('APK 散列清单不匹配');
    const destination = path.join(path.dirname(repo), 'release', `${task}-${attempt}`);
    mkdirSync(destination, { recursive: true });
    for (const name of [apks[0], 'SHA256SUMS.txt', 'VALIDATION.txt', 'source-sha.txt', 'source-status.txt']) {
        copyFileSync(path.join(buildOutput, name), path.join(destination, name), constants.COPYFILE_EXCL);
    }
    return destination;
}

/** 注入 executor 只供隔离测试；实际 CLI 始终调用固定命令，不接收任意 shell 文本。 */
export async function runTask({ repo = defaultRepo, task, files, executor = command, offline = false, pauseBefore, summary }) {
    repo = path.resolve(repo);
    const workspace = path.dirname(repo);
    const directory = taskDirectory(repo, task);
    mkdirSync(directory, { recursive: true });
    const lockPath = path.join(directory, '.lock');
    const lock = openSync(lockPath, 'wx');
    let state;
    try {
        const statePath = path.join(directory, 'state.json');
        const previous = existsSync(statePath) ? JSON.parse(readFileSync(statePath, 'utf8')) : undefined;
        if (previous && (previous.schema !== 1 || previous.repo !== repo)) throw new Error('任务状态格式或仓库不匹配');
        const scope = files?.length ? [...new Set(files.map(file => path.resolve(repo, file)))].sort() : previous?.files;
        if (!scope?.length) throw new Error('首次执行必须用 --files 明确本次文件，避免夹带其他改动');
        if (scope.some(file => !inside(workspace, file) || !inside(repo, file) && (path.dirname(file) !== workspace || !file.endsWith('.md')))) throw new Error('文件范围仅限当前仓库及工作区根目录 Markdown');
        for (const file of scope.filter(file => !existsSync(file))) {
            if (!inside(repo, file) || command('git', ['ls-files', '--error-unmatch', '--', relative(repo, file)], repo).code) throw new Error(`本次文件不存在且不是已跟踪删除：${file}`);
        }
        if (previous && JSON.stringify(scope) !== JSON.stringify(previous.files)) throw new Error('本次文件范围发生变化，请使用新任务名');
        const plan = selectSteps(scope, repo);
        if (pauseBefore && !plan.steps.includes(pauseBefore)) throw new Error('pause-before 必须是本任务已选择的检查步骤');
        const attempt = `${Date.now()}-${process.pid}`;
        const attemptDir = path.join(directory, attempt);
        mkdirSync(attemptDir);
        const versionFile = path.join(repo, 'app/build.gradle.kts');
        const gradle = existsSync(versionFile) ? readFileSync(versionFile, 'utf8') : '';
        state = { schema: 1, repo, task, files: scope, status: 'running', version: `${gradle.match(/versionName\s*=\s*"([^"]+)"/)?.[1] ?? '未知'} / ${gradle.match(/versionCode\s*=\s*(\d+)/)?.[1] ?? '未知'}`, head: git(repo, ['rev-parse', 'HEAD']).trim(), results: [], gaps: [...plan.gaps], history: previous ? [...previous.history, { status: previous.status, results: previous.results }] : [] };
        state.summary = summary || previous?.summary;
        const before = sourceSnapshot(repo);
        writeFileSync(path.join(attemptDir, 'source-before.json'), json(before));
        save(state, directory);
        for (const step of plan.steps) {
            if (step === pauseBefore) {
                state.status = 'paused';
                state.pending = plan.steps.slice(plan.steps.indexOf(step));
                state.pauseReason = `用户指定在 ${step} 前暂停`;
                save(state, directory);
                return state;
            }
            const input = fingerprint(step, { repo, files: scope });
            const records = previous ? [...previous.history.flatMap(item => item.results), ...previous.results].filter(item => item.step === step) : [];
            const reusable = reusableResult(records, input);
            if (reusable.record) {
                state.results.push({ ...reusable.record, status: 'reused', reusedAt: new Date().toISOString(), fingerprint: input });
                save(state, directory);
                continue;
            }
            const result = { step, status: 'running', startedAt: new Date().toISOString(), log: path.join(attemptDir, `${step}.log`), fingerprint: input, rerunReason: reusable.reason, commands: [] };
            state.results.push(result);
            save(state, directory);
            const messages = [];
            let retryUsed = records.some(record => record.fingerprint?.key === input.key && record.retries >= 1);
            result.retries = 0;
            const execute = async (program, args, env) => {
                for (;;) {
                    messages.push(json({ program, args }));
                    const outcome = await executor(program, args, repo, env);
                    const kind = outcome.code ? failureKind(outcome) : undefined;
                    result.commands.push({ program, args, code: outcome.code, rawExitCode: outcome.rawExitCode, signal: outcome.signal, kind });
                    messages.push(outcome.stdout || '', outcome.stderr || '');
                    if (!outcome.code) return outcome.stdout || '';
                    if (!retryUsed && retryable(program, args, kind)) {
                        if (git(repo, ['rev-parse', 'HEAD']).trim() !== state.head || fingerprint(step, { repo, files: scope }).key !== input.key) {
                            const error = new Error('临时故障后输入或 SHA 已变化，不自动重试'); error.category = 'input-changed'; throw error;
                        }
                        retryUsed = true;
                        result.retries = 1;
                        messages.push('相同 SHA/输入的临时只读故障：自动重试 1/1，保留首次失败。');
                        continue;
                    }
                    const error = new Error(`命令失败，退出码 ${outcome.code}`);
                    if (retryUsed && retryable(program, args, kind)) messages.push('该输入的自动重试额度已用尽，需排查或外部状态改变，不能循环刷新额度。');
                    error.exitCode = outcome.code; error.category = kind; throw error;
                }
            };
            try {
                if (step === 'text') {
                    const targets = scope.filter(file => existsSync(file) && /\.(?:md|mjs|js|json|ya?ml|py|ps1|sh|kts|kt|java|xml|toml|properties)$/.test(file));
                    const checked = checkFiles(targets, { encodingOnly: true });
                    messages.push(json(checked));
                    if (checked.issues.length) throw new Error('本次文本编码检查失败');
                } else if (step === 'docs') {
                    const withWorkspace = ['AGENTS.md', '文档导航.md', '项目结构与AI协作评估.md'].every(name => existsSync(path.join(workspace, name)));
                    const checked = checkFiles([...defaultFiles(repo, withWorkspace), ...scope.filter(file => existsSync(file) && file.endsWith('.md'))], { root: withWorkspace ? undefined : repo });
                    messages.push(json(checked));
                    if (checked.issues.length) throw new Error('文档检查失败');
                } else if (step === 'node-syntax') {
                    for (const file of scope.filter(file => existsSync(file) && /\.(?:mjs|js)$/.test(file))) await execute(process.execPath, ['--check', file]);
                } else if (step === 'node-tests') {
                    const tests = readdirSync(path.join(repo, 'scripts')).filter(name => name.endsWith('.test.mjs')).map(name => path.join(repo, 'scripts', name));
                    if (!tests.length) throw new Error('没有 Node 测试源，不能声明测试通过');
                    await execute(process.execPath, ['--test', ...tests], { EHREADER_TEST_TEMP: attemptDir, TMP: attemptDir, TEMP: attemptDir, TMPDIR: attemptDir });
                } else if (step === 'diff') {
                    const repoFiles = scope.filter(file => inside(repo, file)).map(file => relative(repo, file));
                    if (repoFiles.length) {
                        await execute('git', ['diff', '--check', '--', ...repoFiles]);
                        await execute('git', ['diff', '--cached', '--check', '--', ...repoFiles]);
                    }
                    for (const file of scope.filter(file => /\.(?:md|mjs|js|py|ps1|sh|ya?ml)$/.test(file))) {
                        if (existsSync(file) && /[\t ]+$/m.test(readFileSync(file, 'utf8'))) throw new Error(`行尾空白：${file}`);
                    }
                } else if (step === 'docker') {
                    const tools = path.join(workspace, 'build-tools/ehreader-docker');
                    const builder = path.join(tools, 'build.ps1');
                    if (!existsSync(builder)) throw new Error('仓库外 Docker 入口缺失，未构建');
                    const image = (await execute('docker', ['image', 'inspect', IMAGE, '--format', '{{.Id}}'])).trim();
                    if (!/^sha256:[a-f\d]{64}$/i.test(image)) throw new Error('无法固定实际镜像 ID');
                    if ((await execute('docker', ['ps', '-q', '--filter', 'volume=ehreader-workspace'])).trim()) throw new Error('固定 Docker 工作卷被其他容器占用');
                    const outputRoot = path.join(attemptDir, 'docker');
                    mkdirSync(outputRoot);
                    const args = ['-NoProfile', '-NonInteractive', '-File', builder, '-Mode', 'Debug', '-Source', repo, '-OutputRoot', outputRoot];
                    if (offline) args.push('-OfflineDependencies');
                    await execute(process.platform === 'win32' ? 'powershell.exe' : 'pwsh', args);
                    const after = sourceSnapshot(repo);
                    writeFileSync(path.join(attemptDir, 'source-after.json'), json(after));
                    if (json(before) !== json(after)) throw new Error('构建期间宿主源码发生变化，禁止交付；未还原任何文件');
                    const builds = readdirSync(outputRoot).filter(name => statSync(path.join(outputRoot, name)).isDirectory());
                    if (builds.length !== 1) throw new Error('本次构建输出目录数量不符');
                    state.delivery = publish(path.join(outputRoot, builds[0]), repo, task, attempt);
                    writeFileSync(path.join(state.delivery, 'SOURCE.json'), json({ head: state.head, image, inputs: before }));
                    state.gaps.push('设备测试未运行；调试证书预期指纹、实际完整工具版本、替换基线等仍按 Docker 能力表补查。');
                }
                const afterInput = fingerprint(step, { repo, files: scope });
                if (afterInput.key !== input.key) throw new Error('检查期间相关输入发生变化，结果失效，需重新执行受影响检查');
                result.status = 'passed';
                result.code = 0;
            } catch (error) {
                result.status = error.category === 'cancelled' ? 'cancelled' : 'failed';
                result.code = error.exitCode ?? 1;
                result.failureKind = error.category || (/输入发生变化|宿主源码发生变化/.test(error.message) ? 'input-changed' : 'validation');
                result.error = error.message;
                messages.push(error.message);
                state.status = 'failed';
            }
            result.finishedAt = new Date().toISOString();
            writeFileSync(result.log, messages.join('\n') + '\n');
            result.logHash = evidenceHash(result.log);
            save(state, directory);
            if (state.status === 'failed') return state;
        }
        state.status = state.gaps.length ? 'needs-manual-validation' : 'awaiting-review';
        validateResults(state);
        save(state, directory);
        if (state.delivery) copyFileSync(path.join(directory, 'review.md'), path.join(state.delivery, 'REVIEW.md'), constants.COPYFILE_EXCL);
        return state;
    } catch (error) {
        if (state) { state.status = 'blocked'; state.gaps.push(error.message); save(state, directory); }
        throw error;
    } finally {
        closeSync(lock);
        unlinkSync(lockPath);
    }
}

export function readTask(repo, task) {
    repo = path.resolve(repo);
    const state = JSON.parse(readFileSync(path.join(taskDirectory(repo, task), 'state.json'), 'utf8'));
    if (state.schema !== 1 || state.repo !== repo) throw new Error('任务状态格式或仓库不匹配');
    return validateResults(state);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        const args = process.argv.slice(2);
        const action = args.shift();
        if (action === '--help' || !action) {
            console.log('node scripts/local-task.mjs run --task 名称 --files 文件... [--summary 本次更改] [--offline] [--pause-before 步骤]\nnode scripts/local-task.mjs run --task 名称  # 复核输入并恢复，只重跑失效项\nnode scripts/local-task.mjs status --task 名称\nnode scripts/local-task.mjs record --task 名称 [--append]\n每项临时只读故障最多自动重试一次；构建、签名、标签、权限或输入变化不自动重试。\n默认只做本地准备，temp/<任务> 保存状态/证据/记录草稿；不提交推送，不跟踪 CI，不取得测试或发行许可。');
        } else {
            if (!['run', 'status', 'record'].includes(action)) throw new Error('未知动作，使用 --help 查看用法');
            let task;
            let files;
            let offline = false;
            let append = false;
            let pauseBefore;
            let summary;
            while (args.length) {
                const arg = args.shift();
                if (arg === '--task') task = args.shift();
                else if (arg === '--offline') offline = true;
                else if (arg === '--append') append = true;
                else if (arg === '--pause-before') pauseBefore = args.shift();
                else if (arg === '--summary') summary = args.shift();
                else if (arg === '--files') {
                    files = [];
                    while (args.length && !args[0].startsWith('--')) files.push(args.shift());
                } else throw new Error(`未知参数：${arg}`);
            }
            if (!task) throw new Error('必须指定 --task');
            if (append && action !== 'record') throw new Error('--append 仅用于 record');
            const state = action === 'run' ? await runTask({ task, files, offline, pauseBefore, summary }) : readTask(defaultRepo, task);
            if (action === 'record') console.log(JSON.stringify(writeRecord(state, taskDirectory(defaultRepo, task), { append }), null, 2));
            console.log(json({ task: state.task, status: state.status, pending: state.pending, results: state.results.map(({ step, status, currentValidity, validityReason }) => ({ step, status, currentValidity, validityReason })), gaps: state.gaps, delivery: state.delivery }));
            process.exitCode = ['failed', 'blocked', 'stale', 'needs-manual-validation'].includes(state.status) ? 1 : 0;
        }
    } catch (error) { console.error(error.message); process.exitCode = 1; }
}
