package com.sudokuarena.domain

data class SudokuPuzzle(
    val solution: List<List<Int>>,
    val initialBoard: List<List<Int?>>,
)

interface SudokuGenerator {
    fun generate(seed: Long? = null): SudokuPuzzle
}

interface PlayerRecordStore {
    fun soloBestMs(): Long
    fun recordSoloTime(elapsedMs: Long): Boolean
    fun tutorialCompleted(): Boolean
    fun markTutorialCompleted()
    fun totalXp(): Int
    fun addXp(amount: Int)
    fun markDailyCompleted(dayKey: String): Boolean
}
