package dev.extranet.netdiag.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import dev.extranet.netdiag.measure.RoomMapLayout
import dev.extranet.netdiag.measure.RoomMapModel
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Room Map as a picture the user can send to someone.
 *
 * A screenshot would do at a pinch, but a screenshot of this screen carries the app's chrome, its
 * buttons, and whatever else happened to be on it. This draws the map again from the same
 * [RoomMapLayout] the screen uses - so the picture is the same picture, not a second opinion -
 * with the summary written underneath it. Sharing is the one place a result leaves the app, and it
 * leaves as a single PNG with its caveats printed on it rather than as dots a reader has to
 * interpret.
 */
public object RoomMapPicture {

    private const val WIDTH = 1080
    private const val HEIGHT = 1350
    private const val MAP_HEIGHT = 1080

    /**
     * Draws the map and writes it to the app's cache, returning the file, or null when there is
     * nothing worth sharing yet or the picture cannot be written.
     */
    public fun write(
        context: Context,
        snapshot: RoomMapModel.Snapshot,
        startHeadingDegrees: Double,
        nowX: Double,
        nowY: Double,
        headingDegrees: Double?,
        summary: String,
    ): File? {
        if (snapshot.dots.isEmpty()) return null

        return runCatching {
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(BACKGROUND)

            drawMap(canvas, snapshot, startHeadingDegrees, nowX, nowY, headingDegrees)
            drawText(canvas, summary)

            val directory = File(context.cacheDir, "maps").apply { mkdirs() }
            val file = File(directory, "room-map-${System.currentTimeMillis()}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            file
        }.getOrNull()
    }

    private fun drawMap(
        canvas: Canvas,
        snapshot: RoomMapModel.Snapshot,
        startHeadingDegrees: Double,
        nowX: Double,
        nowY: Double,
        headingDegrees: Double?,
    ) {
        // The picture is fitted on its own terms rather than reusing the screen's viewport: a
        // shared export must contain the whole walk whatever the phone was showing at the time,
        // and the screen may have been mid-walk with only part of the trail in view.
        val fit = RoomMapLayout.fit(
            snapshot = snapshot,
            startHeadingDegrees = startHeadingDegrees,
            arrowXMeters = nowX,
            arrowYMeters = nowY,
            widthPx = WIDTH.toFloat(),
            heightPx = MAP_HEIGHT.toFloat(),
            paddingPx = 90f,
        )
        val placed = RoomMapLayout.place(
            snapshot = snapshot,
            startHeadingDegrees = startHeadingDegrees,
            fit = fit,
            widthPx = WIDTH.toFloat(),
            heightPx = MAP_HEIGHT.toFloat(),
            dotRadiusPx = 26f,
            nowMillis = Long.MAX_VALUE,
            arrowXMeters = nowX,
            arrowYMeters = nowY,
            paddingPx = 90f,
        )

        // A hairline frame and a scale bar, so a reader can see how big the walk was. There are
        // no walls and no room outline: this is where the user went, and nothing more.
        canvas.drawRect(40f, 40f, WIDTH - 40f, MAP_HEIGHT - 40f, hairline())

        for (dot in placed.dots) {
            val paint = dotPaint(dot.bucket, dot.alpha, dot.lowConfidence)
            canvas.drawCircle(dot.xPx, dot.yPx, dot.radiusPx, paint)
            if (dot.best) {
                canvas.drawPath(star(dot.xPx, dot.yPx, dot.radiusPx * 2.1f), starPaint())
            }
            if (dot.weakest) {
                canvas.drawCircle(dot.xPx, dot.yPx, dot.radiusPx * 1.9f, weakestPaint())
            }
        }

        val arrow = Path().apply {
            moveTo(placed.arrowX, placed.arrowY)
            lineTo(placed.arrowX - 22f, placed.arrowY + 34f)
            lineTo(placed.arrowX + 22f, placed.arrowY + 34f)
            close()
        }
        canvas.save()
        if (headingDegrees != null) {
            canvas.rotate(
                RoomMapLayout.screenAngleFor(headingDegrees, startHeadingDegrees).toFloat(),
                placed.arrowX,
                placed.arrowY,
            )
        }
        canvas.drawPath(arrow, starPaint())
        canvas.restore()

        // The legend, because a picture that leaves the app has no screen to explain it.
        val legend = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK_SOFT
            textSize = 34f
        }
        val legendText = when {
            !snapshot.coloured -> "Signal: not enough measured to compare places"
            snapshot.neutral -> "Signal: about the same everywhere"
            else -> "Green: strongest third of this walk   ·   Amber: middle   ·   Red: weakest third"
        }
        canvas.drawText(legendText, 70f, MAP_HEIGHT - 70f, legend)
    }

    private fun drawText(canvas: Canvas, summary: String) {
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = 52f
            isFakeBoldText = true
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = 38f
        }
        val note = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK_SOFT
            textSize = 32f
        }

        canvas.drawText("Room Map", 70f, MAP_HEIGHT + 80f, title)
        var y = MAP_HEIGHT + 150f
        y = drawWrapped(canvas, summary, body, 70f, y, WIDTH - 140f)
        y += 24f
        drawWrapped(
            canvas,
            "Positions are approximate: they come from counting steps, and they get less " +
                "accurate the longer the walk. Signal colours are relative to this walk only.",
            note,
            70f,
            y,
            WIDTH - 140f,
        )
    }

    /** Draws text wrapped to [maxWidth], returning the y it finished at. */
    private fun drawWrapped(
        canvas: Canvas,
        text: String,
        paint: Paint,
        x: Float,
        startY: Float,
        maxWidth: Float,
    ): Float {
        val words = text.split(" ")
        var line = StringBuilder()
        var y = startY
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                canvas.drawText(line.toString(), x, y, paint)
                y += paint.textSize * 1.35f
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) {
            canvas.drawText(line.toString(), x, y, paint)
            y += paint.textSize * 1.35f
        }
        return y
    }

    private fun dotPaint(bucket: Int?, alpha: Float, lowConfidence: Boolean): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when (bucket) {
            2 -> STRONG
            1 -> MIDDLE
            0 -> WEAK
            else -> NEUTRAL
        }
        this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        if (lowConfidence) {
            // A ring rather than a disc: the reading is real, the position is the last place the
            // walker was certainly standing, and the hollow centre says so without hiding the dot.
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
    }

    private fun hairline(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HAIRLINE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private fun starPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        style = Paint.Style.FILL
    }

    private fun weakestPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WEAK
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private fun star(cx: Float, cy: Float, radius: Float): Path {
        val path = Path()
        for (point in 0 until 10) {
            val r = if (point % 2 == 0) radius else radius * 0.45f
            val angle = Math.toRadians(-90.0 + point * 36.0)
            val x = cx + (r * cos(angle)).toFloat()
            val y = cy + (r * sin(angle)).toFloat()
            if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private val BACKGROUND = Color.parseColor("#FAF7F0")
    private val INK = Color.parseColor("#1A1A1A")
    private val INK_SOFT = Color.parseColor("#5A5A5A")
    private val HAIRLINE = Color.parseColor("#D8D2C6")
    private val STRONG = Color.parseColor("#2E7D4F")
    private val MIDDLE = Color.parseColor("#C99A2E")
    private val WEAK = Color.parseColor("#B4463C")
    private val NEUTRAL = Color.parseColor("#8A857C")
}