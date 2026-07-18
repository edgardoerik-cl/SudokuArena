package com.sudokuarena.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RpsStartScreen(state: ArenaUiState, onChoose: (String) -> Unit, onExit: () -> Unit) {
    var confirmExit by remember { mutableStateOf(false) }
    BackHandler { confirmExit = true }
    val impact = remember { Animatable(.78f) }
    LaunchedEffect(state.rpsChoices) {
        if (state.rpsChoices.isNotEmpty()) {
            impact.snapTo(.78f)
            impact.animateTo(1.12f, tween(240))
            impact.animateTo(1f, tween(180))
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF07152E)).padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(.9f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("DUELO DE INICIO", color = Color(0xFF00E5FF), fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text("Ronda ${state.rpsRound} · el ganador obtiene el primer turno", color = Color.White)
                val seconds = ((state.rpsEndsAt - state.serverNowMs + 999) / 1_000).coerceAtLeast(0)
                Text(if (state.rpsChoices.isEmpty()) "$seconds" else "¡REVELAR!", color = Color(0xFFFFD740), fontSize = 42.sp, fontWeight = FontWeight.Black)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("ROCK" to "✊", "PAPER" to "✋", "SCISSORS" to "✌").forEach { (id, icon) ->
                        Button(onClick = { onChoose(id) }, enabled = state.rpsChoice == null, modifier = Modifier.size(86.dp)) {
                            Text(icon, fontSize = 34.sp)
                        }
                    }
                }
                state.rpsChoice?.let { Text("Elección bloqueada: ${rpsIcon(it)}", color = Color(0xFFA7F3D0), fontWeight = FontWeight.Bold) }
            }
            Surface(
                modifier = Modifier.weight(1.1f),
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF102A56),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AnimatedContent(
                        targetState = state.rpsChoices,
                        transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                        label = "rpsReveal",
                    ) { choices ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.players.forEach { player ->
                                Surface(color = Color(android.graphics.Color.parseColor(player.colorHex)).copy(alpha = .22f), shape = RoundedCornerShape(12.dp)) {
                                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        AvatarBadge(player.avatarId, Color(android.graphics.Color.parseColor(player.colorHex)), 34.dp)
                                        Text(player.name, Modifier.weight(1f).padding(start = 8.dp), color = Color.White, fontWeight = FontWeight.Black)
                                        Text(
                                            choices[player.id]?.let(::rpsIcon) ?: "❔",
                                            fontSize = 34.sp,
                                            modifier = Modifier.graphicsLayer { scaleX = impact.value; scaleY = impact.value },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (state.rpsTie) Text("EMPATE · nueva ronda automática", color = Color(0xFFFFD740), fontWeight = FontWeight.Black)
                    state.rpsWinnerId?.let { winner ->
                        Text("GANA ${state.players.firstOrNull { it.id == winner }?.name.orEmpty()}", color = Color(0xFF69F0AE), fontSize = 20.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
        ConfirmExitDialog(confirmExit, onDismiss = { confirmExit = false }, onConfirm = onExit)
    }
}

private fun rpsIcon(choice: String): String = when (choice) {
    "ROCK" -> "✊"; "PAPER" -> "✋"; "SCISSORS" -> "✌"; else -> "❔"
}
