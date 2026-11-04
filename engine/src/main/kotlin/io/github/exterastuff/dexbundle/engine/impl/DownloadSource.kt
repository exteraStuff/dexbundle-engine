package io.github.exterastuff.dexbundle.engine.impl

import io.github.exterastuff.dexbundle.engine.exception.InvalidPluginManifestException
import java.util.jar.Attributes

/**
 * Место, откуда можно скачать jar плагина.
 *
 * В манифесте каждый источник — отдельная секция с атрибутом `Source-Type` и полями своего типа.
 */
sealed class DownloadSource {
    /** SHA-1 jar'а, который отдаёт источник, 40 hex-символов в нижнем регистре. */
    abstract val sha1: String?

    /** Прямая http(s)-ссылка на jar. */
    data class Url(val url: String, override val sha1: String?) : DownloadSource() {
        override fun toString(): String = url
    }

    /**
     * Пост в публичном Telegram-канале с прикреплённым jar'ом.
     *
     * @property channel username канала без `@`
     * @property postId id поста в канале
     */
    data class Telegram(val channel: String, val postId: Int, override val sha1: String?) :
        DownloadSource() {
        override fun toString(): String = "https://t.me/$channel/$postId"
    }

    /**
     * Ассет релиза на GitHub.
     *
     * @property repository репозиторий в виде `owner/repo`
     * @property tag тег релиза; `null` — последний релиз
     * @property asset имя файла ассета; `null` — первый `.jar` релиза
     */
    data class GitHub(
        val repository: String,
        val tag: String?,
        val asset: String?,
        override val sha1: String?,
    ) : DownloadSource() {
        override fun toString(): String =
            "github:$repository@${tag ?: "latest"}${asset?.let { "/$it" }.orEmpty()}"
    }

    companion object {
        private val SHA1 = Regex("""[0-9a-fA-F]{40}""")
        private val HTTP_URL = Regex("""https?://\S+""")
        private val TELEGRAM_CHANNEL = Regex("""[A-Za-z0-9_]{5,32}""")
        private val GITHUB_REPOSITORY = Regex("""[A-Za-z0-9-]+/[A-Za-z0-9._-]+""")

        /**
         * Разбирает источник из секции манифеста.
         *
         * @param section имя секции, для текста ошибки
         * @throws InvalidPluginManifestException тип неизвестен или поля некорректны
         */
        @Throws(InvalidPluginManifestException::class)
        fun of(section: String, attributes: Attributes): DownloadSource {
            fun invalid(message: String): Nothing =
                throw InvalidPluginManifestException("Section $section $message")

            fun required(name: String): String =
                attributes.getValue(name)
                    ?.takeIf(String::isNotBlank)
                    ?: invalid("has no $name attribute")

            val sha1 =
                attributes.getValue("Source-Sha1")
                    ?.let {
                        if (!SHA1.matches(it))
                            invalid("has sha1 '$it' that is not 40 hex characters")

                        it.lowercase()
                    }

            return when (val type = required("Source-Type")) {
                "url" -> {
                    val url = required("Source-Url")

                    if (!HTTP_URL.matches(url))
                        invalid("has url '$url' that is not an http(s) url")

                    Url(url, sha1)
                }

                "telegram" -> {
                    val channel = required("Source-Channel")

                    if (!TELEGRAM_CHANNEL.matches(channel))
                        invalid("has channel '$channel' that is not a Telegram username")

                    val post = required("Source-Post")

                    val postId = post.toIntOrNull()
                        ?.takeIf { it > 0 }
                        ?: invalid("has post id '$post' that is not a positive number")

                    Telegram(channel, postId, sha1)
                }

                "github" -> {
                    val repository = required("Source-Repository")

                    if (!GITHUB_REPOSITORY.matches(repository))
                        invalid("has repository '$repository' that is not owner/repo")

                    GitHub(
                        repository,
                        attributes.getValue("Source-Tag")
                            ?.takeIf(String::isNotBlank),
                        attributes.getValue("Source-Asset")
                            ?.takeIf(String::isNotBlank),
                        sha1,
                    )
                }

                else -> invalid("has unknown source type '$type'")
            }
        }
    }
}
