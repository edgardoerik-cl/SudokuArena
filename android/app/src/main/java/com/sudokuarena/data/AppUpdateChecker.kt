package com.sudokuarena.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
)

/** Consulta liviana: si falla la red, el menú sigue funcionando sin mostrar avisos falsos. */
object AppUpdateChecker {
    suspend fun findUpdate(baseUrl: String, installedVersionCode: Int): AppUpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = "${baseUrl.trimEnd('/')}/api/app-version"
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val payload = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val remoteCode = payload.optInt("versionCode", 0)
                val downloadUrl = payload.optString("downloadUrl").trim()
                if (remoteCode <= installedVersionCode || downloadUrl.isEmpty()) null else AppUpdateInfo(
                    versionCode = remoteCode,
                    versionName = payload.optString("versionName", remoteCode.toString()),
                    downloadUrl = downloadUrl,
                )
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    fun openDownload(context: Context, downloadUrl: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
