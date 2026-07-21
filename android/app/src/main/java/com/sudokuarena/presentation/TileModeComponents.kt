package com.sudokuarena.presentation

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sudokuarena.domain.TileType
import com.sudokuarena.domain.GameType
import com.sudokuarena.domain.PuzzleDifficulty

/** Mapeo visual estable: el índice 0 representa el valor Sudoku 1 y así hasta 9. */
object SudokuTilePalette {
    private val defaults = listOf(
        Color(0xFFFF3B30), // rojo
        Color(0xFF003F88), // azul marino
        Color(0xFF00A86B), // verde esmeralda
        Color(0xFFFFD400), // amarillo brillante
        Color(0xFF5B2C83), // morado oscuro
        Color(0xFFFF6B00), // naranja vibrante
        Color(0xFFD100D1), // magenta
        Color(0xFFFFFFFF), // blanco puro
        Color(0xFF30343B), // gris carbón
    )
    val colors = mutableStateListOf<Color>().apply { addAll(defaults) }

    fun colorFor(value: Int): Color = colors.getOrElse(value - 1) { Color.Gray }
    fun assign(value: Int, color: Color) {
        if (value in 1..9) colors[value - 1] = color
    }
    fun reset() {
        defaults.forEachIndexed { index, color -> colors[index] = color }
    }
}

@Composable
fun SoloSetupScreen(
    gameType: GameType,
    isColorMode: Boolean,
    difficulty: PuzzleDifficulty,
    onColorModeChanged: (Boolean) -> Unit,
    onDifficultyChanged: (PuzzleDifficulty) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(if (landscape) 14.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("${gameTitle(gameType).uppercase()} · SOLITARIO", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text("La partida se ejecuta localmente y puede pausarse.", modifier = Modifier.padding(vertical = 14.dp))
            Text(if (gameType == GameType.TIC_TAC_TOE) "TIPO DE GATO" else "DIFICULTAD", style = MaterialTheme.typography.labelLarge)
            if (gameType == GameType.TIC_TAC_TOE) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(PuzzleDifficulty.EASY to "Clásico 3×3", PuzzleDifficulty.MEDIUM to "Ultimate 9×9").forEach { (mode, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = (difficulty == PuzzleDifficulty.EASY) == (mode == PuzzleDifficulty.EASY), onClick = { onDifficultyChanged(mode) })
                            Text(label)
                        }
                    }
                }
            } else {
            val difficultyContent: @Composable (PuzzleDifficulty) -> Unit = { level ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = difficulty == level, onClick = { onDifficultyChanged(level) })
                    Text(
                        when (level) {
                            PuzzleDifficulty.EASY -> "Fácil"
                            PuzzleDifficulty.MEDIUM -> "Medio"
                            PuzzleDifficulty.HARD -> "Difícil"
                            PuzzleDifficulty.EXPERT -> "Experto"
                        },
                    )
                }
            }
            if (landscape) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) { PuzzleDifficulty.entries.forEach { difficultyContent(it) } }
            } else {
                PuzzleDifficulty.entries.forEach { difficultyContent(it) }
            }
            }
            if (gameType == GameType.SUDOKU) {
                Card(Modifier.fillMaxWidth()) {
                    TileTypeSelector(
                        selected = if (isColorMode) TileType.COLORS else TileType.NUMBERS,
                        enabled = true,
                        onSelected = { onColorModeChanged(it == TileType.COLORS) },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
                Text("Comenzar")
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Volver") }
        }
    }
}

@Composable
fun TileTypeSelector(
    selected: TileType,
    enabled: Boolean,
    onSelected: (TileType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("TIPO DE FICHA", style = MaterialTheme.typography.labelLarge)
        TileType.entries.forEach { type ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selected == type,
                    onClick = { onSelected(type) },
                    enabled = enabled,
                )
                Text(if (type == TileType.NUMBERS) "Números (1 al 9)" else "Colores (9 tonos únicos)")
            }
        }
        if (selected == TileType.COLORS) {
            SudokuColorPaletteEditor()
        }
    }
}

private val SudokuPickerSwatches = listOf(
    Color(0xFFE6194B), Color(0xFF3CB44B), Color(0xFFFFE119), Color(0xFF4363D8),
    Color(0xFFF58231), Color(0xFF911EB4), Color(0xFF42D4F4), Color(0xFFF032E6),
    Color(0xFFBFEF45), Color(0xFFFABED4), Color(0xFF469990), Color(0xFF9A6324),
    Color.White, Color(0xFF808080), Color(0xFF111827),
)

/** Selector local: cada jugador puede personalizar la equivalencia 1–9. */
@Composable
fun SudokuColorPaletteEditor(modifier: Modifier = Modifier) {
    var selectedNumber by remember { mutableStateOf(1) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "ASIGNA UN COLOR A CADA NÚMERO",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
        )
        Text("Selecciona una ficha y luego un tono. La lógica continúa usando valores del 1 al 9.")
        SudokuTilePalette.colors.chunked(3).forEachIndexed { row, colors ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                colors.forEachIndexed { column, color ->
                    val number = row * 3 + column + 1
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (selectedNumber == number) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        border = BorderStroke(if (selectedNumber == number) 2.dp else 1.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier.clickable { selectedNumber = number },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(number.toString(), fontWeight = FontWeight.Black)
                            ColorTile(color, Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
        SudokuPickerSwatches.chunked(8).forEach { colors ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                colors.forEach { color ->
                    ColorTile(
                        color,
                        Modifier
                            .size(30.dp)
                            .clickable { SudokuTilePalette.assign(selectedNumber, color) },
                    )
                }
            }
        }
        OutlinedButton(onClick = SudokuTilePalette::reset, modifier = Modifier.align(Alignment.End)) {
            Text("Restablecer paleta")
        }
    }
}

@Composable
fun ColorTile(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawCircle(Color.Black.copy(alpha = 0.45f), radius = size.minDimension * 0.49f)
        drawCircle(color, radius = size.minDimension * 0.40f)
        drawCircle(Color.White.copy(alpha = 0.70f), radius = size.minDimension * 0.40f, style = Stroke(1.5f))
    }
}
