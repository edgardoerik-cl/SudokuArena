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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xFF07152E)).padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RpsChoicePanel(state, onChoose, Modifier.weight(.9f))
                RpsRosterPanel(state, impact.value, Modifier.weight(1.1f))
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RpsChoicePanel(state, onChoose, Modifier.fillMaxWidth())
                RpsRosterPanel(state, impact.value, Modifier.fillMaxWidth())
            }
        }
        ConfirmExitDialog(confirmExit, onDismiss = { confirmExit = false }, onConfirm = onExit)
    }
}

@Composable
private fun RpsChoicePanel(state: ArenaUiState, onChoose: (String) -> Unit, modifier: Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("DUELO DE INICIO", color = Color(0xFF00E5FF), fontSize = 25.sp, fontWeight = FontWeight.Black)
        Text("Ronda ${state.rpsRound} · el ganador obtiene el primer turno", color = Color.White)
        val seconds = ((state.rpsEndsAt - state.serverNowMs + 999) / 1_000).coerceAtLeast(0)
        Text(if (state.rpsChoices.isEmpty()) "$seconds" else "¡REVELAR!", color = Color(0xFFFFD740), fontSize = 38.sp, fontWeight = FontWeight.Black)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("ROCK" to "✊", "PAPER" to "✋", "SCISSORS" to "✌").forEach { (id, icon) ->
                Button(
                    onClick = { onChoose(id) },
                    enabled = state.rpsChoice == null,
                    modifier = Modifier.weight(1f).aspectRatio(1.35f),
                ) { Text(icon, fontSize = 30.sp) }
            }
        }
        state.rpsChoice?.let { Text("Elección bloqueada: ${rpsIcon(it)}", color = Color(0xFFA7F3D0), fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun RpsRosterPanel(state: ArenaUiState, impact: Float, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF102A56),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimatedContent(
                targetState = state.rpsChoices,
                transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                label = "rpsReveal",
            ) { choices ->
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    state.players.forEach { player ->
                        val color = Color(android.graphics.Color.parseColor(player.colorHex))
                        Surface(color = color.copy(alpha = .22f), shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                AvatarBadge(player.avatarId, color, 32.dp)
                                Text(player.name, Modifier.weight(1f).padding(start = 8.dp), color = Color.White, fontWeight = FontWeight.Black)
                                Text(
                                    choices[player.id]?.let(::rpsIcon) ?: "❔",
                                    fontSize = 30.sp,
                                    modifier = Modifier.graphicsLayer { scaleX = impact; scaleY = impact },
                                )
                            }
                        }
                    }
                }
            }
            if (state.rpsTie) Text("EMPATE · nueva ronda automática", color = Color(0xFFFFD740), fontWeight = FontWeight.Black)
            state.rpsWinnerId?.let { winner ->
                Text("GANA ${state.players.firstOrNull { it.id == winner }?.name.orEmpty()}", color = Color(0xFF69F0AE), fontSize = 19.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

private fun rpsIcon(choice: String): String = when (choice) {
    "ROCK" -> "✊"
    "PAPER" -> "✋"
    "SCISSORS" -> "✌"
    else -> "❔"
}
