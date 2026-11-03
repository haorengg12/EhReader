"""检查当前规范文本是否符合 UTF-8 无 BOM、LF 约定。"""

import sys
from pathlib import Path


def main():
    """检查指定文件或默认规范文件，发现编码或换行问题时返回失败。"""
    root = Path(__file__).resolve().parents[1]
    defaults = [
        "AGENTS.md", "README.md", ".editorconfig", "docs/CODING_STANDARDS.md",
        "docs/ARCHITECTURE.md", "docs/TESTING_STANDARDS.md",
        "docs/STANDARDS_README.md", "docs/DEVELOPMENT_FLOW.md",
        "docs/LOCAL_DEVELOPMENT.md", "docs/REPOSITORY_WORKFLOW.md",
        "docs/RELEASE_POLICY.md",
        "scripts/check_standards_encoding.py",
    ]
    paths = [Path(p) for p in sys.argv[1:]] if sys.argv[1:] else [root / p for p in defaults]
    failures = []
    for path in paths:
        try:
            data = path.read_bytes()
            data.decode("utf-8", errors="strict")
            if data.startswith(b"\xef\xbb\xbf") or b"\r" in data or not data.endswith(b"\n"):
                raise ValueError("要求 UTF-8 无 BOM、LF 且文件末尾有换行")
        except (OSError, UnicodeError, ValueError) as error:
            failures.append(f"{path}: {error}")
    for failure in failures:
        print(failure)
    print(f"Checked {len(paths)} files; failures: {len(failures)}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
