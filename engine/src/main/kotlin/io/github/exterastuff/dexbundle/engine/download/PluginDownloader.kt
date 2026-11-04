package io.github.exterastuff.dexbundle.engine.download

import androidx.annotation.WorkerThread
import com.exteragram.messenger.utils.network.ExteraHttpClient
import io.github.exterastuff.dexbundle.engine.extension.format
import io.github.exterastuff.dexbundle.engine.impl.DownloadSource
import io.github.exterastuff.dexbundle.engine.impl.PluginManifest
import io.github.exterastuff.dexbundle.engine.util.Logger
import okhttp3.Request
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ChatObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Скачивает jar'ы плагинов из [DownloadSource]. */
object PluginDownloader {
    private const val USER_AGENT = "DexBundle"

    private const val REQUEST_TIMEOUT_SECONDS = 30L
    private const val FILE_TIMEOUT_SECONDS = 300L

    /** Поток для загрузок: они долгие и не должны держать очередь плагинов. */
    val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "DexBundleDownloader")
                .apply { isDaemon = true }
        }

    private val downloadsDir: File
        get() = File(ApplicationLoader.applicationContext.cacheDir, "dexbundle/downloads")

    /** Скачанный и проверенный jar плагина. */
    data class Download(val file: File, val manifest: PluginManifest)

    /** Удаляет скачанные файлы, которые никто уже не ждёт. */
    fun clearDownloads() {
        downloadsDir.listFiles()
            .orEmpty()
            .forEach { it.delete() }
    }

    /**
     * Скачивает плагин [pluginId], перебирая [sources] по порядку до первой удачной загрузки.
     *
     * Загрузка удачна, если sha1 файла совпадает с указанным в источнике и в файле лежит манифест
     * плагина [pluginId].
     *
     * @throws IOException ни один источник не отдал подходящий файл
     */
    @WorkerThread
    @Throws(IOException::class)
    fun download(pluginId: String, sources: List<DownloadSource>): Download {
        if (sources.isEmpty())
            throw IOException("Plugin '$pluginId' has no download sources")

        val directory = downloadsDir

        if (!directory.isDirectory && !directory.mkdirs())
            throw IOException("Failed to create ${directory.absolutePath}")

        val errors = ArrayList<Throwable>()

        for (source in sources) {
            val target = File.createTempFile("$pluginId-", ".jar", directory)

            try {
                fetch(source, target)

                source.sha1?.let { expected ->
                    val actual = sha1(target)

                    if (actual != expected)
                        throw IOException("$source serves a file with sha1 $actual, $expected expected")
                }

                val manifest = PluginManifest.of(target)
                    ?: throw IOException("$source serves a file that is not a plugin")

                if (manifest.id != pluginId)
                    throw IOException("$source serves plugin '${manifest.id}', but '$pluginId' was expected")

                return Download(target, manifest)
            } catch (e: Throwable) {
                target.delete()

                Logger.warn("Failed to download '$pluginId' from $source: ${e.format()}")

                errors.add(e)
            }
        }

        throw IOException(
            "Failed to download plugin '$pluginId': " +
                    errors.joinToString("; ") { it.message ?: it.toString() }
        )
            .apply { errors.forEach(::addSuppressed) }
    }

    @Throws(IOException::class)
    private fun fetch(source: DownloadSource, target: File) =
        when (source) {
            is DownloadSource.Url -> httpDownload(source.url, target)
            is DownloadSource.GitHub -> fetchGitHub(source, target)
            is DownloadSource.Telegram -> fetchTelegram(source, target)
        }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")

        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

            while (true) {
                val read = input.read(buffer)

                if (read < 0)
                    break

                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun request(url: String, accept: String? = null): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { accept?.let { header("Accept", it) } }
            .build()

    @Throws(IOException::class)
    private fun httpDownload(url: String, target: File) {
        ExteraHttpClient.client
            .newCall(request(url))
            .execute()
            .use { response ->
                if (!response.isSuccessful)
                    throw IOException("HTTP ${response.code} from $url")

                Files.copy(response.body.byteStream(), target.toPath(), REPLACE_EXISTING)
            }
    }

    @Suppress("SameParameterValue")
    @Throws(IOException::class)
    private fun httpGetString(url: String, accept: String): String =
        ExteraHttpClient.client
            .newCall(request(url, accept))
            .execute()
            .use { response ->
                if (!response.isSuccessful)
                    throw IOException("HTTP ${response.code} from $url")

                response.body.string()
            }

    @Throws(IOException::class)
    private fun fetchGitHub(source: DownloadSource.GitHub, target: File) {
        val release =
            if (source.tag == null)
                "latest"
            else
                "tags/${URLEncoder.encode(source.tag, "UTF-8")}"

        val json = JSONObject(
            httpGetString(
                "https://api.github.com/repos/${source.repository}/releases/$release",
                "application/vnd.github+json",
            )
        )

        val assets = json.getJSONArray("assets")

        val asset = (0 until assets.length())
            .map(assets::getJSONObject)
            .firstOrNull {
                val name = it.getString("name")

                if (source.asset != null)
                    name == source.asset
                else
                    name.endsWith(".jar")
            }
            ?: throw IOException(
                "Release ${json.optString("tag_name")} of ${source.repository} has no " +
                        (source.asset?.let { "asset '$it'" } ?: ".jar asset")
            )

        httpDownload(asset.getString("browser_download_url"), target)
    }

    @Throws(IOException::class)
    private fun fetchTelegram(source: DownloadSource.Telegram, target: File) {
        val account = UserConfig.selectedAccount

        val chat = resolveChannel(account, source.channel)

        val request = TLRPC.TL_channels_getMessages()
            .apply {
                channel = MessagesController.getInputChannel(chat)
                id.add(source.postId)
            }

        val messages = sendRequest(account, request) as? TLRPC.messages_Messages
            ?: throw IOException("Unexpected response for $source")

        val document = messages.messages
            .firstOrNull()
            ?.media
            ?.document
            ?: throw IOException("Post $source has no file attached")

        downloadDocument(account, document)
            .copyTo(target, overwrite = true)
    }

    @Throws(IOException::class)
    private fun resolveChannel(account: Int, username: String): TLRPC.Chat {
        val controller = MessagesController.getInstance(account)

        (controller.getUserOrChat(username) as? TLRPC.Chat)
            ?.let { return it }

        val request = TLRPC.TL_contacts_resolveUsername()
            .apply { this.username = username }

        val resolved = sendRequest(account, request) as? TLRPC.TL_contacts_resolvedPeer
            ?: throw IOException("Unexpected response while resolving @$username")

        controller.putChats(resolved.chats, false)

        return resolved.chats
            .firstOrNull { ChatObject.isChannel(it) }
            ?: throw IOException("@$username is not a channel")
    }

    @Throws(IOException::class)
    private fun sendRequest(account: Int, request: TLObject): TLObject {
        val latch = CountDownLatch(1)
        val result = AtomicReference<TLObject?>()
        val error = AtomicReference<TLRPC.TL_error?>()

        ConnectionsManager.getInstance(account)
            .sendRequest(request) { response, responseError ->
                result.set(response)
                error.set(responseError)
                latch.countDown()
            }

        if (!latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            throw IOException("Telegram request ${request.javaClass.simpleName} timed out")

        error.get()?.let { throw IOException("Telegram error: ${it.text}") }

        return result.get()
            ?: throw IOException("Empty Telegram response")
    }

    /** Скачивает [document] средствами клиента и возвращает файл в его кеше. */
    @Throws(IOException::class)
    private fun downloadDocument(account: Int, document: TLRPC.Document): File {
        val fileLoader = FileLoader.getInstance(account)

        fileLoader.getPathToAttach(document, true)
            .takeIf { it.isFile && it.length() == document.size }
            ?.let { return it }

        val name = FileLoader.getAttachFileName(document)

        val latch = CountDownLatch(1)
        val loaded = AtomicReference<File?>()
        val failed = AtomicReference<Any?>()

        val observer =
            object : NotificationCenter.NotificationCenterDelegate {
                override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                    if (args.firstOrNull() != name)
                        return

                    when (id) {
                        NotificationCenter.fileLoaded -> loaded.set(args.getOrNull(1) as? File)
                        NotificationCenter.fileLoadFailed -> failed.set(args.getOrNull(1))
                        else -> return
                    }

                    latch.countDown()
                }
            }

        val notificationCenter = NotificationCenter.getInstance(account)

        // наблюдатели NotificationCenter живут на главном потоке
        AndroidUtilities.runOnUIThread {
            notificationCenter.addObserver(observer, NotificationCenter.fileLoaded)
            notificationCenter.addObserver(observer, NotificationCenter.fileLoadFailed)

            fileLoader.loadFile(document, null, FileLoader.PRIORITY_HIGH, 1)
        }

        try {
            if (!latch.await(FILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                fileLoader.cancelLoadFile(document)

                throw IOException("Download of $name timed out")
            }
        } finally {
            AndroidUtilities.runOnUIThread {
                notificationCenter.removeObserver(observer, NotificationCenter.fileLoaded)
                notificationCenter.removeObserver(observer, NotificationCenter.fileLoadFailed)
            }
        }

        failed.get()?.let { throw IOException("Failed to download $name (reason $it)") }

        return loaded.get()
            ?.takeIf(File::isFile)
            ?: fileLoader.getPathToAttach(document, true)
                .takeIf(File::isFile)
            ?: throw IOException("Downloaded $name is missing")
    }
}
