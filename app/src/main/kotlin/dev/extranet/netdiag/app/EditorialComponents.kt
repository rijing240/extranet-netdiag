package dev.extranet.netdiag.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
 * The editorial primitives shared by every screen, composed the way an app composes them.
 *
 * Each one still mirrors a rule from the reference CSS - hairline rules instead of shadows, mono
 * upper-case labels with wide tracking, the pink stitch as the only flourish - but the unit of
 * composition is now the card rather than the ruled page: SectionHeader opens a screen, SectionCard
 * holds each group of readings, and the buttons are sized for a thumb rather than a mouse.
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

/** Primary button: solid black, rounded, mono upper case, 48 dp tall for a thumb. */
@Composable
public fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = Editorial.ButtonShape,
        // Buttons sit three across on the probe screen, so the default 24 dp side padding would
        // push "LIVE 8 S" onto a second line inside a bar whose height is fixed at 48 dp.
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Editorial.Ink,
            contentColor = Editorial.Paper,
            disabledContainerColor = Editorial.Hairline,
            disabledContentColor = Editorial.Muted,
        ),
        modifier = modifier.height(48.dp),
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Secondary button: paper background, 1 px black border, rounded, 48 dp tall. */
@Composable
public fun LineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = Editorial.ButtonShape,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Editorial.Ink,
            disabledContainerColor = Editorial.Paper,
            disabledContentColor = Editorial.Muted,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) Editorial.Ink else Editorial.Hairline),
        modifier = modifier.height(48.dp),
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
 * The screen header: eyebrow, headline, optional standfirst, closed by the pink stitch.
 *
 * Replaces the site's full-bleed hero. A hero is a page's opening statement pinned to the top
 * of a document; an app's opening statement belongs to the scroll, so this is set on the bone
 * canvas rather than on a black band and it moves away with the content.
 */
@Composable
public fun SectionHeader(
    eyebrow: String,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        // The hero set this in translucent paper on black, where it sat at roughly 4.3:1; on
        // bone the same role needs a real tone, so it takes InkMid to keep that contrast.
        Eyebrow(eyebrow, color = Editorial.InkMid)
        Spacer(Modifier.height(6.dp))
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
                color = Editorial.InkSoft,
            )
        }
        Spacer(Modifier.height(10.dp))
        StitchLine()
    }
}

/**
 * A section of the screen, drawn as one touchable block: paper on bone, 16 dp radius, one hairline
 * for an edge instead of a shadow.
 *
 * This is the unit the whole app is composed from. Where the site separated content with a full
 * width rule, the app groups it into a card, so related readings sit together and unrelated ones
 * are visibly apart.
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
            .border(1.dp, Editorial.Hairline, Editorial.CardShape)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        if (eyebrow != null) {
            Eyebrow(eyebrow)
            Spacer(Modifier.height(10.dp))
        }
        content()
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
