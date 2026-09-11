#!/usr/bin/env python3
"""比较完整发行目录；只有宿主完全兼容时才能暂存独立业务包，不调用服务或数据库。"""
import argparse
import hashlib
import json
from pathlib import Path
import os
import re
import tempfile
import zipfile

MODULES = ("game-logic", "login-logic", "admin-logic", "query-logic", "coordinator-logic")


def jar_entries(path):
    with zipfile.ZipFile(path) as jar:
        names = [item.filename for item in jar.infolist() if not item.is_dir()]
        if len(names) != len(set(names)):
            raise ValueError(f"JAR 存在重复条目：{path}")
        # ZIP 时间不影响代码；模块 Maven 构建时间也不是运行内容。
        return {name: hashlib.sha256(jar.read(name)).hexdigest() for name in names
                if not (name.startswith("META-INF/maven/") and name.endswith("/pom.properties"))}


def metadata(path):
    with zipfile.ZipFile(path) as jar:
        text = jar.read("META-INF/twinkle-module.properties").decode("ascii")
    return dict(line.split("=", 1) for line in text.splitlines() if line and not line.startswith("#") and "=" in line)


def active_module(installed, module):
    pointer = installed / "logic" / ".cache" / module / "active"
    if not pointer.exists():
        return installed / "logic" / f"{module}.jar"
    digest = pointer.read_text().strip()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError(f"启动记录损坏：{module}")
    artifact = pointer.with_name(digest + ".jar")
    if hashlib.sha256(artifact.read_bytes()).hexdigest() != digest:
        raise ValueError(f"缓存内容与摘要不符：{module}")
    return artifact


def compare(installed, candidate):
    old_host, new_host = jar_entries(installed / "twinkle-server.jar"), jar_entries(candidate / "twinkle-server.jar")
    changes = sorted(name for name in old_host.keys() | new_host.keys() if old_host.get(name) != new_host.get(name))
    modules, incompatible = [], []
    for module in MODULES:
        old, new = active_module(installed, module), candidate / "logic" / f"{module}.jar"
        old_meta, new_meta = metadata(old), metadata(new)
        if any(old_meta.get(key) != new_meta.get(key) for key in ("module", "contracts", "hostContracts", "package", "fingerprint")):
            incompatible.append(module)
        # 元数据时间戳变化不应变成一次虚假的业务发布。
        before, after = jar_entries(old), jar_entries(new)
        before.pop("META-INF/twinkle-module.properties", None)
        after.pop("META-INF/twinkle-module.properties", None)
        if before != after:
            modules.append(module)
    return {"canHotReload": not changes and not incompatible, "changedModules": modules,
            "incompatibleModules": incompatible, "hostChanges": changes}


def stage(installed, candidate, modules):
    incoming = installed / "logic" / "incoming"
    incoming.mkdir(parents=True, exist_ok=True)
    for module in modules:
        source = candidate / "logic" / f"{module}.jar"
        handle, temporary = tempfile.mkstemp(prefix=f"{module}-", suffix=".tmp", dir=incoming)
        try:
            with os.fdopen(handle, "wb") as output:
                output.write(source.read_bytes())
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, incoming / f"{module}.jar")
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("installed", type=Path, help="已部署发行目录，包含主 JAR 与 logic/")
    parser.add_argument("candidate", type=Path, help="本次完整构建目录，包含主 JAR 与 logic/")
    parser.add_argument("--stage", action="store_true", help="检查通过后暂存候选业务包；不会触发运行中的切换")
    args = parser.parse_args()
    result = compare(args.installed.resolve(), args.candidate.resolve())
    if args.stage and result["canHotReload"]:
        stage(args.installed.resolve(), args.candidate.resolve(), result["changedModules"])
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["canHotReload"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
