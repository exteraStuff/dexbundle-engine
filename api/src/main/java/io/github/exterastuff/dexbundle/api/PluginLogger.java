package io.github.exterastuff.dexbundle.api;

public interface PluginLogger {
    void debug(String message);

    void info(String message);

    void warn(String message);

    void error(String message);
}
