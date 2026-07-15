package com.sudokuarena.domain

data class SudokuPuzzle(
    val solution: List<List<Int>>,
    val initialBoard: List<List<Int?>>,
)

interface SudokuGenerator {
    fun generate(seed: Long? = null): SudokuPuzzle
}

interface PlayerRecordStore {
    fun soloBestMs(gameType: GameType = GameType.SUDOKU): Long
    fun recordSoloTime(gameType: GameType, elapsedMs: Long): Boolean
    fun soloBestScore(gameType: GameType): Int
    fun recordSoloScore(gameType: GameType, score: Int): Boolean
    fun tutorialCompleted(gameType: GameType): Boolean
    fun markTutorialCompleted(gameType: GameType)
    fun totalXp(): Int
    fun addXp(amount: Int)
    fun markDailyCompleted(dayKey: String): Boolean
}
