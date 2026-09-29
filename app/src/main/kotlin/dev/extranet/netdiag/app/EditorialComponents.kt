package dev.extranet.netdiag.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.core.report.SupportStatus

/**
 * The editorial primitives shared by every screen.
 *
 * Each one mirrors a rule from the reference CSS: hairline rules instead of shadows, square
 * corners, mono upper-case labels with wide tracking, and the pink stitch as the only flourish.
 */

/** The `.eyebrow`: mono, small, upper case, muted, wide-tracked. */
@Composable
public fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Editorial.Muted) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier,
    )
}

/** The 1 px rule the reference uses everywhere instead of card borders or shadows. */
@Composable
public fun Hairline(modifier: Modifier = Modifier, color: Color = Editorial.Hairline) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** The `.stitch-line`: a short dashed pink rule under card titles. */
@Composable
public fun StitchLine(modifier: Modifier = Modifier) {
    Row(modifier) {
        repeat(5) {
            Box(Modifier.width(4.dp).height(2.dp).background(Editorial.Stitch))
            Box(Modifier.width(4.dp).height(2.dp))
        }
    }
}

/** Primary button: solid black, square, mono upper case. */
@Composable
public fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = Editorial.Shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Editorial.Ink,
            contentColor = Editorial.Paper,
            disabledContainerColor = Editorial.Paper,
            disabledContentColor = Editorial.Muted,
        ),
        modifier = modifier,
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge)
    }
}

/** Secondary button: bone background, 1 px black border, square. */
@Composable
public fun LineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = Editorial.Shape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Editorial.Ink,
            disabledContentColor = Editorial.Muted,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) Editorial.Ink else Editorial.Hairline),
        modifier = modifier,
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Status tag: the value in green when it is good, plain mono otherwise.
 *
 * The reference has exactly one accent color for numbers worth trusting; SUPPORTED and a met
 * exit criterion both earn it, everything else stays quiet.
 */
@Composable
public fun StatusTag(status: SupportStatus, modifier: Modifier = Modifier) {
    val good = status == SupportStatus.SUPPORTED
    Text(
        text = status.name,
        style = MaterialTheme.typography.labelSmall,
        color = when {
            good -> Editorial.Green
            status == SupportStatus.THROWS -> MaterialTheme.colorScheme.error
            status == SupportStatus.PERMISSION_DENIED -> MaterialTheme.colorScheme.error
            else -> Editorial.Muted
        },
        modifier = modifier,
    )
}

/**
 * The hero band: solid black, white type, content anchored to the bottom like the reference hero.
 *
 * Used sparingly: one per screen, it is the black block that makes the language readable at a
 * glance on a phone.
 */
@Composable
public fun HeroBand(
    eyebrow: String,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Editorial.Ink)
            .padding(horizontal = 20.dp, vertical = 28.dp),
    ) {
        Eyebrow(eyebrow, color = Editorial.Paper.copy(alpha = 0.45f))
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            color = Editorial.Paper,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Editorial.Paper.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * The marquee: a slow mono ticker of capability words between two hairlines.
 *
 * On the reference it is the wink that makes the page feel alive; here it carries the names of
 * what the app actually measures. Animation is a single infinite translation, cheap on the GPU,
 * and it stops mattering entirely on a screen readers cannot see.
 */
@Composable
public fun Marquee(words: List<String>, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "marquee")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = -1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 25_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "marquee-offset",
    )
    val row = @Composable { text: String ->
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = Editorial.Ink,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
    Column(modifier.fillMaxWidth()) {
        Hairline()
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState(), enabled = false)
                    .padding(start = (-offset * 600).dp),
            ) {
                words.forEach { row(it) }
                words.forEach { row(it) }
            }
        }
        Hairline()
    }
}

/** A numbered row, the `.craft-row`: big grey number, title, description under a hairline. */
@Composable
public fun CraftRow(
    number: String,
    title: String,
    subtitle: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                number,
                style = MaterialTheme.typography.labelLarge,
                color = Editorial.Muted,
                modifier = Modifier.width(44.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                subtitle()
            }
        }
        Spacer(Modifier.height(10.dp))
        Hairline()
    }
}

/** A quiet mono line for metadata, the way the reference sets prices and footers. */
@Composable
public fun MonoMeta(text: String, modifier: Modifier = Modifier, color: Color = Editorial.Green) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Thin bordered container used where the reference uses `.pricing-tier` borders. */
@Composable
public fun BorderedColumn(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, Editorial.Hairline)
            .padding(16.dp),
        content = content,
    )
}
