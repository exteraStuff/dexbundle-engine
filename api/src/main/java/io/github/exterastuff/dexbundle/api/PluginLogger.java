package io.github.exterastuff.dexbundle.api;

import org.jetbrains.annotations.NotNull;

public interface PluginLogger {
    void debug(@NotNull String message);

    void info(@NotNull String message);

    void warn(@NotNull String message);

    void error(@NotNull String message);
}
