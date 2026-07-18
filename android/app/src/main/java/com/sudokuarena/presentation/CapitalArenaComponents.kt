package com.sudokuarena.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
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
    val balances = state.meta.stringIntMap("balances")
    val owners = state.meta.stringStringMap("propertyOwners")
    val levels = state.meta.stringIntMap("propertyLevels")
    val surpriseCard = state.meta["surpriseCard"] as? Map<*, *>
    val activePlayerId = state.meta["currentPlayerTurn"]?.toString()
    val activeGlow by rememberInfiniteTransition(label = "capitalActiveGlow").animateFloat(
        initialValue = .35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "capitalActiveCell",
    )
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

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color(0xFFF6F8FF), RoundedCornerShape(18.dp)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
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
                    "STATION" -> "TREN"; "JAIL" -> "JAIL"; "PARKING" -> "P"; else -> space.name.take(7)
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
                Color(0xFF101B3B), Offset(unit * 1.25f, unit * 1.25f),
                Size(unit * 8.5f, unit * 8.5f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(28f),
            )
            activePlayerId?.let { activeId ->
                val activePosition = positions[activeId] ?: 0
                val activeColor = players[activeId]?.colorHex?.let(::parseCapitalColor) ?: Color(0xFF00E5FF)
                val activeCell = capitalCellOffset(activePosition, unit)
                drawRoundRect(
                    activeColor.copy(alpha = .18f + activeGlow * .32f),
                    activeCell - Offset(4f, 4f),
                    Size(unit + 8f, unit + 8f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f),
                )
                drawRoundRect(
                    activeColor,
                    activeCell,
                    Size(unit, unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(9f),
                    style = Stroke(2.5f + activeGlow * 3f),
                )
            }

            tokenAnimations.entries.forEach { (playerId, animation) ->
                val finalPosition = positions[playerId] ?: 0
                val occupants = positions.filterValues { it == finalPosition }.keys.sorted()
                val occupantSlot = occupants.indexOf(playerId).coerceAtLeast(0)
                val gridOffset = Offset(
                    unit * (.25f + (occupantSlot % 2) * .38f),
                    unit * (.25f + (occupantSlot / 2) * .38f),
                )
                val tokenCenter = capitalPositionOffset(animation.value, unit) + gridOffset
                val player = players[playerId]
                drawCapitalToken(player?.slot ?: 0, tokenCenter, unit * .26f, player?.colorHex?.let(::parseCapitalColor) ?: Color.White)
            }
        }
        CapitalCenterDeck(
            card = surpriseCard,
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(.48f),
        )
    }
}

@Composable
private fun CapitalCenterDeck(card: Map<*, *>?, modifier: Modifier = Modifier) {
    val cardId = card?.get("id")?.toString()
    val flip = remember { Animatable(0f) }
    val glow by rememberInfiniteTransition(label = "capitalGlow").animateFloat(
        initialValue = .78f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "capitalLogoGlow",
    )
    LaunchedEffect(cardId) {
        if (cardId != null) {
            flip.snapTo(0f)
            flip.animateTo(180f, tween(720))
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "CAPITAL ARENA",
            color = Color(0xFFB8F8FF),
            fontWeight = FontWeight.Black,
            fontSize = 17.sp,
            modifier = Modifier.graphicsLayer {
                alpha = glow
                scaleX = .96f + glow * .05f
                scaleY = scaleX
                shadowElevation = 18f * glow
            },
        )
        Surface(
            modifier = Modifier.fillMaxWidth(.72f).aspectRatio(.72f).graphicsLayer {
                rotationY = if (flip.value <= 90f) flip.value else flip.value - 180f
                cameraDistance = 14f * density
            },
            shape = RoundedCornerShape(14.dp),
            color = if (flip.value <= 90f || card == null) Color(0xFF7C3AED) else Color(0xFFF8FAFF),
            border = BorderStroke(2.dp, Color(0xFF00E5FF)),
            shadowElevation = 12.dp,
        ) {
            if (flip.value <= 90f || card == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("?", fontSize = 44.sp, color = Color.White, fontWeight = FontWeight.Black)
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(card["title"]?.toString().orEmpty(), fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color(0xFF102A56))
                    Text(card["description"]?.toString().orEmpty(), fontSize = 8.sp, color = Color(0xFF37474F))
                }
            }
        }
        Text("MAZO SORPRESA", color = Color(0xFF00E5FF), fontSize = 8.sp, fontWeight = FontWeight.Black)
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
        CapitalStatsPanel(state.players, balances, generic.meta.stringStringMap("propertyOwners"))
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
        AnimatedContent(
            targetState = event,
            transitionSpec = { (scaleIn(tween(300)) + fadeIn()) togetherWith (scaleOut(tween(160)) + fadeOut()) },
            label = "capitalPropertyCard",
        ) { message ->
            if (message.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp),
                    color = if ("conquistó" in message) Color(0xFFE9FFF3) else Color(0xFFEAFBFF),
                    border = BorderStroke(2.dp, if ("conquistó" in message) Color(0xFF00A651) else Color(0xFF00A8FF)),
                    shadowElevation = 8.dp,
                ) {
                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if ("conquistó" in message) Text("TÍTULO DE PROPIEDAD", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color(0xFF00A651))
                        Text("⚡ $message", fontWeight = FontWeight.Black, color = Color(0xFF102A56))
                    }
                }
            }
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

@Composable
private fun CapitalStatsPanel(players: List<Player>, balances: Map<String, Int>, owners: Map<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        players.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                pair.forEach { player ->
                    val color = parseCapitalColor(player.colorHex)
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = color.copy(alpha = .16f),
                        border = BorderStroke(1.dp, color),
                    ) {
                        Column(Modifier.padding(7.dp)) {
                            Text("${capitalTokenGlyph(player.slot)} ${player.name}", maxLines = 1, fontSize = 11.sp, fontWeight = FontWeight.Black)
                            Text("${balances[player.id] ?: 0} cr · ${owners.values.count { it == player.id }} propiedades", fontSize = 9.sp)
                        }
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
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

private fun capitalTokenGlyph(slot: Int): String = when (slot % 4) {
    0 -> "🚗"; 1 -> "🚀"; 2 -> "🎩"; else -> "◆"
}

private fun DrawScope.drawCapitalToken(slot: Int, center: Offset, size: Float, color: Color) {
    drawCircle(Color(0x6600E5FF), size * .72f, center)
    when (slot % 4) {
        0 -> { // Auto neón
            drawRoundRect(Color(0xFF102A56), center - Offset(size * .52f, size * .28f), Size(size * 1.04f, size * .56f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size * .16f))
            drawRoundRect(color, center - Offset(size * .43f, size * .24f), Size(size * .86f, size * .38f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size * .13f))
            drawCircle(Color.White, size * .12f, center + Offset(-size * .3f, size * .27f))
            drawCircle(Color.White, size * .12f, center + Offset(size * .3f, size * .27f))
        }
        1 -> { // Cohete
            val body = Path().apply {
                moveTo(center.x, center.y - size * .6f)
                lineTo(center.x + size * .38f, center.y + size * .32f)
                lineTo(center.x, center.y + size * .2f)
                lineTo(center.x - size * .38f, center.y + size * .32f)
                close()
            }
            drawPath(body, color)
            drawCircle(Color.White, size * .13f, center - Offset(0f, size * .15f))
            val flame = Path().apply {
                moveTo(center.x - size * .16f, center.y + size * .25f)
                lineTo(center.x, center.y + size * .65f)
                lineTo(center.x + size * .16f, center.y + size * .25f)
                close()
            }
            drawPath(flame, Color(0xFFFF6D00))
        }
        2 -> { // Sombrero cibernético
            drawRoundRect(color, center - Offset(size * .34f, size * .44f), Size(size * .68f, size * .65f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size * .12f))
            drawRoundRect(Color(0xFF102A56), center - Offset(size * .54f, -size * .1f), Size(size * 1.08f, size * .24f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size * .1f))
            drawRect(Color.White.copy(alpha = .8f), center - Offset(size * .28f, size * .02f), Size(size * .56f, size * .08f))
        }
        else -> { // Prisma 3D
            val diamond = Path().apply {
                moveTo(center.x, center.y - size * .58f)
                lineTo(center.x + size * .5f, center.y)
                lineTo(center.x, center.y + size * .58f)
                lineTo(center.x - size * .5f, center.y)
                close()
            }
            drawPath(diamond, color)
            drawLine(Color.White.copy(alpha = .8f), center - Offset(0f, size * .5f), center, size * .08f)
            drawLine(Color(0xFF102A56), center, center + Offset(size * .42f, 0f), size * .08f)
        }
    }
    drawCircle(Color.White.copy(alpha = .9f), size * .68f, center, style = Stroke(size * .07f))
}

private fun parseCapitalColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrElse { Color.White }
