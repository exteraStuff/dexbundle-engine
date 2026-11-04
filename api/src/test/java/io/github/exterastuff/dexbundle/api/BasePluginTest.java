package io.github.exterastuff.dexbundle.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.lang.reflect.Member;

import de.robv.android.xposed.XC_MethodHook;

public class BasePluginTest {
    private static final PluginLogger LOGGER = new PluginLogger() {
        public void debug(@NotNull String m) {
        }

        public void info(@NotNull String m) {
        }

        public void warn(@NotNull String m) {
        }

        public void error(@NotNull String m) {
        }
    };

    // Объекты Android/Xposed не нужны: BasePlugin использует только getLogger.
    private static final PluginContext CONTEXT = new PluginContext() {
        public @NotNull PluginLogger getLogger() {
            return LOGGER;
        }

        public void unloadSelf(Throwable e) {
        }

        public <T, I extends T> void registerService(@NotNull Class<T> c, @NotNull I i) {
        }

        public <T> T getService(@NotNull Class<T> c) {
            return null;
        }

        public @NotNull XC_MethodHook.Unhook installHook(@NotNull Member m, @NotNull XC_MethodHook h) {
            //noinspection DataFlowIssue
            return null;
        }

        public boolean removeHook(XC_MethodHook.@NotNull Unhook u) {
            return false;
        }
    };

    private static class Plugin extends BasePlugin {
        int loads;
        int unloads;
        boolean failLoad;
        boolean failUnload;

        protected void onLoad() {
            loads++;

            assertSame(CONTEXT, context);
            assertSame(LOGGER, logger);

            if (failLoad)
                throw new IllegalArgumentException("load");
        }

        protected void onUnload() {
            unloads++;

            assertSame(CONTEXT, context);
            assertSame(LOGGER, logger);

            if (failUnload)
                throw new IllegalArgumentException("unload");
        }
    }

    @Test
    public void lifecycleAndGuards() {
        Plugin p = new Plugin();

        assertThrows(IllegalStateException.class, p::unload);

        p.load(CONTEXT);

        assertThrows(IllegalStateException.class, () -> p.load(CONTEXT));
        p.onDependencyUnload("dependency");
        p.unload();

        assertEquals(1, p.loads);
        assertEquals(1, p.unloads);
        assertNull(p.context);
        assertNull(p.logger);
        assertThrows(IllegalStateException.class, p::unload);
        assertThrows(IllegalStateException.class, () -> p.load(CONTEXT));
    }

    @Test
    public void failedLoadCanBeCleanedUpButNeverRetried() {
        Plugin p = new Plugin();
        p.failLoad = true;

        assertThrows(IllegalArgumentException.class, () -> p.load(CONTEXT));
        assertThrows(IllegalStateException.class, () -> p.load(CONTEXT));

        p.unload();

        assertNull(p.context);
        assertNull(p.logger);
    }

    @Test
    public void failedUnloadStillClearsReferencesAndCannotBeRetried() {
        Plugin p = new Plugin();
        p.load(CONTEXT);
        p.failUnload = true;

        assertThrows(IllegalArgumentException.class, p::unload);
        assertNull(p.context);
        assertNull(p.logger);
        assertThrows(IllegalStateException.class, p::unload);
    }
}
