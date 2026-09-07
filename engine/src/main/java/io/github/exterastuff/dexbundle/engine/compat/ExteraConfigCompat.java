package io.github.exterastuff.dexbundle.engine.compat;

import android.content.SharedPreferences;

import com.exteragram.messenger.ExteraConfig;

public final class ExteraConfigCompat {
    private ExteraConfigCompat() {
    }

    public static SharedPreferences getPreferences() {
        return ExteraConfig.getPreferences();
    }

    public static SharedPreferences.Editor getEditor() {
        return ExteraConfig.getEditor();
    }

    public static boolean isPluginsSafeMode() {
        return ExteraConfig.getPluginsSafeMode();
    }

    public static boolean isPluginsDevMode() {
        return ExteraConfig.getPluginsDevMode();
    }
}
