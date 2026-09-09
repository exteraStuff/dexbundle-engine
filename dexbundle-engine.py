# fmt: off

import base64
import lzma
import os
import threading
import traceback
from typing import Any, Optional, cast

from android.util import Log
from android_utils import copy_to_clipboard
from base_plugin import BasePlugin
from dalvik.system import InMemoryDexClassLoader
from java.lang import Class, String
from java.nio import ByteBuffer
from org.telegram.messenger import ApplicationLoader

__id__ = "dexbundle-engine"
__name__ = "DexBundle Engine"
__description__ = "PoC движка плагинов принимающего на вход JAR с DEX и META-INF внутри"
__author__ = "@n08i40k_extera_dev"
__version__ = "0.0.0"
__icon__ = "PumpkinEmoji/9"
__min_version__ = "12.9.0"

LOGCAT_TAG = __id__

JVM_PLUGIN_CLASS = "io.github.exterastuff.dexbundle.engine.Plugin"

DEX_COMMENT_BEGIN = "# === EMBEDDED DEX BEGIN ==="
DEX_COMMENT_END = "# === EMBEDDED DEX END ==="

# fmt: on


class JvmPluginBridge:
    klass: Optional[Class]

    def __init__(self, plugin: "DexEnginePlugin"):
        self.plugin = plugin
        self.klass = None

    def load(self):
        dex_data = self._read_embedded_dex()
        if dex_data is None:
            self.plugin.log("Embedded DEX is unavailable; plugin will not load")
            return

        try:
            loader = InMemoryDexClassLoader(
                ByteBuffer.wrap(dex_data),  # ty:ignore[invalid-argument-type]
                ApplicationLoader.applicationContext.getClassLoader(),
            )
            self.klass = loader.loadClass(String(JVM_PLUGIN_CLASS))
        except Exception as e:
            self.plugin.log_exception("Failed to load DEX", e)

    def call(self, name: str, *args: Any, types: tuple = ()) -> Any:
        if self.klass is None:
            raise RuntimeError(f"cannot call {name}: JVM plugin is not loaded")

        return self.klass.getDeclaredMethod(String(name), *types).invoke(None, *args)

    def _read_own_source(self) -> Optional[str]:
        candidates: list[str] = []

        own_file = globals().get("__file__")
        if isinstance(own_file, str) and own_file:
            candidates.append(own_file)

        plugins_dir_getter = globals().get("get_plugins_dir")
        if callable(plugins_dir_getter):
            try:
                candidates.append(os.path.join(plugins_dir_getter(), f"{__id__}.py"))
            except Exception as e:
                self.plugin.log_exception("Failed to resolve plugins directory", e)

        for path in candidates:
            try:
                with open(path, "r", encoding="utf-8") as f:
                    return f.read()
            except Exception as e:
                self.plugin.log_exception(f"Failed to read plugin source at {path}", e)

        return None

    def _read_embedded_dex(self) -> Optional[bytes]:
        source = self._read_own_source()
        if source is None:
            self.plugin.log("Failed to read plugin source for embedded DEX")
            return None

        payload = bytearray()
        decompressor = lzma.LZMADecompressor()
        collecting = False
        completed = False

        try:
            for line in source.splitlines():
                stripped = line.strip()

                if not collecting:
                    collecting = stripped == DEX_COMMENT_BEGIN
                    continue

                if stripped == DEX_COMMENT_END:
                    completed = True
                    break

                if stripped.startswith("#"):
                    chunk = base64.b64decode(stripped[1:].strip())
                    payload += decompressor.decompress(chunk)
        except (ValueError, lzma.LZMAError) as e:
            self.plugin.log_exception("Failed to decode embedded DEX", e)
            return None

        if completed and not decompressor.eof:
            self.plugin.log("Embedded DEX payload is truncated")
            return None

        if not completed or not payload:
            self.plugin.log("Embedded DEX payload is empty")
            return None

        return bytes(payload)


class DexEnginePlugin(BasePlugin):
    _full_load_lock = threading.Lock()
    _eject_lock = threading.Lock()

    _full_load_started = False
    _ejected = False

    jvm_plugin: JvmPluginBridge

    def log(self, message: Any):
        text = str(message)
        super().log(text)

        try:
            Log.i(cast("String", LOGCAT_TAG), cast("String", text))
        except Exception:
            pass

    def log_exception(self, message: str, exception: BaseException):
        self.log(f"{message}: {exception}")

        for chunk in traceback.format_exception(
            type(exception),
            exception,
            exception.__traceback__,
        ):
            for line in chunk.rstrip().splitlines():
                if line:
                    self.log(line)

    def _prepare_jvm_plugin(self) -> bool:
        self.jvm_plugin = JvmPluginBridge(self)
        self.jvm_plugin.load()

        return self.jvm_plugin.klass is not None

    def _inject_jvm_plugin(self):
        try:
            self.log(f"Loading JVM plugin {self.jvm_plugin.call('getBuildDate')}")
        except Exception as e:
            self.log_exception("Failed to infer JVM plugin version", e)

        self.jvm_plugin.call(
            "inject",
            String(__version__),
            types=tuple([String.getClass()]),
        )
        self.log("JVM plugin injected successfully")

    def _run_plugin_load(self):
        with self._full_load_lock:
            if self._full_load_started:
                return
            self._full_load_started = True

        if not self._prepare_jvm_plugin():
            with self._full_load_lock:
                self._full_load_started = False
            return

        try:
            self._inject_jvm_plugin()
        except BaseException as e:
            self.log_exception("Plugin load failed", e)

            report = (
                f"#crash_report\n\n"
                f"{__name__} inject failed!\n"
                f"Plugin version: `{__version__}`\n\n"
                f"Error:\n```\n{e}\n```"
            )

            try:
                copy_to_clipboard(report)
            except Exception as e:
                self.log_exception("Failed to copy load-crash report to clipboard", e)

    def on_plugin_load(self):
        self._ejected = False
        self._full_load_started = False

        thread = threading.Thread(
            target=self._run_plugin_load,
            name=f"{__id__}-continue-plugin-load",
            daemon=True,
        )
        thread.start()

        return thread

    def on_plugin_unload(self):
        jvm_plugin = getattr(self, "jvm_plugin", None)

        if jvm_plugin is None or jvm_plugin.klass is None:
            return

        try:
            jvm_plugin.call("eject")
            self.log("JVM plugin ejected successfully")
        except Exception as e:
            self.log_exception("Failed to eject JVM plugin", e)

        jvm_plugin.klass = None

    def on_plugin_eject(self):
        with self._eject_lock:
            if self._ejected:
                return
            self._ejected = True

        self.log("JVM plugin instance lost: ejected by a concurrent reload")

        jvm_plugin = getattr(self, "jvm_plugin", None)

        if jvm_plugin is not None:
            jvm_plugin.klass = None


# === EMBEDDED DEX BEGIN ===
# === EMBEDDED DEX END ===
