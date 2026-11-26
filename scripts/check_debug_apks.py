"""核对本地 ARM64 / GitHub 完整架构的 Debug 附件，拒绝空包或漏包。"""

import json
import os
from pathlib import Path
from zipfile import ZipFile


def main():
    """检查 AGP 元数据和 APK 内实际原生库，不能只看构建任务退出码。"""
    root = Path(__file__).resolve().parents[1]
    output = root / "app/build/outputs/apk/debug"
    metadata = json.loads((output / "output-metadata.json").read_text(encoding="utf-8"))
    remote = os.environ.get("GITHUB_ACTIONS") == "true"
    abis = {"arm64-v8a", "armeabi-v7a", "x86_64"} if remote else {"arm64-v8a"}
    expected = abis | {"universal"} if remote else abis
    assert metadata["applicationId"] == "com.haorengg12.ehreader.debug"
    found = set()
    for item in metadata["elements"]:
        abi = next((f["value"] for f in item["filters"] if f["filterType"] == "ABI"), "universal")
        assert abi in expected and abi not in found, f"Unexpected/duplicate ABI: {abi}"
        found.add(abi)
        name = item["outputFile"]
        assert Path(name).name == name
        with ZipFile(output / name) as apk:
            native = {p.split("/")[1] for p in apk.namelist() if p.startswith("lib/") and p.endswith(".so")}
        assert native == (abis if abi == "universal" else {abi}), f"Native ABI mismatch: {name}"
    assert found == expected, f"Missing APK: {expected - found}"
    print(f"Debug APK architectures verified: {', '.join(sorted(found))}")


if __name__ == "__main__":
    main()
