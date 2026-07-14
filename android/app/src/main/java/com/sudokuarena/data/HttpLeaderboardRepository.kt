package com.sudokuarena.data

import com.sudokuarena.domain.GlobalLeaderboards
import com.sudokuarena.domain.LeaderboardRepository
import com.sudokuarena.domain.MultiplayerLeaderboardEntry
import com.sudokuarena.domain.SoloLeaderboardEntry
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HttpLeaderboardRepository(serverUrl: String) : LeaderboardRepository {
    private val baseUrl = serverUrl.trimEnd('/')

    override suspend fun loadTopTen(): GlobalLeaderboards = withContext(Dispatchers.IO) {
        val json = request("GET", "/api/leaderboards")
        val soloJson = json.getJSONArray("solo")
        val multiplayerJson = json.getJSONArray("multiplayer")
        GlobalLeaderboards(
            solo = List(soloJson.length()) { index ->
                val entry = soloJson.getJSONObject(index)
                SoloLeaderboardEntry(
                    rank = entry.getInt("rank"),
                    nickname = entry.getString("nickname"),
                    bestTimeMs = entry.getLong("bestTimeMs"),
                )
            },
            multiplayer = List(multiplayerJson.length()) { index ->
                val entry = multiplayerJson.getJSONObject(index)
                MultiplayerLeaderboardEntry(
                    rank = entry.getInt("rank"),
                    nickname = entry.getString("nickname"),
                    wins = entry.getInt("wins"),
                )
            },
        )
    }

    override suspend fun submitSoloRecord(nickname: String, elapsedMs: Long) = withContext(Dispatchers.IO) {
        request(
            method = "POST",
            path = "/api/leaderboards/solo",
            body = JSONObject().put("nickname", nickname).put("elapsedMs", elapsedMs).toString(),
        )
        Unit
    }

    private fun request(method: String, path: String, body: String? = null): JSONObject {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Servidor respondió $code")
            JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}
