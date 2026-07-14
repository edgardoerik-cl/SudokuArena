package com.sudokuarena.domain

data class SudokuPuzzle(
    val solution: List<List<Int>>,
    val initialBoard: List<List<Int?>>,
)

fun interface SudokuGenerator {
    fun generate(): SudokuPuzzle
}

interface PlayerRecordStore {
    fun soloBestMs(): Long
    fun recordSoloTime(elapsedMs: Long): Boolean
}
