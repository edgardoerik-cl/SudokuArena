package com.sudokuarena.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sudokuarena.domain.GenericBoardState
import com.sudokuarena.domain.Player
import kotlin.math.floor
import kotlinx.coroutines.launch

private data class CapitalSpaceUi(
    val index: Int,
    val name: String,
    val type: String,
    val price: Int,
)

@Composable
fun CapitalArenaBoard(
    state: GenericBoardState,
    players: Map<String, Player>,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val spaces = remember(state.gameId) { capitalSpaces(state) }
    val positions = state.meta.stringIntMap("positions")
    val owners = state.meta.stringStringMap("propertyOwners")
    val levels = state.meta.stringIntMap("propertyLevels")
    val tokenAnimations = remember(state.gameId, players.keys) {
        players.keys.associateWith { playerId -> Animatable((positions[playerId] ?: 0).toFloat()) }
    }
    LaunchedEffect(positions) {
        tokenAnimations.forEach { (playerId, animation) ->
            launch {
                val target = (positions[playerId] ?: 0).toFloat()
                val base = floor(animation.value / 40f) * 40f
                var destination = base + target
                if (destination < animation.value) destination += 40f
                animation.animateTo(destination, tween(((destination - animation.value).coerceAtLeast(1f) * 115).toInt().coerceAtMost(2_200)))
            }
        }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color(0xFFF6F8FF), RoundedCornerShape(18.dp)),
    ) {
        val unit = size.minDimension / 11f
        spaces.forEach { space ->
            val cell = capitalCellOffset(space.index, unit)
            val owner = owners[space.index.toString()]
            val ownerColor = owner?.let(players::get)?.colorHex?.let(::parseCapitalColor)
            val color = ownerColor?.copy(alpha = .34f) ?: capitalSpaceColor(space.type, space.index)
            drawRoundRect(color, cell + Offset(2f, 2f), Size(unit - 4f, unit - 4f))
            drawRoundRect(Color(0xFF263A60), cell + Offset(2f, 2f), Size(unit - 4f, unit - 4f), style = Stroke(1.4f))
            val shortName = when (space.type) {
                "START" -> "SALIDA"; "GO_TO_JAIL" -> "CÁRCEL"; "CHANCE" -> "?"; "TAX" -> "IMP"
                "STATION" -> "🚉"; "JAIL" -> "🔒"; "PARKING" -> "P"; else -> space.name.take(7)
            }
            val layout = textMeasurer.measure(
                shortName,
                TextStyle(color = Color(0xFF102A56), fontSize = if (shortName.length > 4) 6.sp else 9.sp, fontWeight = FontWeight.Black),
            )
            drawText(layout, topLeft = cell + Offset((unit - layout.size.width) / 2f, unit * .22f))
            if (space.price > 0) {
                val detail = textMeasurer.measure(
                    "${space.price}${levels[space.index.toString()]?.let { " · L$it" }.orEmpty()}",
                    TextStyle(color = Color(0xFF37474F), fontSize = 5.sp, fontWeight = FontWeight.Bold),
                )
                drawText(detail, topLeft = cell + Offset((unit - detail.size.width) / 2f, unit * .62f))
            }
        }
        drawRoundRect(
            Color(0xFF101B3B), Offset(unit * 1.35f, unit * 1.35f),
            Size(unit * 8.3f, unit * 8.3f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(28f),
        )
        val title = textMeasurer.measure("CAPITAL\nARENA", TextStyle(color = Color(0xFF00E5FF), fontSize = 25.sp, fontWeight = FontWeight.Black))
        drawText(title, topLeft = center - Offset(title.size.width / 2f, title.size.height / 2f))

        tokenAnimations.entries.forEachIndexed { order, (playerId, animation) ->
            val tokenCenter = capitalPositionOffset(animation.value, unit) +
                Offset(unit * (.32f + (order % 2) * .28f), unit * (.32f + (order / 2) * .28f))
            val tokenColor = players[playerId]?.colorHex?.let(::parseCapitalColor) ?: Color.White
            drawCircle(Color.White, unit * .13f, tokenCenter)
            drawCircle(tokenColor, unit * .105f, tokenCenter)
            drawCircle(Color(0xFF102A56), unit * .105f, tokenCenter, style = Stroke(1.2f))
        }
    }
}

@Composable
fun CapitalArenaControls(
    state: ArenaUiState,
    enabled: Boolean,
    onAction: (Map<String, String>) -> Unit,
) {
    val generic = state.genericBoard ?: return
    val stage = generic.meta["stage"]?.toString() ?: "ROLL"
    val dice = (generic.meta["dice"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: listOf(1, 1)
    val balances = generic.meta.stringIntMap("balances")
    val ownBalance = balances[state.playerId] ?: 0
    val event = generic.meta["lastEvent"]?.toString().orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Capital: $ownBalance créditos", fontWeight = FontWeight.Black, color = Color(0xFF102A56))
            AnimatedContent(
                targetState = dice.joinToString("-"),
                transitionSpec = { (scaleIn(tween(260)) + fadeIn()) togetherWith (scaleOut(tween(180)) + fadeOut()) },
                label = "capitalDice",
            ) { value ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    value.split("-").forEach { die ->
                        Surface(shape = RoundedCornerShape(8.dp), color = Color.White, border = BorderStroke(2.dp, Color(0xFF7C3AED))) {
                            Text(die, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 22.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
        if (event.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp),
                color = Color(0xFFEAFBFF), border = BorderStroke(1.dp, Color(0xFF00A8FF)),
            ) { Text("⚡ $event", Modifier.padding(10.dp), fontWeight = FontWeight.Bold) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            when (stage) {
                "ROLL" -> Button(
                    onClick = { onAction(mapOf("action" to "ROLL")) },
                    enabled = enabled, modifier = Modifier.weight(1f),
                ) { Text("🎲 Lanzar dados") }
                "BUY_OR_END" -> {
                    Button(
                        onClick = { onAction(mapOf("action" to "BUY")) },
                        enabled = enabled, modifier = Modifier.weight(1f),
                    ) { Text("Comprar propiedad") }
                    OutlinedButton(
                        onClick = { onAction(mapOf("action" to "END_TURN")) },
                        enabled = enabled, modifier = Modifier.weight(1f),
                    ) { Text("Pasar") }
                }
                else -> {
                    OutlinedButton(
                        onClick = { onAction(mapOf("action" to "BUILD")) },
                        enabled = enabled, modifier = Modifier.weight(1f),
                    ) { Text("⬆ Mejorar") }
                    Button(
                        onClick = { onAction(mapOf("action" to "END_TURN")) },
                        enabled = enabled, modifier = Modifier.weight(1f),
                    ) { Text("Terminar turno") }
                }
            }
        }
    }
}

private fun capitalSpaces(state: GenericBoardState): List<CapitalSpaceUi> =
    (state.meta["spaces"] as? List<*>)?.mapNotNull { raw ->
        val map = raw as? Map<*, *> ?: return@mapNotNull null
        CapitalSpaceUi(
            index = (map["index"] as? Number)?.toInt() ?: return@mapNotNull null,
            name = map["name"]?.toString().orEmpty(),
            type = map["type"]?.toString().orEmpty(),
            price = (map["price"] as? Number)?.toInt() ?: 0,
        )
    }.orEmpty()

private fun Map<String, Any?>.stringIntMap(key: String): Map<String, Int> =
    (this[key] as? Map<*, *>)?.entries?.associate { it.key.toString() to ((it.value as? Number)?.toInt() ?: 0) }.orEmpty()

private fun Map<String, Any?>.stringStringMap(key: String): Map<String, String> =
    (this[key] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() }.orEmpty()

private fun capitalCellOffset(index: Int, unit: Float): Offset = when {
    index <= 10 -> Offset((10 - index) * unit, 10 * unit)
    index <= 20 -> Offset(0f, (20 - index) * unit)
    index <= 30 -> Offset((index - 20) * unit, 0f)
    else -> Offset(10 * unit, (index - 30) * unit)
}

private fun capitalPositionOffset(position: Float, unit: Float): Offset {
    val normalized = ((position % 40f) + 40f) % 40f
    val lower = floor(normalized).toInt()
    val progress = normalized - lower
    val from = capitalCellOffset(lower, unit)
    val to = capitalCellOffset((lower + 1) % 40, unit)
    return Offset(from.x + (to.x - from.x) * progress, from.y + (to.y - from.y) * progress)
}

private fun capitalSpaceColor(type: String, index: Int): Color = when (type) {
    "START" -> Color(0xFF80CBC4); "CHANCE" -> Color(0xFFFFD54F); "TAX" -> Color(0xFFFF8A80)
    "STATION" -> Color(0xFFB39DDB); "JAIL", "GO_TO_JAIL" -> Color(0xFFFFAB91)
    "UTILITY" -> Color(0xFF90CAF9); "PARKING" -> Color(0xFFA5D6A7)
    else -> listOf(Color(0xFF80DEEA), Color(0xFFF48FB1), Color(0xFFA5D6A7), Color(0xFFFFCC80))[index % 4]
}

private fun parseCapitalColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrElse { Color.White }
