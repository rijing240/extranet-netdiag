package dev.extranet.netdiag.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.core.report.SupportStatus

/**
 * The shared primitives, re-voiced for a phone-native surface.
 *
 * The names are the ones the screens already call, so the restyle is contained here: cards lose
 * their hairline border for a shadowless white-on-grey separation, buttons lose the upper-case
 * mono for sentence-case Inter, and the eyebrow becomes a plain small title. The stitch survives
 * only as the radar's sweep trail colour.
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

/** Primary action: the blue accent, rounded, sentence case, 52 dp for a thumb. */
@Composable
public fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = Editorial.ButtonShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Editorial.Blue,
            contentColor = Editorial.Paper,
            disabledContainerColor = Editorial.Hairline,
            disabledContentColor = Editorial.Muted,
        ),
        modifier = modifier.height(52.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Secondary action: white with the quiet border, same height as the primary. */
@Composable
public fun LineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = Editorial.ButtonShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Editorial.Ink,
            disabledContainerColor = Editorial.Paper,
            disabledContentColor = Editorial.Muted,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) Editorial.Hairline else Editorial.Hairline),
        modifier = modifier.height(52.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
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
            style = MaterialTheme.typography.displaySmall,
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

/** A quiet line for a measured value; green only when the caller says the number is good. */
@Composable
public fun MonoMeta(text: String, modifier: Modifier = Modifier, color: Color = Editorial.Ink) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
