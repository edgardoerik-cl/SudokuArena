package com.sudokuarena.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GlobalGameChat(
    state: ArenaUiState,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.isSoloMode) return
    var draft by remember { mutableStateOf("") }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF7FAFF),
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("CHAT DE ARENA", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color(0xFF102A56))
            Column(
                Modifier.fillMaxWidth().heightIn(min = 42.dp, max = 92.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                state.globalChat.takeLast(6).forEach { entry ->
                    val name = state.players.firstOrNull { it.id == entry.playerId }?.name ?: "Jugador"
                    Text("$name: ${entry.message}", fontSize = 10.sp, color = Color(0xFF263238))
                }
                if (state.globalChat.isEmpty()) Text("Habla con todos los jugadores…", fontSize = 10.sp, color = Color(0xFF64748B))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(160) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Mensaje", fontSize = 10.sp) },
                )
                Button(
                    onClick = {
                        val message = draft.trim()
                        if (message.isNotEmpty()) {
                            onSend(message)
                            draft = ""
                        }
                    },
                ) { Text("➤") }
            }
        }
    }
}
