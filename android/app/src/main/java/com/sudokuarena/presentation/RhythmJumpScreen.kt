package com.sudokuarena.presentation

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.sudokuarena.audio.GlobalAudioManager

@Composable
fun RhythmJumpScreen(
    state: ArenaUiState,
    onInput: (Float) -> Unit,
    onPause: () -> Unit,
    onExit: () -> Unit,
) {
    var confirmExit by remember { mutableStateOf(false) }
    BackHandler { confirmExit = true }
    val rhythm = state.rhythmState
    val phase = rhythm?.let { GlobalAudioManager.beatPhase(it.bpm) } ?: 0f
    Scaffold(topBar = {
        PinnedGameHeader(
            "Salto Rítmico Arena", "BPM ${rhythm?.bpm ?: 128} · Beat ${rhythm?.beat ?: 0}",
            state, onTutorial = {}, onPause = onPause, onExit = { confirmExit = true },
        )
    }) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding)
                .background(Brush.verticalGradient(listOf(Color(0xFF12072C), Color(0xFF021B31))))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, delta -> change.consume(); onInput((delta.x / 45f).coerceIn(-1f, 1f)) },
                        onDragEnd = { onInput(0f) },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val camera = rhythm?.cameraY ?: 0f
                val scale = size.height / 7f
                rhythm?.platforms?.forEach { platform ->
                    val screenY = size.height - (platform.y - camera + 1f) * scale
                    val glow = .55f + (1f - kotlin.math.abs(phase - .5f) * 2f) * .45f
                    drawRoundRect(
                        (if (platform.obstacle) Color(0xFFFF3D71) else Color(0xFF00E5FF)).copy(alpha = glow),
                        Offset(platform.x * size.width, screenY),
                        Size(platform.width * size.width, 10f),
                    )
                }
                rhythm?.players?.forEach { player ->
                    val screenY = size.height - (player.y - camera + 1f) * scale
                    val color = runCatching { Color(AndroidColor.parseColor(player.colorHex)) }.getOrDefault(Color.White)
                    drawCircle(color.copy(alpha = .24f), 22f, Offset(player.x * size.width, screenY))
                    drawCircle(color, 11f, Offset(player.x * size.width, screenY))
                    repeat(player.lives) { life -> drawCircle(Color(0xFFFF3D71), 4f, Offset(player.x * size.width - 9f + life * 9f, screenY - 22f)) }
                }
                drawRect(Color(0x88FF1744), Offset(0f, size.height - 9f), Size(size.width, 9f))
            }
            ConfirmExitDialog(confirmExit, { confirmExit = false }, onExit)
        }
    }
}
