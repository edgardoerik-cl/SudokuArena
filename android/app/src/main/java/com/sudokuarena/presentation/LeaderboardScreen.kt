package com.sudokuarena.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sudokuarena.domain.GlobalLeaderboards
import com.sudokuarena.domain.LeaderboardRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardBottomSheet(
    repository: LeaderboardRepository,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<GlobalLeaderboards?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        data = null
        error = null
        runCatching { repository.loadTopTen() }
            .onSuccess { data = it }
            .onFailure { error = "No se pudo cargar el Cuadro de Honor" }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111522),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("CUADRO DE HONOR", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            TabRow(selectedTabIndex = selectedTab, containerColor = Color(0xFF111522)) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Solitario ⏱") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Multijugador 🏆") })
            }
            Spacer(Modifier.height(12.dp))
            when {
                data != null -> {
                    val rows = if (selectedTab == 0) {
                        data!!.solo.map { HonorRow(it.rank, it.nickname, formatHonorTime(it.bestTimeMs)) }
                    } else {
                        data!!.multiplayer.map { HonorRow(it.rank, it.nickname, "${it.wins} victorias") }
                    }
                    if (rows.isEmpty()) {
                        Text("Aún no hay campeones. ¡Sé el primero!", modifier = Modifier.padding(30.dp))
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            rows.forEach { HonorCard(it) }
                        }
                    }
                }
                error != null -> {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { reloadKey += 1 }) { Text("Reintentar") }
                }
                else -> CircularProgressIndicator(modifier = Modifier.padding(32.dp))
            }
        }
    }
}

private data class HonorRow(val rank: Int, val nickname: String, val value: String)

@Composable
private fun HonorCard(row: HonorRow) {
    val accent = when (row.rank) {
        1 -> Color(0xFFFFD54F)
        2 -> Color(0xFFCFD8DC)
        3 -> Color(0xFFCD7F32)
        else -> Color(0xFF263247)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171D2D)),
        border = BorderStroke(if (row.rank <= 3) 1.5.dp else 1.dp, accent.copy(alpha = 0.9f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { shadowElevation = if (row.rank <= 3) 14f else 2f },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(accent.copy(alpha = if (row.rank <= 3) 0.10f else 0.03f))
                .padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(medal(row.rank), style = MaterialTheme.typography.titleLarge)
            Text(row.nickname, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Text(row.value, color = accent, fontWeight = FontWeight.Bold)
        }
    }
}

private fun medal(rank: Int): String = when (rank) {
    1 -> "🥇"
    2 -> "🥈"
    3 -> "🥉"
    else -> "#$rank"
}

private fun formatHonorTime(milliseconds: Long): String {
    val seconds = milliseconds / 1_000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
