package com.sudokuarena.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
                modifier = Modifier.semantics { contentDescription = "Cambiar género musical. Actual: ${audio.genre.label}" },
            ) { Text(if (audio.preparing) "…" else audio.genre.icon, fontSize = 18.sp) }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.widthIn(min = 180.dp)) {
            MusicGenre.entries.forEach { genre ->
                DropdownMenuItem(
                    text = { Text("${genre.icon}  ${genre.label}${if (genre == audio.genre) "  ✓" else ""}") },
                    onClick = {
                        GlobalAudioManager.selectGenre(genre)
                        menuOpen = false
                    },
                )
            }
        }
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
