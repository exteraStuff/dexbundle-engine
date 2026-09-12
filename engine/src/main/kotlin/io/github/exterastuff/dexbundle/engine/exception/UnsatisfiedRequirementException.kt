package io.github.exterastuff.dexbundle.engine.exception

/**
 * Плагин не может работать прямо сейчас, но сам по себе исправен (наверно).
 *
 * Скорее всего не хватает плагина, от которого он зависит, клиент слишком старый, включён safe mode
 * и тому подобное.
 *
 * Такой плагин остаётся включённым и стартует сам, как только требование будет выполнено.
 */
class UnsatisfiedRequirementException(message: String) : Exception(message)
