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

    override fun soloBestMs(): Long = preferences.getLong(KEY_SOLO_BEST_MS, 0L)

    /** Devuelve true si el tiempo se convirtió en el nuevo récord. */
    override fun recordSoloTime(elapsedMs: Long): Boolean {
        val previous = soloBestMs()
        val isRecord = previous == 0L || elapsedMs < previous
        if (isRecord) preferences.edit().putLong(KEY_SOLO_BEST_MS, elapsedMs).apply()
        return isRecord
    }

    override fun tutorialCompleted(): Boolean = preferences.getBoolean(KEY_TUTORIAL, false)

    override fun markTutorialCompleted() {
        preferences.edit().putBoolean(KEY_TUTORIAL, true).apply()
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
    }
}
