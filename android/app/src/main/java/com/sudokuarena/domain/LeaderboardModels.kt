package com.sudokuarena.domain

data class SoloLeaderboardEntry(
    val rank: Int,
    val nickname: String,
    val bestTimeMs: Long,
)

data class MultiplayerLeaderboardEntry(
    val rank: Int,
    val nickname: String,
    val wins: Int,
)

data class GlobalLeaderboards(
    val solo: List<SoloLeaderboardEntry>,
    val multiplayer: List<MultiplayerLeaderboardEntry>,
)

interface LeaderboardRepository {
    suspend fun loadTopTen(): GlobalLeaderboards
    suspend fun submitSoloRecord(nickname: String, elapsedMs: Long)
}
