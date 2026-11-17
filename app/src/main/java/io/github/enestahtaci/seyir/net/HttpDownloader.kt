package io.github.enestahtaci.seyir.net

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * NewPipe Extractor's network access, on Android's own HttpURLConnection (no OkHttp: one dependency less).
 * No cookies are kept: there is no account, so YouTube cannot tie requests to a person.
 */
object HttpDownloader : Downloader() {
    /** Same desktop browser identity NewPipe uses; YouTube serves the web client to it. */
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

    override fun execute(request: Request): Response {
        val connection = URL(request.url()).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.requestMethod = request.httpMethod()
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Encoding", "gzip")
            for ((name, values) in request.headers()) {
                values.forEachIndexed { i, value ->
                    if (i == 0) connection.setRequestProperty(name, value) else connection.addRequestProperty(name, value)
                }
            }
            request.dataToSend()?.let { body ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }

            val code = connection.responseCode
            if (code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())

            val raw = if (code >= 400) connection.errorStream else connection.inputStream
            val body = raw?.let { stream ->
                val input = if ("gzip".equals(connection.contentEncoding, ignoreCase = true)) GZIPInputStream(stream) else stream
                input.use { it.readBytes().toString(Charsets.UTF_8) }
            } ?: ""
            val headers = connection.headerFields.entries
                .filter { it.key != null }
                .associate { it.key!! to it.value }
            return Response(code, connection.responseMessage, headers, body, connection.url.toString())
        } finally {
            connection.disconnect()
        }
    }
}
