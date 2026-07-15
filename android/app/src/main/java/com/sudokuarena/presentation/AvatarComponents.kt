package com.sudokuarena.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val MultiArenaAvatars = listOf("ORBIT", "NOVA", "PIXEL", "NINJA", "ASTRO", "BRAIN", "ROBOT", "FOX")

fun avatarGlyph(id: String): String = when (id) {
    "NOVA" -> "✦"; "PIXEL" -> "▦"; "NINJA" -> "🥷"; "ASTRO" -> "🚀"
    "BRAIN" -> "🧠"; "ROBOT" -> "🤖"; "FOX" -> "🦊"; else -> "◉"
}

@Composable
fun AvatarBadge(id: String, color: Color, size: Dp = 34.dp) {
    Box(Modifier.size(size).background(color.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
        Text(avatarGlyph(id))
    }
}
