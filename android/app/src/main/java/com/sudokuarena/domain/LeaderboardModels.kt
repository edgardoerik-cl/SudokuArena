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

data class GameLeaderboardEntry(
    val rank: Int,
    val nickname: String,
    val bestTimeMs: Long? = null,
    val bestScore: Int? = null,
    val wins: Int = 0,
)

data class GameLeaderboards(
    val gameType: GameType,
    val time: List<GameLeaderboardEntry>,
    val score: List<GameLeaderboardEntry>,
)

interface LeaderboardRepository {
    suspend fun loadTopTen(): GlobalLeaderboards
    suspend fun loadGameTopTen(gameType: GameType): GameLeaderboards
    suspend fun beginSoloChallenge(): String
    suspend fun submitSoloRecord(nickname: String, gameType: GameType, elapsedMs: Long, score: Int, challengeToken: String)
}
