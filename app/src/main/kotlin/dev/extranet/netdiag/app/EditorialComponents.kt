package dev.extranet.netdiag.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.core.report.SupportStatus

/**
 * The shared primitives, re-voiced for a phone-native surface.
 *
 * The names are the ones the screens already call, so the restyle is contained here: cards lose
 * their hairline border for a shadowless white-on-grey separation, buttons lose the upper-case
 * mono for sentence-case Inter, and the eyebrow becomes a plain small title. The stitch survives
 * only as the radar's sweep trail colour.
 *
 * Everything here also has to survive a reader who has set their phone to large text. That rules
 * out fixed-height text containers and single-line truncation on anything but a measured number:
 * a button is a minimum height, not a height, and its label wraps rather than disappearing. See
 * [CappedDisplay] for the other half of that arrangement.
 */

/** A small section label: sentence case, medium weight, quiet. */
@Composable
public fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Editorial.InkMid) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier,
    )
}

/** Kept for the one place a structural rule still helps (under the bottom bar). */
@Composable
public fun Hairline(modifier: Modifier = Modifier, color: Color = Editorial.Hairline) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** The radar's sweep trail, kept as a component so the palette dependency stays in one file. */
@Composable
public fun StitchLine(modifier: Modifier = Modifier) {
    Row(modifier) {
        repeat(5) {
            Box(Modifier.width(4.dp).height(2.dp).background(Editorial.Stitch))
            Box(Modifier.width(4.dp).height(2.dp))
        }
    }
}

/**
 * Primary action: the blue accent, rounded, sentence case, 52 dp for a thumb.
 *
 * The height is a floor rather than a measurement. A fixed 52 dp button is a clipped button the
 * moment someone raises their font size: the label grows, the box does not, and the text is
 * cut off through the middle. So the button is at least a thumb tall and grows past it, the label
 * is allowed two lines, and the padding is vertical rather than zero so the text is never
 * touching the shape.
 */
@Composable
public fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = Editorial.ButtonShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Editorial.Blue,
            contentColor = Editorial.Paper,
            disabledContainerColor = Editorial.Hairline,
            disabledContentColor = Editorial.Muted,
        ),
        modifier = modifier.heightIn(min = BUTTON_MIN_HEIGHT),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Secondary action: white with the quiet border, same floor as the primary. */
@Composable
public fun LineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = Editorial.ButtonShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Editorial.Ink,
            disabledContainerColor = Editorial.Paper,
            disabledContentColor = Editorial.Muted,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Editorial.Hairline),
        modifier = modifier.heightIn(min = BUTTON_MIN_HEIGHT),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Status tag: the word a person would use, in the status colour.
 *
 * SUPPORTED and its siblings are capability-report vocabulary that only the B0/B1 screens still
 * render; they keep their plain treatment.
 */
@Composable
public fun StatusTag(status: SupportStatus, modifier: Modifier = Modifier) {
    val good = status == SupportStatus.SUPPORTED
    Text(
        text = status.name.lowercase().replaceFirstChar { it.uppercase() },
        style = MaterialTheme.typography.labelMedium,
        color = when {
            good -> Editorial.Green
            status == SupportStatus.THROWS -> Editorial.Red
            status == SupportStatus.PERMISSION_DENIED -> Editorial.Red
            else -> Editorial.Muted
        },
        modifier = modifier,
    )
}

/**
 * The screen opener: a small question over a large answer-shaped title.
 *
 * No standfirst paragraph - the first build opened every screen with an explanation of the test,
 * which is documentation, not interface. What a first-time user needs is the question; the how
 * lives behind the Details fold of each result.
 *
 * The title is capped display type: it grows with the reader's font setting up to a point and no
 * further, because this is the one piece of text on the screen that is long, wide and at the top,
 * which is exactly the combination that overflows a small screen at large text sizes.
 */
@Composable
public fun SectionHeader(
    eyebrow: String,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            style = CappedDisplay(MaterialTheme.typography.displaySmall),
            color = Editorial.Ink,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Editorial.InkMid,
            )
        }
    }
}

/**
 * A card: white on grey, 20 dp radius, no border - separation comes from the surface contrast,
 * the way iOS cards do it, rather than from a stroke.
 */
@Composable
public fun SectionCard(
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Editorial.Paper, Editorial.CardShape)
            .padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        if (eyebrow != null) {
            Eyebrow(eyebrow)
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

/**
 * A quiet line for a measured value; green only when the caller says the number is good.
 *
 * Two lines rather than one, because the values here are the app's own numbers and truncating one
 * silently is worse than showing it on a second line.
 */
@Composable
public fun MonoMeta(text: String, modifier: Modifier = Modifier, color: Color = Editorial.Ink) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * How many things fit across before the layout has to stack them.
 *
 * Three questions get asked all over the app in the same shape - "is there room for these side by
 * side, or must they stack?" - and the answer has to account for the reader's font size as well as
 * the width, because the same 360dp screen holds far less text once the text is half as big per
 * character... rather, once the text is one and a half times as big. Returns the columns to use.
 */
@Composable
public fun responsiveColumns(available: Dp, wanted: Int, minColumn: Dp, fontScale: Float): Int {
    val needed = minColumn * wanted * (1f + (fontScale - 1f).coerceAtLeast(0f) * 0.75f)
    if (available <= 0.dp) return wanted.coerceAtLeast(1)
    return (available / needed).toInt().coerceIn(1, wanted)
}

/**
 * A row of [children] that becomes a column when they will not fit.
 *
 * The honest fix for large text on a small screen is to stop insisting on a row. Every layout in
 * this app that used to be a fixed set of columns now asks this first, so a 1.8x reader gets a
 * taller screen that still says everything, rather than a 360dp one that clips it.
 */
@Composable
public fun AdaptiveRow(
    modifier: Modifier = Modifier,
    minColumnWidth: Dp,
    spacing: Dp = 10.dp,
    content: @Composable () -> Unit,
) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = responsiveColumns(maxWidth, 2, minColumnWidth, fontScale)
        if (columns >= 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                content()
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
                content()
            }
        }
    }
}

/** Both buttons sit on this floor, so a row of them lines up whatever the reader's text size. */
public val BUTTON_MIN_HEIGHT: Dp = 52.dp

/** Alignment shared by the label-and-value rows, so a stack of them reads as one column. */
internal val RowLabelAlignment: Alignment.Vertical = Alignment.Top
