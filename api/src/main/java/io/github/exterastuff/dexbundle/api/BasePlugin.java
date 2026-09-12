package io.github.exterastuff.dexbundle.api;

public abstract class BasePlugin {
    /**
     * Защита от случайного вызова метода BasePlugin::load.
     * <p>
     * Движок вызывает этот метод ровно один раз на инстанс плагина, поэтому проверка по флагу
     * срабатывает только тогда, когда плагин вызвал метод сам.
     */
    private volatile boolean WAS_LOADED = false;

    /**
     * Защита от повторного вызова метода BasePlugin::unload.
     * <p>
     * Движок вызывает этот метод ровно один раз на инстанс плагина, поэтому проверка по флагу
     * срабатывает только тогда, когда плагин вызвал метод сам.
     */
    private volatile boolean WAS_UNLOADED = false;

    protected PluginContext context;
    protected PluginLogger logger;

    public final void load(PluginContext context) {
        if (WAS_LOADED)
            throw new IllegalStateException("This method cannot be called twice");

        if (WAS_UNLOADED)
            throw new IllegalStateException("This method cannot be called after the plugin has been unloaded");

        WAS_LOADED = true;

        this.context = context;
        this.logger = context.getLogger();

        onLoad();
    }

    public final void unload() {
        if (!WAS_LOADED)
            throw new IllegalStateException("This method cannot be called before the plugin is loaded");

        if (WAS_UNLOADED)
            throw new IllegalStateException("This method cannot be called twice");

        WAS_UNLOADED = true;

        try {
            onUnload();
        } finally {
            this.context = null;
            this.logger = null;
        }
    }

    /**
     * Вызывается самим движком плагинов перед выгрузкой плагина, от которого зависит текущий.
     * <p>
     * Текущий плагин обязан отпустить всё, что он получил от выгружаемого: сервисы этого плагина
     * пропадают вместе с ним.
     *
     * @param pluginId идентификатор выгружаемого плагина
     */
    public void onDependencyUnload(String pluginId) {
    }

    protected abstract void onLoad();

    protected abstract void onUnload();
}