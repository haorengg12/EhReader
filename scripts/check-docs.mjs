/** 本地文档检查：不联网，不修改文件。支持项目使用的常规 Markdown 链接及标题。 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('../', import.meta.url));
const blank = text => text.replace(/[^\n]/g, ' ');

function withoutFences(text) {
    let fence;
    return text.split('\n').map(line => {
        const match = line.match(/^ {0,3}(`{3,}|~{3,})/);
        if (!fence && match) { fence = match[1]; return blank(line); }
        if (fence) {
            if (new RegExp(`^ {0,3}${fence[0]}{${fence.length},}\\s*$`).test(line)) fence = undefined;
            return blank(line);
        }
        return line;
    }).join('\n');
}

function withoutCode(text) {
    return withoutFences(text).replace(/(`+)([\s\S]*?)\1(?!`)/g, blank)
        .replace(/<!--([\s\S]*?)-->/g, blank);
}

function plain(text) {
    return text.replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
        .replace(/<[^>]*>/g, '').replace(/\\([\p{P}\p{S}])/gu, '$1')
        .replace(/&(?:amp|lt|gt|quot|apos|#\d+|#x[\da-f]+);/gi, entity => {
            const named = { '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&apos;': "'" };
            if (named[entity]) return named[entity];
            const number = entity.slice(2, -1);
            const point = number[0].toLowerCase() === 'x' ? parseInt(number.slice(1), 16) : Number(number);
            return point <= 0x10ffff ? String.fromCodePoint(point) : entity;
        }).replace(/[*~`]/g, '');
}

export function anchors(text) {
    const result = new Set();
    const source = withoutFences(text).replace(/<!--[\s\S]*?-->/g, blank);
    const lines = source.split('\n');
    for (let i = 0; i < lines.length; i++) {
        const heading = lines[i].match(/^ {0,3}#{1,6}\s+(.+?)(?:\s+#+)?\s*$/);
        const setext = i + 1 < lines.length && /^ {0,3}(?:=+|-+)\s*$/.test(lines[i + 1]);
        if (!heading && !(setext && lines[i].trim() && !/^\s*[|>*-]/.test(lines[i]))) continue;
        const base = plain(heading ? heading[1] : lines[i]).trim().toLowerCase()
            .replace(/[^\p{L}\p{M}\p{N}_\-\s]/gu, '').replace(/\s/g, '-');
        let slug = base;
        let number = 0;
        while (result.has(slug)) slug = `${base}-${++number}`;
        result.add(slug);
        if (!heading) i++;
    }
    for (const match of source.matchAll(/<(?:[\w-]+)\b[^>]*\b(?:id|name)=["']([^"']+)["'][^>]*>/g)) result.add(match[1]);
    return result;
}

const referenceKey = label => label.replace(/\\([\[\]\\])/g, '$1').trim().replace(/\s+/g, ' ').toLowerCase();

function destination(text) {
    const match = text.trim().match(/^(?:<([^>\n]*)>|((?:\\.|[^\s])+?))(?=\s+["'(]|\s*$)/);
    return match ? (match[1] ?? match[2]).replace(/\\([\p{P}\p{S}])/gu, '$1') : undefined;
}

/** 提取行内、图片、引用式及本地尖括号链接；不解析 MDX、模板和 HTML href。 */
export function links(text) {
    const definitions = new Map();
    let source = withoutCode(text);
    source = source.replace(/^ {0,3}\[([^\]\n]+)\]:[ \t]*(.+)$/gm, (line, label, value) => {
        const target = destination(value);
        if (target !== undefined) definitions.set(referenceKey(label), target);
        return blank(line);
    });
    const result = [];
    const add = (target, offset, missing) => result.push({ target, line: source.slice(0, offset).split('\n').length, missing });
    const labels = /!?\[((?:\\.|[^\[\]\\]|\[[^\]\n]*\])*)\]/g;
    for (let match; (match = labels.exec(source));) {
        const end = labels.lastIndex;
        if (source[end] === '(') {
            let depth = 1;
            let angle = false;
            let cursor = end + 1;
            for (; cursor < source.length && depth; cursor++) {
                const char = source[cursor];
                if (char === '\\') { cursor++; continue; }
                if (char === '<') angle = true;
                if (char === '>') angle = false;
                if (!angle && char === '(') depth++;
                if (!angle && char === ')') depth--;
            }
            if (depth === 0) {
                const target = destination(source.slice(end + 1, cursor - 1));
                if (target !== undefined) add(target, match.index);
                labels.lastIndex = cursor;
            }
        } else {
            const explicit = source.slice(end).match(/^\[([^\]\n]*)\]/);
            const key = referenceKey(explicit?.[1] || match[1]);
            if (definitions.has(key)) add(definitions.get(key), match.index);
            else if (explicit) add(key, match.index, true);
            if (explicit) labels.lastIndex += explicit[0].length;
        }
    }
    for (const match of source.matchAll(/<((?:\.\.?\/|#)[^<>\s]+)>/g)) add(match[1], match.index);
    return result;
}

function markdownFiles(directory) {
    return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
        const full = path.join(directory, entry.name);
        return entry.isDirectory() ? markdownFiles(full) : entry.isFile() && entry.name.endsWith('.md') ? [full] : [];
    });
}

export function defaultFiles(root = repoRoot, workspace = false) {
    const files = ['AGENTS.md', 'README.md'].map(file => path.join(root, file));
    files.push(...markdownFiles(path.join(root, '.agents')));
    if (workspace) files.push(...['AGENTS.md', '文档导航.md', '项目结构与AI协作评估.md'].map(file => path.resolve(root, '..', file)));
    return files;
}

export function checkFiles(files, { root, encodingOnly = false } = {}) {
    const issues = [];
    let linkCount = 0;
    let anchorCount = 0;
    let workspaceLinks = 0;
    const cache = new Map();
    const read = file => {
        if (!cache.has(file)) cache.set(file, new TextDecoder('utf-8', { fatal: true }).decode(readFileSync(file)));
        return cache.get(file);
    };
    for (const file of new Set(files.map(file => path.resolve(file)))) {
        let text;
        try {
            const bytes = readFileSync(file);
            text = read(file);
            if (bytes.subarray(0, 3).equals(Buffer.from([0xef, 0xbb, 0xbf])) || text.includes('\r') || !text.endsWith('\n')) {
                issues.push(`${file}: 要求 UTF-8 无 BOM、LF、末尾换行`);
            }
        } catch (error) { issues.push(`${file}: ${error.message}`); continue; }
        if (encodingOnly || !file.toLowerCase().endsWith('.md')) continue;
        for (const link of links(text)) {
            const context = `${file}:${link.line}: ${link.target}`;
            if (link.missing) { issues.push(`${context}: 引用定义缺失`); continue; }
            // 外部 URL、mailto、plugin 等只跳过，不作网络可用性声明。
            if (/^[a-z][a-z\d+.-]*:/i.test(link.target) || link.target.startsWith('//')) continue;
            linkCount++;
            try {
                const hash = link.target.indexOf('#');
                const location = (hash < 0 ? link.target : link.target.slice(0, hash)).split('?')[0];
                const target = location ? path.resolve(path.dirname(file), decodeURIComponent(location)) : file;
                if (root) {
                    const relative = path.relative(path.resolve(root), target);
                    if (relative === '..' || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) {
                        workspaceLinks++;
                        continue;
                    }
                }
                if (!statSync(target).isFile() && !statSync(target).isDirectory()) throw new Error('目标不是文件或目录');
                if (hash >= 0 && link.target.slice(hash + 1)) {
                    anchorCount++;
                    if (!target.toLowerCase().endsWith('.md')) continue;
                    const fragment = decodeURIComponent(link.target.slice(hash + 1));
                    if (!anchors(read(target)).has(fragment)) throw new Error(`锚点不存在：${fragment}`);
                }
            } catch (error) { issues.push(`${context}: ${error.message}`); }
        }
    }
    return { files: new Set(files.map(file => path.resolve(file))).size, linkCount, anchorCount, workspaceLinks, issues };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const args = process.argv.slice(2);
    if (args.includes('--help')) {
        console.log('node scripts/check-docs.mjs [--workspace] [--encoding-only] [文件 ...]\n默认检查仓库 AGENTS.md、README.md 和 .agents/**/*.md，仅检查仓库内目标，仓库外链接跳过并计数。\n--workspace 再加入工作区入口、导航和评估，检查仓库外目标。显式文件替代默认清单，并检查其全部本地目标；非 Markdown 文件仅检查编码。\n--encoding-only 对全部输入仅检查编码。外部 URL 不联网；不解析 MDX、模板、HTML href 或标题扩展语法。');
    } else {
        try {
            const options = new Set(['--workspace', '--encoding-only']);
            if (args.some(arg => arg.startsWith('--') && !options.has(arg))) throw new Error('未知选项，使用 --help 查看用法');
            const names = args.filter(arg => !options.has(arg));
            const workspace = args.includes('--workspace');
            const result = checkFiles(names.length ? names : defaultFiles(repoRoot, workspace), {
                root: !names.length && !workspace ? repoRoot : undefined,
                encodingOnly: args.includes('--encoding-only'),
            });
            for (const issue of result.issues) console.error(issue);
            console.log(`文件 ${result.files}；本地链接 ${result.linkCount}；锚点 ${result.anchorCount}；问题 ${result.issues.length}`);
            if (result.workspaceLinks) console.log(`仓库外链接未检查 ${result.workspaceLinks}；完整工作区请加 --workspace。`);
            process.exitCode = result.issues.length ? 1 : 0;
        } catch (error) { console.error(error.message); process.exitCode = 1; }
    }
}
