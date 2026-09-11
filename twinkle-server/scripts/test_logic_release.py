"""发行差异检查的隔离测试，只操作临时目录。"""
import importlib.util
import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("logic_release", Path(__file__).with_name("logic-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.installed, self.candidate = [Path(self.temp.name) / name for name in ("installed", "candidate")]
        for root in (self.installed, self.candidate):
            (root / "logic").mkdir(parents=True)
            self.host(root, b"host-v1")
            for name in release.MODULES:
                self.module(root, name, b"logic-v1")

    @staticmethod
    def host(root, content):
        with zipfile.ZipFile(root / "twinkle-server.jar", "w") as jar:
            jar.writestr("org/gms/Host.class", content)

    @staticmethod
    def module(root, name, content, fingerprint="same-contract"):
        with zipfile.ZipFile(root / "logic" / f"{name}.jar", "w") as jar:
            jar.writestr("META-INF/twinkle-module.properties", f"module={name}\ncontracts=Stable\npackage=impl.\nfingerprint={fingerprint}\n")
            jar.writestr("impl/Business.class", content)

    def test_only_business_changes_can_be_staged(self):
        self.module(self.candidate, "game-logic", b"logic-v2")
        result = release.compare(self.installed, self.candidate)
        self.assertTrue(result["canHotReload"])
        self.assertEqual(["game-logic"], result["changedModules"])
        release.stage(self.installed, self.candidate, result["changedModules"])
        self.assertTrue((self.installed / "logic/incoming/game-logic.jar").is_file())
        self.assertEqual(b"logic-v1", zipfile.ZipFile(self.installed / "logic/game-logic.jar").read("impl/Business.class"))

    def test_host_or_contract_changes_require_restart(self):
        self.host(self.candidate, b"host-v2")
        self.module(self.candidate, "login-logic", b"logic-v2", "new-contract")
        result = release.compare(self.installed, self.candidate)
        self.assertFalse(result["canHotReload"])
        self.assertEqual(["org/gms/Host.class"], result["hostChanges"])
        self.assertEqual(["login-logic"], result["incompatibleModules"])

    def test_rollback_compares_against_active_release(self):
        self.module(self.candidate, "game-logic", b"logic-v2")
        content = (self.candidate / "logic/game-logic.jar").read_bytes()
        digest = hashlib.sha256(content).hexdigest()
        cache = self.installed / "logic/.cache/game-logic"
        cache.mkdir(parents=True)
        (cache / "active").write_text(digest)
        (cache / f"{digest}.jar").write_bytes(content)
        self.module(self.candidate, "game-logic", b"logic-v1")
        self.assertEqual(["game-logic"], release.compare(self.installed, self.candidate)["changedModules"])


if __name__ == "__main__":
    unittest.main()
