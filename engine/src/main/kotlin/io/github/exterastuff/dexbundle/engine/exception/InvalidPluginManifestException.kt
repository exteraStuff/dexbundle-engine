package io.github.exterastuff.dexbundle.engine.exception

/**
 * Манифест плагина некорректен. Например, в нём нет обязательных полей или их значения не подходят.
 */
class InvalidPluginManifestException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
