package com.sudokuarena.presentation

import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sudokuarena.audio.GlobalAudioManager

@Composable
fun AudioToggleButton(modifier: Modifier = Modifier) {
    val enabled by GlobalAudioManager.enabled.collectAsState()
    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = Color(0xFFF8FAFF),
        shadowElevation = 4.dp,
    ) {
        IconButton(
            onClick = GlobalAudioManager::toggle,
            modifier = Modifier.semantics {
                contentDescription = if (enabled) "Silenciar música y efectos" else "Activar música y efectos"
            },
        ) {
            Text(if (enabled) "🎵" else "🔇", fontSize = 20.sp)
        }
    }
}
