package io.github.exterastuff.dexbundle.api;

public abstract class BasePlugin {
    /**
     * To prevent accidental invocation of the BasePlugin::load method.
     * <p>
     * As plugin engine calls this method only once per plugin instance, check with this flag can
     * fail only when plugin calls this method by itself.
     */
    private volatile boolean WAS_LOADED = false;

    /**
     * To prevent the BasePlugin::unload method from being called twice.
     * <p>
     * As plugin engine calls this method only once per plugin instance, check with this flag can
     * fail only when plugin calls this method by itself.
     */
    private volatile boolean WAS_UNLOADED = false;

    protected PluginContext context;
    protected Logger logger;

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

        onUnload();

        this.context = null;
        this.logger = null;
    }

    protected abstract void onLoad();

    protected abstract void onUnload();
}