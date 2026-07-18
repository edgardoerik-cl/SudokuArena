package com.sudokuarena.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sudokuarena.audio.GlobalAudioManager
import com.sudokuarena.audio.MusicGenre

/** Controles compactos: mute, canción siguiente y selector de género. */
@Composable
fun AudioControls(modifier: Modifier = Modifier, compact: Boolean = false) {
    val audio by GlobalAudioManager.state.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var volumeOpen by remember { mutableStateOf(false) }
    Row(modifier) {
        AudioCircleButton(
            text = if (audio.enabled) "🎵" else "🔇",
            description = if (audio.enabled) "Silenciar música y efectos" else "Activar música y efectos",
            onClick = GlobalAudioManager::toggle,
        )
        if (!compact) {
            AudioCircleButton("⏭", "Siguiente canción", GlobalAudioManager::nextTrack)
            androidx.compose.foundation.layout.Box {
                AudioCircleButton("🔊", "Ajustar volumen") { volumeOpen = true }
                DropdownMenu(expanded = volumeOpen, onDismissRequest = { volumeOpen = false }) {
                    Column(Modifier.width(230.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Volumen ${(audio.volume * 100).toInt()} %")
                        Slider(
                            value = audio.volume,
                            onValueChange = GlobalAudioManager::setVolume,
                            valueRange = 0f..1f,
                        )
                    }
                }
            }
        }
        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = Color(0xFFF8FAFF), shadowElevation = 4.dp) {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.semantics {
                    contentDescription =
                        "Cambiar género musical. Actual: ${audio.genre.trackTitle}, ${audio.genre.artist}"
                },
            ) {
                if (audio.preparing) Text("…", fontSize = 18.sp)
                else if (audio.genre == MusicGenre.METAL) MetalHeadbangerIcon()
                else Text(audio.genre.icon, fontSize = 18.sp)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.widthIn(min = 180.dp)) {
            MusicGenre.entries.forEach { genre ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("${genre.icon}  ${genre.label}${if (genre == audio.genre) "  ✓" else ""}")
                            Text(
                                "${genre.trackTitle} · ${genre.artist}",
                                fontSize = 11.sp,
                                color = Color(0xFF526078),
                            )
                        }
                    },
                    onClick = {
                        GlobalAudioManager.selectGenre(genre)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

/** Icono vectorial animado: la cabellera oscila solo cuando suena Metal. */
@Composable
private fun MetalHeadbangerIcon() {
    val phase by rememberInfiniteTransition(label = "headbanger").animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(260), RepeatMode.Reverse),
        label = "hairSwing",
    )
    Canvas(Modifier.size(28.dp).padding(2.dp)) {
        val head = Offset(size.width * .5f, size.height * .34f)
        drawCircle(Color(0xFFFFD2B3), size.minDimension * .16f, head)
        drawArc(
            color = Color(0xFF111827),
            startAngle = 185f + phase * 18f,
            sweepAngle = 225f,
            useCenter = false,
            topLeft = Offset(head.x - size.width * .25f + phase * 2f, head.y - size.height * .18f),
            size = androidx.compose.ui.geometry.Size(size.width * .5f, size.height * .64f),
            style = Stroke(size.minDimension * .13f, cap = StrokeCap.Round),
        )
        drawLine(Color(0xFF7C3AED), Offset(size.width * .5f, size.height * .50f), Offset(size.width * .5f, size.height * .80f), size.minDimension * .13f, StrokeCap.Round)
        drawLine(Color(0xFF111827), Offset(size.width * .38f, size.height * .72f), Offset(size.width * .28f, size.height * .92f), 3f, StrokeCap.Round)
        drawLine(Color(0xFF111827), Offset(size.width * .62f, size.height * .72f), Offset(size.width * .72f, size.height * .92f), 3f, StrokeCap.Round)
    }
}

@Composable
fun AudioToggleButton(modifier: Modifier = Modifier) {
    AudioControls(modifier = modifier, compact = true)
}

@Composable
private fun AudioCircleButton(text: String, description: String, onClick: () -> Unit) {
    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = Color(0xFFF8FAFF), shadowElevation = 4.dp) {
        IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
            Text(text, fontSize = 19.sp)
        }
    }
}
