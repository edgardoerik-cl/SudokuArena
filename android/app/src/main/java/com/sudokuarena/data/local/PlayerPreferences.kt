package com.sudokuarena.data.local

import android.content.Context
import com.sudokuarena.domain.PlayerRecordStore

class PlayerPreferences(context: Context) : PlayerRecordStore {
    private val preferences = context.getSharedPreferences("sudoku_arena_player", Context.MODE_PRIVATE)

    fun nickname(): String = preferences.getString(KEY_NICKNAME, "")?.trim().orEmpty()

    fun saveNickname(nickname: String) {
        preferences.edit().putString(KEY_NICKNAME, nickname.trim().take(20)).apply()
    }

    override fun soloBestMs(): Long = preferences.getLong(KEY_SOLO_BEST_MS, 0L)

    /** Devuelve true si el tiempo se convirtió en el nuevo récord. */
    override fun recordSoloTime(elapsedMs: Long): Boolean {
        val previous = soloBestMs()
        val isRecord = previous == 0L || elapsedMs < previous
        if (isRecord) preferences.edit().putLong(KEY_SOLO_BEST_MS, elapsedMs).apply()
        return isRecord
    }

    private companion object {
        const val KEY_NICKNAME = "nickname"
        const val KEY_SOLO_BEST_MS = "solo_best_ms"
    }
}
