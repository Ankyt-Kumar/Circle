package com.circle.app.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

class HttpTransport(
    private val baseUrl: String,
    private val credentials: RequestCredentials = DemoCredentials,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null): String = withContext(dispatcher) {
        val snapshot = credentials.snapshot()
        val connection = URI(baseUrl.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/json")
            snapshot.token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (!credentials.isCurrent(snapshot)) throw CancellationException("Account changed")
            if (status == 401 || status == 403) credentials.rejected(snapshot, status)
            if (status !in 200..299) throw ApiException(status)
            text
        } finally { connection.disconnect() }
    }
}
