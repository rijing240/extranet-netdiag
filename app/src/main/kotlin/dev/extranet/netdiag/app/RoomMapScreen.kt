package dev.extranet.netdiag.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.measure.RoomMapLayout
import dev.extranet.netdiag.measure.RoomMapWords

/**
 * The Room Map tab: where in this room the signal is better, drawn from the user's own walk.
 *
 * The screen's job is to keep the picture honest, and to keep it still. Three rules follow from
 * that:
 *
 * **The map lives in a box and cannot leave it.** The drawing is clipped to its panel, so a dot
 * or a marker can never appear over the cards, the tab bar or the rest of the app - and the fit
 * is computed in the rotated frame so that nothing needs clipping in the first place. The clip is
 * the seatbelt, not the brakes.
 *
 * **The picture follows the walk, it does not jump.** The viewport and the marker are eased in the
 * engine and arrive here already smoothed, so nothing on this screen animates: it draws the state
 * it is given, once per frame, and the drawing itself reads the frame inside the draw lambda so a
 * moving map redraws without recomposing the screen around it.
 *
 * **Every dot carries its own doubt.** Older dots fade, dots filed at a last-known position are
 * hollow, and the whole walk is called flat when nothing differs by enough to rank.
 */
@Composable
public fun RoomMapScreen(
    state: RoomMapUiState,
    map: State<RoomMapEngine.Frame>,
    canMap: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onShare: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSignal: () -> Unit,
    onViewport: (widthPx: Float, heightPx: Float, paddingPx: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                eyebrow = "Room Map",
                title = "Where in the room is it best?",
                subtitle = if (canMap) {
                    "Hold your phone upright at chest height and keep it there. Walk slowly around the room."
                } else {
                    "This phone cannot measure which way it turns, so it cannot draw a map. The Signal tab still works."
                },
            )
        }

        when (state) {
            is RoomMapUiState.NeedPermission -> item { MapPermissionNote(state.denied, onOpenSettings) }
            else -> {
                if (!canMap) {
                    item { SectionCard { NoDirectionNote(onOpenSignal) } }
                } else {
                    item { MapPanel(frame = map, onViewport = onViewport) }
                    item { LegendRow() }
                    item { MapCaption(frame = map, state = state) }
                    item { MapControls(state, map, onStart, onStop, onReset, onShare) }
                    if (state is RoomMapUiState.Live && state.finished) {
                        item { MapResult(map) }
                    }
                }
            }
        }

        item { MapHonestyNote() }
    }
}

/**
 * The map panel: a bordered, clipped box with the trail in it.
 *
 * The border is doing real work. A trail of dots on an open background reads as a diagram of
 * the whole screen, and the eye cannot tell a dot that has drifted out of frame from one that was
 * never measured. A visible box says what the picture is: this much room, this many paces, and
 * the ring is where the walk started.
 */
@Composable
private fun MapPanel(frame: State<RoomMapEngine.Frame>, onViewport: (Float, Float, Float) -> Unit) {
    val density = LocalDensity.current
    val paddingPx = with(density) { MAP_PADDING.toPx() }
    val measurer = rememberTextMeasurer()
    // The scale bar's label depends only on the scale, which changes slowly; measured off the
    // frame so it is not re-measured sixty times a second.
    val barLabelStyle = remember {
        TextStyle(fontSize = with(density) { 11.dp.toSp() }, fontWeight = FontWeight.Medium)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Editorial.Paper)
            .border(1.dp, Editorial.Hairline, RoundedCornerShape(16.dp)),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    onViewport(size.width.toFloat(), size.height.toFloat(), paddingPx)
                },
        ) {
            // Read inside the draw lambda: a map that moves every frame redraws without
            // recomposing the screen around it, which is what keeps a walk smooth.
            val current = frame.value
            val placed = RoomMapLayout.place(
                snapshot = current.map,
                startHeadingDegrees = current.startHeadingDegrees,
                fit = current.layout,
                widthPx = size.width,
                heightPx = size.height,
                dotRadiusPx = 9.dp.toPx(),
                nowMillis = current.displayedAtMillis,
                arrowXMeters = current.displayX,
                arrowYMeters = current.displayY,
                paddingPx = paddingPx,
            )

            drawStartMarker(placed.startX, placed.startY)

            for (dot in placed.dots) {
                val colour = dotColour(dot.bucket)
                // Two fades multiply: the dot's own arrival, so a new reading blooms in rather
                // than appearing, and its age, so an old one recedes.
                val alpha = (dot.alpha * dot.appear).coerceIn(0.03f, 1f)
                val radius = dot.radiusPx * (0.6f + 0.4f * dot.appear)
                if (dot.lowConfidence) {
                    // Hollow: the reading is real, the position is the last place the walker was
                    // certainly standing, and the empty middle says so without hiding the dot.
                    drawCircle(
                        color = colour.copy(alpha = alpha),
                        radius = radius,
                        center = Offset(dot.xPx, dot.yPx),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                } else {
                    drawCircle(
                        color = colour.copy(alpha = alpha),
                        radius = radius,
                        center = Offset(dot.xPx, dot.yPx),
                    )
                }
                if (dot.weakest) {
                    drawCircle(
                        color = Editorial.Red.copy(alpha = alpha),
                        radius = radius * 1.9f,
                        center = Offset(dot.xPx, dot.yPx),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                if (dot.best) {
                    drawPath(
                        path = star(Offset(dot.xPx, dot.yPx), radius * 2.2f),
                        color = Editorial.Ink.copy(alpha = alpha),
                    )
                }
            }

            drawWalker(
                x = placed.arrowX,
                y = placed.arrowY,
                headingDegrees = current.displayHeadingDegrees?.let {
                    RoomMapLayout.screenAngleFor(it, current.startHeadingDegrees)
                },
            )
            drawScaleBar(placed.scale, placed.barMeters, size, measurer, barLabelStyle)
        }

        // The empty state, drawn over the box rather than in it: a canvas that draws only an
        // arrow and a ring looks broken until someone explains it.
        val empty by remember { derivedStateOf { frame.value.map.dots.isEmpty() } }
        if (empty) {
            Text(
                text = "Walk slowly to draw the map",
                style = MaterialTheme.typography.bodyMedium,
                color = Editorial.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 56.dp),
            )
        }
    }
}

/** Where the walk began: a fixed ring, so every direction on the map can be read from it. */
private fun DrawScope.drawStartMarker(x: Float, y: Float) {
    drawCircle(color = Editorial.Muted, radius = 7.dp.toPx(), center = Offset(x, y), style = Stroke(2.dp.toPx()))
    drawCircle(color = Editorial.Muted, radius = 2.dp.toPx(), center = Offset(x, y))
}

/**
 * The walker: a halo, then an arrow pointing the way the phone is facing.
 *
 * The halo is what makes the marker findable on top of a trail of dots. The arrow is drawn only
 * when the phone knows which way it faces - a triangle always points somewhere, and pointing it
 * north by default would be a claim the instrument has not made.
 */
private fun DrawScope.drawWalker(x: Float, y: Float, headingDegrees: Double?) {
    drawCircle(color = Editorial.Blue.copy(alpha = 0.14f), radius = 17.dp.toPx(), center = Offset(x, y))
    drawCircle(color = Editorial.Blue.copy(alpha = 0.25f), radius = 9.dp.toPx(), center = Offset(x, y))

    val tip = 15.dp.toPx()
    val base = 11.dp.toPx()
    val arrow = Path().apply {
        moveTo(x, y - tip)
        lineTo(x - base, y + base)
        lineTo(x + base, y + base)
        close()
    }
    rotate(
        degrees = headingDegrees?.toFloat() ?: 0f,
        pivot = Offset(x, y),
    ) {
        // A paper outline under the blue, so the arrow stays legible over a dense green cluster.
        drawPath(path = arrow, color = Editorial.Paper, style = Stroke(width = 4.dp.toPx()))
        drawPath(path = arrow, color = Editorial.Blue)
    }
}

/** A bar of a round number of meters, with its length written on it. */
private fun DrawScope.drawScaleBar(
    scale: Float,
    meters: Double,
    canvas: Size,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    if (scale <= 0f) return
    val length = (meters * scale).toFloat()
    val left = 14.dp.toPx()
    val bottom = canvas.height - 14.dp.toPx()
    val tick = 5.dp.toPx()
    val colour = Editorial.Muted

    drawLine(colour, Offset(left, bottom), Offset(left + length, bottom), strokeWidth = 2.dp.toPx())
    drawLine(colour, Offset(left, bottom - tick), Offset(left, bottom + tick), strokeWidth = 2.dp.toPx())
    drawLine(
        colour,
        Offset(left + length, bottom - tick),
        Offset(left + length, bottom + tick),
        strokeWidth = 2.dp.toPx(),
    )

    val label = measurer.measure(AnnotatedString(formatMeters(meters)), style)
    drawText(
        textLayoutResult = label,
        color = colour,
        topLeft = Offset(left, bottom - label.size.height - tick),
    )
}

/** "2 m" rather than "2.0 m", and "0.5 m" rather than "0 m". */
private fun formatMeters(meters: Double): String =
    if (meters >= 1.0) "${meters.toInt()} m" else "$meters m"

/** The colour legend, always on screen rather than only with a result. */
@Composable
private fun LegendRow() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(Editorial.Green, "Stronger")
        LegendItem(lerp(Editorial.Red, Editorial.Green, 0.5f), "Middle")
        LegendItem(Editorial.Red, "Weaker")
        LegendItem(Editorial.Muted, "Not ranked yet")
    }
}

@Composable
private fun LegendItem(colour: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(12.dp)) {
            drawCircle(
                color = colour,
                radius = 5.dp.toPx(),
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Editorial.InkMid,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

private fun star(center: Offset, radius: Float): Path {
    val path = Path()
    for (point in 0 until 10) {
        val r = if (point % 2 == 0) radius else radius * 0.45f
        val angle = Math.toRadians(-90.0 + point * 36.0)
        val x = center.x + (r * kotlin.math.cos(angle)).toFloat()
        val y = center.y + (r * kotlin.math.sin(angle)).toFloat()
        if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** Red through amber to green, matching the radar's own strength colours. */
private fun dotColour(bucket: Int?): Color = when (bucket) {
    2 -> Editorial.Green
    1 -> lerp(Editorial.Red, Editorial.Green, 0.5f)
    0 -> Editorial.Red
    else -> Editorial.Muted
}

/**
 * The one-line caption: how much has been measured, and which way it points.
 *
 * Derived from the frame rather than read straight off it, so the text recomposes when the
 * sentence changes and not when the picture does. Marking up a map sixty times a second to
 * rewrite an identical string is exactly the kind of work that makes a walk stutter.
 */
@Composable
private fun MapCaption(frame: State<RoomMapEngine.Frame>, state: RoomMapUiState) {
    val caption by remember {
        derivedStateOf {
            RoomMapWords.caption(frame.value.map, frame.value.labelsHidden)
        }
    }
    val progress by remember {
        derivedStateOf {
            val current = frame.value
            if (state is RoomMapUiState.Live && !state.finished) {
                "${current.scanSeconds} s  ·  ${current.steps} steps  ·  ${current.readings} readings"
            } else {
                "${current.steps} steps  ·  ${current.readings} readings"
            }
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = caption,
            style = MaterialTheme.typography.labelLarge,
            color = Editorial.InkSoft,
            textAlign = TextAlign.Center,
        )
        Text(
            text = progress,
            style = MaterialTheme.typography.labelMedium,
            color = Editorial.Muted,
        )
    }
}

@Composable
private fun MapControls(
    state: RoomMapUiState,
    frame: State<RoomMapEngine.Frame>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onShare: (String) -> Unit,
) {
    val finished = state is RoomMapUiState.Live && state.finished
    val summary by remember {
        derivedStateOf { RoomMapWords.summary(frame.value.map, frame.value.steps) }
    }
    SectionCard {
        when {
            state is RoomMapUiState.Live && !state.finished ->
                LineButton("Stop", onClick = onStop, modifier = Modifier.fillMaxWidth())
            finished -> Column {
                InkButton(
                    text = "Share result",
                    onClick = { onShare(summary) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LineButton("Map again", onClick = onReset, modifier = Modifier.fillMaxWidth())
            }
            else -> InkButton(
                text = "Start walking",
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** What the walk found, in words, with the caveat attached rather than buried. */
@Composable
private fun MapResult(frame: State<RoomMapEngine.Frame>) {
    val summary by remember { derivedStateOf { RoomMapWords.summary(frame.value.map, frame.value.steps) } }
    val longWalk by remember { derivedStateOf { RoomMapWords.driftWorthMentioning(frame.value.map) } }
    SectionCard {
        Text("What this walk found", style = MaterialTheme.typography.titleMedium, color = Editorial.Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = Editorial.InkSoft,
        )
        if (longWalk) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "You walked a long way, so the later dots may have drifted from where you " +
                    "actually stood. Shorter walks in one room are more accurate.",
                style = MaterialTheme.typography.bodySmall,
                color = Editorial.Muted,
            )
        }
    }
}

/** The standing caveat: this is a picture of a walk, not a plan of a room. */
@Composable
private fun MapHonestyNote() {
    Text(
        text = "The box is the map and the ring is where you started. Positions come from " +
            "counting steps and are approximate, so this shows where you walked and never the " +
            "walls of the room.",
        style = MaterialTheme.typography.bodySmall,
        color = Editorial.Muted,
    )
}

@Composable
private fun NoDirectionNote(onOpenSignal: () -> Unit) {
    Text(
        text = "The Room Map needs to know which way you turn, which needs a compass or a " +
            "gyroscope. This phone reports neither.",
        style = MaterialTheme.typography.bodyMedium,
        color = Editorial.Ink,
    )
    Spacer(Modifier.height(8.dp))
    LineButton("Open Signal", onClick = onOpenSignal, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun MapPermissionNote(denied: List<String>, onOpenSettings: () -> Unit) {
    Text(
        text = "Reading the radio needs ${denied.joinToString(", ")}. Without it the platform " +
            "hides cell identity and the map has nothing to draw.",
        style = MaterialTheme.typography.bodyMedium,
        color = Editorial.Ink,
    )
    Spacer(Modifier.height(8.dp))
    LineButton("Open settings", onClick = onOpenSettings, modifier = Modifier.fillMaxWidth())
}

/** Empty space between the trail and the box's edge, so dots never touch the border. */
private val MAP_PADDING = 22.dp