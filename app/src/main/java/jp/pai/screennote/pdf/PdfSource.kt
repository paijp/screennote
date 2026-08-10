package jp.pai.screennote.pdf

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves whatever URI the user navigated to into a seekable local file, which is what
 * [android.graphics.pdf.PdfRenderer] requires.
 */
object PdfSource {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_BYTES = 128L * 1024 * 1024

    suspend fun resolve(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "pdf").apply { mkdirs() }
        val target = File(cacheDir, "%08x.pdf".format(uri.toString().hashCode()))
        if (target.exists() && target.length() > 0) return@withContext target

        when (uri.scheme?.lowercase()) {
            "http", "https" -> download(uri, target)
            "file" -> copy(File(requireNotNull(uri.path)).inputStream(), target)
            else -> {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open $uri")
                copy(stream, target)
            }
        }
        target
    }

    private fun download(uri: Uri, target: File) {
        val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/pdf,*/*")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            copy(connection.inputStream, target)
        } finally {
            connection.disconnect()
        }
    }

    private fun copy(source: InputStream, target: File) {
        var total = 0L
        try {
            source.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw IOException("PDF larger than 128 MB")
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete() // Never leave a truncated file behind for the next open to reuse.
            throw t
        }
    }
}
