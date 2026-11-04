import base64
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from test_tools import SOURCE, embed, load


def bridge_module():
    names = {
        "android.util": ["Log"],
        "android_utils": ["copy_to_clipboard"],
        "base_plugin": ["BasePlugin"],
        "dalvik.system": ["InMemoryDexClassLoader"],
        "java.lang": ["Class", "String"],
        "java.nio": ["ByteBuffer"],
        "org.telegram.messenger": ["ApplicationLoader"],
    }
    modules = {}
    for name, attrs in names.items():
        module = types.ModuleType(name)
        for attr in attrs:
            setattr(
                module,
                attr,
                type("BasePlugin", (), {"log": lambda *args: None})
                if attr == "BasePlugin"
                else MagicMock(),
            )
        modules[name] = module
    with patch.dict(sys.modules, modules):
        return load("bridge_fixture", "dexbundle-engine.py")


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.module = bridge_module()
        self.plugin = MagicMock()
        self.bridge = self.module.JvmPluginBridge(self.plugin)

    def decode(self, source):
        with patch.object(self.bridge, "_read_own_source", return_value=source):
            return self.bridge._read_embedded_dex()

    def test_real_embedder_roundtrip(self):
        for data in (b"dex\x00\xff", bytes(range(256)) * 1000):
            self.assertEqual(data, self.decode(embed.embed_source(SOURCE, data)))

    def test_missing_empty_corrupt_and_truncated_payloads(self):
        for source in (
            None,
            "",
            embed.BEGIN + "\n" + embed.END,
            embed.embed_source(SOURCE, b""),
            embed.BEGIN + "\n# !!!\n" + embed.END,
            embed.BEGIN
            + "\n# "
            + base64.b64encode(b"not xz").decode()
            + "\n"
            + embed.END,
            embed.embed_source(SOURCE, b"dex").replace(embed.END, ""),
        ):
            with self.subTest(source=source):
                self.assertIsNone(self.decode(source))
        truncated = base64.b64encode(embed.compress(b"dex")[:-8]).decode()
        self.assertIsNone(
            self.decode(embed.BEGIN + "\n# " + truncated + "\n" + embed.END)
        )

    def test_call_requires_loaded_class_and_forwards_arguments(self):
        with self.assertRaises(RuntimeError):
            self.bridge.call("inject")
        klass = self.bridge.klass = MagicMock()
        self.bridge.call("inject", 123, types=("type",))
        klass.getDeclaredMethod.assert_called_once_with(
            self.module.String("inject"), "type"
        )
        klass.getDeclaredMethod.return_value.invoke.assert_called_once_with(None, 123)

    def test_load_missing_and_classloader_failure(self):
        with patch.object(self.bridge, "_read_embedded_dex", return_value=None):
            self.bridge.load()
        self.module.InMemoryDexClassLoader.assert_not_called()
        self.module.InMemoryDexClassLoader.side_effect = RuntimeError("loader")
        with patch.object(self.bridge, "_read_embedded_dex", return_value=b"dex"):
            self.bridge.load()
        self.assertIsNone(self.bridge.klass)
        self.plugin.log_exception.assert_called_once()

    def test_load_success(self):
        with patch.object(self.bridge, "_read_embedded_dex", return_value=b"dex"):
            self.bridge.load()
        self.module.ByteBuffer.wrap.assert_called_once_with(b"dex")
        self.assertIs(
            self.module.InMemoryDexClassLoader.return_value.loadClass.return_value,
            self.bridge.klass,
        )

    def test_plugin_lifecycle_is_idempotent_and_failed_prepare_can_retry(self):
        plugin = self.module.DexEnginePlugin()
        with (
            patch.object(
                plugin, "_prepare_jvm_plugin", side_effect=[False, True]
            ) as prepare,
            patch.object(plugin, "_inject_jvm_plugin") as inject,
        ):
            plugin._run_plugin_load()
            plugin._run_plugin_load()
            plugin._run_plugin_load()
            self.assertEqual(2, prepare.call_count)
            inject.assert_called_once()
        plugin.jvm_plugin = MagicMock()
        plugin.on_plugin_unload()
        plugin.on_plugin_unload()
        plugin.jvm_plugin.call.assert_called_once_with("eject")
        self.assertIsNone(plugin.jvm_plugin.klass)
        plugin.on_plugin_eject()
        plugin.on_plugin_eject()
        self.assertTrue(plugin._ejected)

    def test_unload_failure_still_drops_class(self):
        plugin = self.module.DexEnginePlugin()
        plugin.jvm_plugin = MagicMock()
        plugin.jvm_plugin.call.side_effect = RuntimeError("eject")
        plugin.on_plugin_unload()
        self.assertIsNone(plugin.jvm_plugin.klass)

    def test_injection_failure_reports_error_without_escaping(self):
        plugin = self.module.DexEnginePlugin()
        with (
            patch.object(plugin, "_prepare_jvm_plugin", return_value=True),
            patch.object(
                plugin, "_inject_jvm_plugin", side_effect=RuntimeError("inject")
            ),
        ):
            plugin._run_plugin_load()
        self.assertIn("#crash_report", self.module.copy_to_clipboard.call_args.args[0])

    def test_source_reading_uses_fallback_and_reports_failures(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / (self.module.__id__ + ".py")
            path.write_text("fallback source")
            with (
                patch.object(self.module, "__file__", str(Path(tmp) / "missing.py")),
                patch.object(
                    self.module, "get_plugins_dir", return_value=tmp, create=True
                ),
            ):
                self.assertEqual("fallback source", self.bridge._read_own_source())
                self.plugin.log_exception.assert_called_once()
            with patch.object(self.module, "__file__", None):
                self.assertIsNone(self.bridge._read_own_source())

    def test_eject_before_load_and_thread_start(self):
        plugin = self.module.DexEnginePlugin()
        plugin.on_plugin_unload()
        plugin.on_plugin_eject()
        with patch.object(self.module.threading, "Thread") as thread:
            result = plugin.on_plugin_load()
        self.assertIs(result, thread.return_value)
        thread.return_value.start.assert_called_once()
        self.assertTrue(thread.call_args.kwargs["daemon"])
        self.assertFalse(plugin._ejected)
        self.assertFalse(plugin._full_load_started)
