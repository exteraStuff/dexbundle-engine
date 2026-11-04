import base64
import contextlib
import importlib.util
import io
import lzma
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


embed = load("embed_dex", "tools/embed_dex.py")
release = load("prepare_release", "scripts/prepare_release.py")
SOURCE = 'before = "Привет"\n' + embed.BEGIN + "\n# old\n" + embed.END + "\nafter = 1\n"


class EmbedTests(unittest.TestCase):
    def test_roundtrip_empty_binary_and_multiline(self):
        for data in (b"", b"dex\x00\xff", bytes(range(256)) * 100):
            with self.subTest(size=len(data)):
                result = embed.embed_source(SOURCE, data)
                lines = result.splitlines()
                payload = lines[lines.index(embed.BEGIN) + 1 : lines.index(embed.END)]
                self.assertTrue(
                    all(line.startswith("# ") and len(line) <= 122 for line in payload)
                )
                self.assertEqual(
                    data,
                    lzma.decompress(
                        base64.b64decode("".join(line[2:] for line in payload))
                    ),
                )
                self.assertTrue(result.startswith('before = "Привет"\n'))
                self.assertTrue(result.endswith("after = 1\n"))
                self.assertEqual(result, embed.embed_source(result, data))
                compile(result, "embedded.py", "exec")

    def test_markers_missing_and_reversed(self):
        for source in ("", embed.BEGIN, embed.END, embed.END + "\n" + embed.BEGIN):
            with self.subTest(source=source), self.assertRaises(ValueError):
                embed.embed_source(source, b"dex")

    def test_indented_crlf_markers(self):
        result = embed.embed_source("  " + embed.BEGIN + "\r\n  " + embed.END, b"dex")
        self.assertIn("# /Td6", result)
        self.assertTrue(result.endswith("\n"))

    def test_cli_output_inplace_usage_and_invalid_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            dex, source, output = [
                Path(tmp) / name for name in ("classes.dex", "source.py", "output.py")
            ]
            dex.write_bytes(b"dex")
            source.write_text(SOURCE)
            for argv in (
                ["embed", str(dex), str(source), str(output)],
                ["embed", str(dex), str(source)],
            ):
                with (
                    patch.object(sys, "argv", argv),
                    contextlib.redirect_stdout(io.StringIO()),
                ):
                    self.assertEqual(0, embed.main())
            self.assertEqual(source.read_text(), output.read_text())
            source.write_text("invalid")
            output.write_text("keep")
            with (
                patch.object(
                    sys, "argv", ["embed", str(dex), str(source), str(output)]
                ),
                contextlib.redirect_stderr(io.StringIO()),
            ):
                self.assertEqual(1, embed.main())
            self.assertEqual("keep", output.read_text())
        with (
            patch.object(sys, "argv", ["embed"]),
            contextlib.redirect_stderr(io.StringIO()),
        ):
            self.assertEqual(2, embed.main())


class ReleaseTests(unittest.TestCase):
    def test_updates_only_version_and_preserves_unicode(self):
        with tempfile.TemporaryDirectory() as tmp:
            for function, key in (
                (release.update_plugin_file, "__version__"),
                (release.update_pyproject_file, "version"),
            ):
                path = Path(tmp) / key
                path.write_text(f'# Привет\n{key} = "0.0.0"\nother = "0.0.0"\n')
                function(path, "1.2.3")
                self.assertEqual(
                    f'# Привет\n{key} = "1.2.3"\nother = "0.0.0"\n', path.read_text()
                )
                path.write_text("missing")
                with self.assertRaises(RuntimeError):
                    function(path, "1.2.3")
                self.assertEqual("missing", path.read_text())

    def test_cli_validation_and_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            plugin, project = Path(tmp) / "plugin.py", Path(tmp) / "pyproject.toml"
            plugin.write_text('__version__ = "0"')
            project.write_text('version = "0"')

            def run(version):
                with (
                    patch.object(
                        sys,
                        "argv",
                        [
                            "release",
                            "--version",
                            version,
                            "--plugin-file",
                            str(plugin),
                            "--pyproject-file",
                            str(project),
                        ],
                    ),
                    contextlib.redirect_stdout(io.StringIO()),
                ):
                    release.main()

            for version in ("1", "1.2", "v1.2.3", "1.2.3-beta", "1.2.3\n"):
                with self.subTest(version=version), self.assertRaises(SystemExit):
                    run(version)
            self.assertEqual('__version__ = "0"', plugin.read_text())
            run("1.2.3")
            self.assertEqual('__version__ = "1.2.3"', plugin.read_text())
            project.unlink()
            with self.assertRaises(SystemExit):
                run("2.0.0")
            self.assertEqual('__version__ = "1.2.3"', plugin.read_text())
            plugin.unlink()
            with self.assertRaises(SystemExit):
                run("2.0.0")


class WatchTests(unittest.TestCase):
    def setUp(self):
        client = types.ModuleType("exteragram_utils.dev_client")
        client.AdbManager = MagicMock()
        client.DeviceConnection = MagicMock()
        client.parse_metadata = MagicMock()
        with patch.dict(sys.modules, {"exteragram_utils.dev_client": client}):
            self.watch = load("dev_watch", "tools/dev_watch.py")
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.source, self.dex, self.output = [
            Path(self.tmp.name) / name for name in ("p.py", "classes.dex", "out.py")
        ]
        self.source.write_text(SOURCE)
        self.dex.write_bytes(b"dex")

    def test_build_and_missing_files(self):
        content = self.watch._build_temp(
            str(self.source), str(self.dex), str(self.output)
        )
        self.assertEqual(embed.embed_source(SOURCE, b"dex"), content)
        self.assertEqual(content, self.output.read_text())
        self.assertIsNotNone(self.watch._mtime(str(self.dex)))
        self.dex.unlink()
        self.assertIsNone(self.watch._mtime(str(self.dex)))
        with self.assertLogs("watch", level="ERROR"):
            self.assertIsNone(
                self.watch._build_temp(
                    str(self.source), str(self.dex), str(self.output)
                )
            )
        self.assertEqual(content, self.output.read_text())

    def run_watch(self):
        with patch.object(sys, "argv", ["watch", str(self.source), str(self.dex)]):
            return self.watch.main()

    def test_setup_failures(self):
        self.watch.parse_metadata.return_value = None
        with self.assertLogs("watch", level="ERROR"):
            self.assertEqual(1, self.run_watch())
        self.watch.AdbManager.assert_not_called()
        self.watch.parse_metadata.return_value = types.SimpleNamespace(id="plugin")
        self.watch.AdbManager.return_value.setup_device.return_value = False
        with self.assertLogs("watch", level="ERROR"):
            self.assertEqual(1, self.run_watch())
        self.watch.DeviceConnection.assert_not_called()

    def test_upload_reload_and_cleanup_after_interrupt(self):
        self.watch.parse_metadata.return_value = types.SimpleNamespace(id="plugin")
        connection = self.watch.DeviceConnection.return_value
        with (
            patch.object(
                self.watch.time, "sleep", side_effect=[None, KeyboardInterrupt]
            ),
            patch.object(self.watch.tempfile, "mkdtemp", return_value=self.tmp.name),
        ):
            self.assertEqual(0, self.run_watch())
        connection.write_plugin.assert_called_once_with(
            "plugin", embed.embed_source(SOURCE, b"dex")
        )
        connection.reload_plugin.assert_called_once_with("plugin")
        connection.stop_debugger.assert_called_once()
        connection.disconnect.assert_called_once()

    def test_failed_upload_does_not_reload(self):
        self.watch.parse_metadata.return_value = types.SimpleNamespace(id="plugin")
        connection = self.watch.DeviceConnection.return_value
        connection.write_plugin.return_value = False
        with (
            patch.object(self.watch.time, "sleep", side_effect=KeyboardInterrupt),
            patch.object(self.watch.tempfile, "mkdtemp", return_value=self.tmp.name),
            self.assertLogs("watch"),
        ):
            self.assertEqual(0, self.run_watch())
        connection.reload_plugin.assert_not_called()
        connection.disconnect.assert_called_once()

    def test_missing_dex_waits_without_upload(self):
        self.watch.parse_metadata.return_value = types.SimpleNamespace(id="plugin")
        self.dex.unlink()
        with (
            patch.object(self.watch.time, "sleep", side_effect=KeyboardInterrupt),
            patch.object(self.watch.tempfile, "mkdtemp", return_value=self.tmp.name),
            self.assertLogs("watch"),
        ):
            self.assertEqual(0, self.run_watch())
        self.watch.DeviceConnection.return_value.write_plugin.assert_not_called()

    def test_unchanged_files_are_not_uploaded_twice(self):
        self.watch.parse_metadata.return_value = types.SimpleNamespace(id="plugin")
        # Пишем результат в отдельную временную папку, как это делает настоящий watcher.
        out = Path(self.tmp.name) / "watch"
        out.mkdir()
        with (
            patch.object(
                self.watch.time, "sleep", side_effect=[None, None, KeyboardInterrupt]
            ),
            patch.object(self.watch.tempfile, "mkdtemp", return_value=str(out)),
            self.assertLogs("watch"),
        ):
            self.assertEqual(0, self.run_watch())
        self.watch.DeviceConnection.return_value.write_plugin.assert_called_once()
