/** 失败分类用于有限只读重试；签名、标签、权限和源码变化不能通过重试绕过。 */
export function failureKind(outcome) {
    if (outcome.signal || outcome.code === 130 || outcome.code === 143) return 'cancelled';
    if (['ENOENT', 'EACCES', 'EPERM'].includes(outcome.errorCode)) return 'environment';
    const message = `${outcome.stderr || ''}\n${outcome.stdout || ''}`;
    if (/signing|keystore|certificate mismatch|tag.*conflict|non-fast-forward|permission denied|HTTP 40[13]/i.test(message)) return 'sensitive';
    if (['ETIMEDOUT', 'ECONNRESET', 'EAI_AGAIN'].includes(outcome.errorCode)) return 'transient';
    if (/ETIMEDOUT|ECONNRESET|EAI_AGAIN|HTTP (429|502|503|504)|temporary failure in name resolution/i.test(message)) return 'transient';
    return 'validation';
}

export function retryable(program, args, kind) {
    if (kind !== 'transient') return false;
    return program === process.execPath && ['--test', '--check'].includes(args[0]) ||
        program === 'git' && args[0] === 'diff' ||
        program === 'docker' && (args[0] === 'ps' || args[0] === 'image' && args[1] === 'inspect');
}
