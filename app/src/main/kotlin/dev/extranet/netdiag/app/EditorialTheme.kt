package dev.extranet.netdiag.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
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
 * The editorial design language, transcribed from the reference site.
 *
 * Bone-black on near-white, hairline rules instead of card shadows, square corners, monospace
 * labels in upper case with wide tracking, a single green for numbers that matter, and a pink
 * stitch accent. Type carries the hierarchy: Space Grotesk for display, JetBrains Mono for
 * everything that is a label or a measurement.
 */
public object Editorial {

    // Palette, from the reference CSS.
    public val Paper: Color = Color(0xFFfefefe)
    public val Ink: Color = Color(0xFF000000)
    public val InkSoft: Color = Color(0xFF1e1e1e)
    public val Hairline: Color = Color(0xFFdedede)
    public val Muted: Color = Color(0xFFc4c4c4)
    public val Green: Color = Color(0xFF16803c)
    public val Stitch: Color = Color(0xFFf386a1)
    public val StitchDeep: Color = Color(0xFFd45bb6)

    // Square corners everywhere; the reference sets border-radius: 0.
    public val Shape = RoundedCornerShape(0.dp)

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

    /** Light scheme; the app has no dark surfaces in this language yet. */
    public val ColorScheme: ColorScheme = lightColorScheme(
        primary = Ink,
        onPrimary = Paper,
        secondary = InkSoft,
        onSecondary = Paper,
        background = Paper,
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
        shapes = MaterialTheme.shapes.let { s ->
            s.copy(
                extraSmall = Editorial.Shape,
                small = Editorial.Shape,
                medium = Editorial.Shape,
                large = Editorial.Shape,
                extraLarge = Editorial.Shape,
            )
        },
        content = content,
    )
}
