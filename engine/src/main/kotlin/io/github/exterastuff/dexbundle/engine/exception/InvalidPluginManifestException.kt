package io.github.exterastuff.dexbundle.engine.exception

/**
 * Indicates that plugin manifest is invalid.
 * Can be thrown if plugin manifest doesn't have required entries.
 */
class InvalidPluginManifestException(message: String, cause: Throwable? = null) :
    Exception(message, cause)