package dev.extranet.netdiag.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.extranet.netdiag.app.R

/**
 * The editorial design language, transcribed from the reference site and then re-set for an app.
 *
 * Bone-black on near-white, hairline rules instead of card shadows, monospace labels in upper
 * case with wide tracking, a single green for numbers that matter, and a pink stitch accent.
 * Type carries the hierarchy: Space Grotesk for display, JetBrains Mono for everything that is
 * a label or a measurement.
 *
 * What changed from the site: the canvas is bone rather than white so cards can be paper on top
 * of it, and corners are rounded. Those two are the difference between a document and a stack
 * of touchable surfaces; everything else in the language carried over unchanged.
 */
public object Editorial {

    // Palette, from the reference CSS, plus one addition the site never needed: Bone is the app
    // background and Paper is what cards sit on it. A page can be one flat white because a page
    // is one flat surface; an app needs its content blocks to separate from the canvas, so the
    // canvas gets a warm bone tint and every card stays near-white on top of it.
    public val Bone: Color = Color(0xFFf2f0ec)
    public val Paper: Color = Color(0xFFfdfdfc)
    public val Ink: Color = Color(0xFF000000)
    public val InkSoft: Color = Color(0xFF1e1e1e)
    public val Hairline: Color = Color(0xFFdedede)
    public val Muted: Color = Color(0xFFc4c4c4)
    // The one tone the site never needed: a page has no inactive tab, but a bottom bar does, and
    // Muted on paper is a hairline's worth of contrast. InkMid sits between Muted and InkSoft so
    // an unselected tab stays readable without competing with the ink pill on the selected one.
    public val InkMid: Color = Color(0xFF6b6b6b)
    public val Green: Color = Color(0xFF16803c)
    public val Stitch: Color = Color(0xFFf386a1)
    public val StitchDeep: Color = Color(0xFFd45bb6)

    // Corners are rounded now: the reference's border-radius: 0 reads as a web page, and the
    // radius is most of what makes a surface feel like a touchable block rather than a region
    // of a document. Cards take the larger radius, controls the smaller one.
    public val CardShape = RoundedCornerShape(16.dp)
    public val ButtonShape = RoundedCornerShape(12.dp)

    public val Grotesk: FontFamily = FontFamily(
        Font(R.font.spacegrotesk_variable, FontWeight.Normal),
        Font(R.font.spacegrotesk_variable, FontWeight.Medium),
        Font(R.font.spacegrotesk_variable, FontWeight.Bold),
    )

    public val Mono: FontFamily = FontFamily(
        Font(R.font.jetbrainsmono_variable, FontWeight.Normal),
        Font(R.font.jetbrainsmono_variable, FontWeight.Medium),
    )

    /**
     * Type scale. The reference's pixel values are converted at 17 px/rem root: the eyebrow and
     * label sizes are the site's exact rem values, the display sizes are clamped by the phone
     * width instead of the viewport.
     */
    public val Typography: Typography = Typography(
        displayLarge = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 44.sp,
            lineHeight = 50.sp,
            letterSpacing = (-0.32).sp,
        ),
        displaySmall = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 30.sp,
            lineHeight = 36.sp,
            letterSpacing = (-0.32).sp,
        ),
        headlineSmall = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 22.sp,
            lineHeight = 26.sp,
            letterSpacing = (-0.32).sp,
        ),
        titleLarge = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            lineHeight = 24.sp,
            letterSpacing = (-0.32).sp,
        ),
        titleMedium = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 17.sp,
            lineHeight = 21.sp,
        ),
        bodyLarge = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = Grotesk,
            fontWeight = FontWeight.Normal,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = Mono,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.5.sp,
        ),
        // The eyebrow: monospace, small, upper case applied at the call site, wide tracking.
        labelMedium = TextStyle(
            fontFamily = Mono,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            letterSpacing = 0.85.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = Mono,
            fontWeight = FontWeight.Normal,
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.5.sp,
        ),
    )

    /**
     * Light scheme. Background is the bone canvas and surface is paper, which is the one change
     * that lets every card read as a distinct object; the rest of the roles are the same black
     * and bone the reference used.
     */
    public val ColorScheme: ColorScheme = lightColorScheme(
        primary = Ink,
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
        error = Color(0xFFa11313),
    )
}

/** The app theme: the editorial language, nothing else. */
@Composable
public fun EditorialTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Editorial.ColorScheme,
        typography = Editorial.Typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(10.dp),
            medium = Editorial.ButtonShape,
            large = Editorial.CardShape,
            extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
