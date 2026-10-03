package dev.extranet.netdiag.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.extranet.netdiag.app.R

/**
 * The app's look: a calm, phone-native surface built on Inter.
 *
 * The first build dressed itself as an editorial document - bone canvas, hairline rules,
 * upper-case monospace labels, a pink stitch - which read as a developer instrument rather than
 * something to hand to a person whose question is "why is my internet slow". The structure that
 * mattered carried over: one canvas, cards floating on it, big readable type, a small set of
 * status colours. What changed is the voice: soft grey instead of bone, rounded white cards, a
 * single blue accent, and sentence case everywhere.
 *
 * The typeface is Inter, not Apple's San Francisco: SF is licensed only for Apple platforms and
 * cannot legally ship inside an Android app. Inter is the closest open-licensed match - same
 * neutral grotesque, same tall x-height, designed for screens - so the app reads "modern phone"
 * without borrowing a licence it does not have.
 */
public object Editorial {

    // Canvas and surface, in the iOS-system register: a cool light grey the cards float on.
    public val Bone: Color = Color(0xFFF2F2F7)
    public val Paper: Color = Color(0xFFFFFFFF)

    // Text: near-black rather than pure black, the way phone UIs set body copy.
    public val Ink: Color = Color(0xFF111118)
    public val InkSoft: Color = Color(0xFF3A3A43)
    public val InkMid: Color = Color(0xFF6E6E78)
    public val Muted: Color = Color(0xFFAEAEB6)
    public val Hairline: Color = Color(0xFFE4E4EA)

    // One accent for actions and focus; the status colours do the talking otherwise.
    public val Blue: Color = Color(0xFF0A66FF)
    public val BlueDeep: Color = Color(0xFF084EC0)

    // Status: green is good, amber is workable-but-worth-fixing, red is a real problem.
    public val Green: Color = Color(0xFF1E9E55)
    public val Amber: Color = Color(0xFFD98A00)
    public val Red: Color = Color(0xFFE03131)

    // The pink pair survives only as the radar's sweep trail: a soft flourish on one screen,
    // where a rotating dial wants a colour that is not a status.
    public val Stitch: Color = Color(0xFFF386A1)
    public val StitchDeep: Color = Color(0xFFD45BB6)

    public val CardShape = RoundedCornerShape(20.dp)
    public val ButtonShape = RoundedCornerShape(14.dp)

    public val Grotesk: FontFamily = FontFamily(
        Font(R.font.inter_variable, FontWeight.Normal),
        Font(R.font.inter_variable, FontWeight.Medium),
        Font(R.font.inter_variable, FontWeight.SemiBold),
        Font(R.font.inter_variable, FontWeight.Bold),
    )

    /**
     * Measurements still set in a mono face - a number that updates every second reads better
     * in a tabular font - but quiet, sentence case, no wide tracking.
     */
    public val Mono: FontFamily = FontFamily(
        Font(R.font.jetbrainsmono_variable, FontWeight.Normal),
        Font(R.font.jetbrainsmono_variable, FontWeight.Medium),
    )

    /**
     * Type scale on Inter. Weights do the hierarchy now: semibold titles, regular body, medium
     * labels. Sizes grew slightly over the editorial scale because Inter sets smaller than
     * Space Grotesk at the same point size.
     */
    public val Typography: Typography = Typography(
        displayLarge = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.Bold,
            fontSize = 40.sp,
            lineHeight = 46.sp,
            letterSpacing = (-0.5).sp,
        ),
        displaySmall = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            letterSpacing = (-0.4).sp,
        ),
        headlineSmall = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 21.sp,
            lineHeight = 26.sp,
            letterSpacing = (-0.3).sp,
        ),
        titleLarge = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 19.sp,
            lineHeight = 24.sp,
            letterSpacing = (-0.2).sp,
        ),
        titleMedium = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 21.sp,
        ),
        bodyLarge = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 23.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.Normal,
            fontSize = 14.5.sp,
            lineHeight = 21.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 12.5.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.1.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            letterSpacing = 0.2.sp,
        ),
    )

    /**
     * Light scheme. Primary is the blue accent rather than ink, so every Material control that
     * inherits it - switches, cursors, ripples - lands on the accent without per-call colours.
     */
    public val ColorScheme: ColorScheme = lightColorScheme(
        primary = Blue,
        onPrimary = Paper,
        secondary = InkSoft,
        onSecondary = Paper,
        background = Bone,
        onBackground = Ink,
        surface = Paper,
        onSurface = Ink,
        surfaceVariant = Paper,
        onSurfaceVariant = InkSoft,
        outline = Muted,
        outlineVariant = Hairline,
        error = Red,
    )
}

/** The app theme: Inter, soft grey, white cards, one blue accent. */
@Composable
public fun EditorialTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Editorial.ColorScheme,
        typography = Editorial.Typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(12.dp),
            medium = Editorial.ButtonShape,
            large = Editorial.CardShape,
            extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}

/**
 * How far the very large type is allowed to grow with the reader's font setting.
 *
 * People do set their phones to big text, often because they need it, and an app that breaks at
 * 1.8x is an app those people cannot use. So body copy, labels and buttons follow the setting all
 * the way. The display sizes do not, and this is the trade-off made explicit rather than left to
 * chance: at 28sp a title is already the largest thing on a 360dp screen, and letting it grow to
 * 50sp turns a two-word question into a three-line banner that pushes the answer off the screen
 * and the button below the fold. Capped at 1.3x the title still grows visibly, the sentence still
 * wraps instead of clipping, and everything underneath stays where the thumb expects it.
 */
public const val DISPLAY_FONT_SCALE_CAP: Float = 1.3f

/**
 * A display style that grows with the reader's font setting, but only to [DISPLAY_FONT_SCALE_CAP].
 *
 * Applied by hand to the handful of places that set a title or a headline-sized number, rather
 * than by rewriting the type scale, because the same 28sp has to keep its full size for a caller
 * that already knows it is drawing a short fixed string into a wide box.
 */
@Composable
public fun CappedDisplay(style: TextStyle, maxScale: Float = DISPLAY_FONT_SCALE_CAP): TextStyle {
    val fontScale = LocalDensity.current.fontScale
    if (fontScale <= maxScale) return style
    val factor = maxScale / fontScale
    return style.copy(
        fontSize = style.fontSize * factor,
        lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * factor else style.lineHeight,
        letterSpacing = if (style.letterSpacing.isSpecified) {
            style.letterSpacing * factor
        } else {
            style.letterSpacing
        },
    )
}
