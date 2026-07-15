package com.sudokuarena.data.local

import android.content.Context
import com.sudokuarena.domain.PlayerRecordStore
import java.util.UUID

class PlayerPreferences(context: Context) : PlayerRecordStore {
    private val preferences = context.getSharedPreferences("sudoku_arena_player", Context.MODE_PRIVATE)

    fun nickname(): String = preferences.getString(KEY_NICKNAME, "")?.trim().orEmpty()

    fun saveNickname(nickname: String) {
        preferences.edit().putString(KEY_NICKNAME, nickname.trim().take(20)).apply()
    }

    fun clientId(): String {
        preferences.getString(KEY_CLIENT_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also { preferences.edit().putString(KEY_CLIENT_ID, it).apply() }
    }

    fun avatarId(): String = preferences.getString(KEY_AVATAR, "ORBIT") ?: "ORBIT"

    fun saveAvatarId(avatarId: String) {
        if (avatarId in AVATARS) preferences.edit().putString(KEY_AVATAR, avatarId).apply()
    }

    override fun soloBestMs(gameType: com.sudokuarena.domain.GameType): Long = preferences.getLong("${KEY_SOLO_BEST_MS}_${gameType.name}", preferences.getLong(KEY_SOLO_BEST_MS, 0L).takeIf { gameType == com.sudokuarena.domain.GameType.SUDOKU } ?: 0L)

    /** Devuelve true si el tiempo se convirtió en el nuevo récord. */
    override fun recordSoloTime(gameType: com.sudokuarena.domain.GameType, elapsedMs: Long): Boolean {
        val previous = soloBestMs(gameType)
        val isRecord = previous == 0L || elapsedMs < previous
        if (isRecord) preferences.edit().putLong("${KEY_SOLO_BEST_MS}_${gameType.name}", elapsedMs).apply()
        return isRecord
    }

    override fun soloBestScore(gameType: com.sudokuarena.domain.GameType): Int = preferences.getInt("solo_best_score_${gameType.name}", 0)

    override fun recordSoloScore(gameType: com.sudokuarena.domain.GameType, score: Int): Boolean {
        val record = score > soloBestScore(gameType)
        if (record) preferences.edit().putInt("solo_best_score_${gameType.name}", score).apply()
        return record
    }

    override fun tutorialCompleted(gameType: com.sudokuarena.domain.GameType): Boolean = preferences.getBoolean("${KEY_TUTORIAL}_${gameType.name}", false)

    override fun markTutorialCompleted(gameType: com.sudokuarena.domain.GameType) {
        preferences.edit().putBoolean("${KEY_TUTORIAL}_${gameType.name}", true).apply()
    }

    override fun totalXp(): Int = preferences.getInt(KEY_XP, 0)

    override fun addXp(amount: Int) {
        preferences.edit().putInt(KEY_XP, (totalXp() + amount.coerceAtLeast(0)).coerceAtMost(999_999)).apply()
    }

    override fun markDailyCompleted(dayKey: String): Boolean {
        if (preferences.getString(KEY_DAILY, null) == dayKey) return false
        preferences.edit().putString(KEY_DAILY, dayKey).apply()
        return true
    }

    private companion object {
        const val KEY_NICKNAME = "nickname"
        const val KEY_SOLO_BEST_MS = "solo_best_ms"
        const val KEY_TUTORIAL = "tutorial_completed"
        const val KEY_XP = "total_xp"
        const val KEY_DAILY = "daily_completed"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_AVATAR = "avatar_id"
        val AVATARS = setOf("ORBIT", "NOVA", "PIXEL", "NINJA", "ASTRO", "BRAIN", "ROBOT", "FOX")
    }
}
