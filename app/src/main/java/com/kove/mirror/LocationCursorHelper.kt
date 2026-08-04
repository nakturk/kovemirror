package com.kove.mirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

object LocationCursorHelper {

    const val SHAPE_NAV_ARROW  = 0
    const val SHAPE_MOTORCYCLE = 1
    const val SHAPE_CIRCLE_DOT = 2
    const val SHAPE_CROSSHAIR  = 3
    const val SHAPE_PIN        = 4

    val COLOR_PRESETS = listOf(
        Color.parseColor("#0284C7"), // Blue
        Color.parseColor("#EF4444"), // Red
        Color.parseColor("#10B981"), // Green
        Color.parseColor("#EAB308"), // Yellow
        Color.parseColor("#A855F7"), // Purple
        Color.parseColor("#F97316"), // Orange
        Color.parseColor("#FFFFFF")  // White
    )

    fun createCursorBitmap(context: Context, shape: Int, colorInt: Int, sizeDp: Int = 44): Bitmap {
        val density = context.resources.displayMetrics.density
        val px = (sizeDp * density).toInt().coerceAtLeast(32)
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = px / 2f
        val cy = px / 2f
        val radius = px * 0.42f

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt
            style = Paint.Style.FILL
        }

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
        }

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#66000000")
            style = Paint.Style.FILL
        }

        when (shape) {
            SHAPE_NAV_ARROW -> {
                // Navigation Arrow / Triangle Cone
                val path = Path().apply {
                    moveTo(cx, cy - radius)
                    lineTo(cx + radius * 0.75f, cy + radius * 0.8f)
                    lineTo(cx, cy + radius * 0.35f)
                    lineTo(cx - radius * 0.75f, cy + radius * 0.8f)
                    close()
                }

                // Shadow
                canvas.save()
                canvas.translate(0f, 2f * density)
                canvas.drawPath(path, shadowPaint)
                canvas.restore()

                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
            }

            SHAPE_MOTORCYCLE -> {
                // Motorcycle / Bike Silhouette
                val bodyPath = Path().apply {
                    // Front wheel circle
                    addCircle(cx - radius * 0.5f, cy + radius * 0.3f, radius * 0.35f, Path.Direction.CW)
                    // Rear wheel circle
                    addCircle(cx + radius * 0.5f, cy + radius * 0.3f, radius * 0.35f, Path.Direction.CW)
                }

                val framePath = Path().apply {
                    // Handlebars & body frame
                    moveTo(cx - radius * 0.4f, cy - radius * 0.5f)
                    lineTo(cx + radius * 0.1f, cy - radius * 0.5f)
                    lineTo(cx + radius * 0.4f, cy - radius * 0.1f)
                    lineTo(cx + radius * 0.5f, cy + radius * 0.3f)
                    lineTo(cx - radius * 0.5f, cy + radius * 0.3f)
                    close()
                }

                canvas.drawPath(bodyPath, strokePaint)
                canvas.drawPath(bodyPath, fillPaint)
                canvas.drawPath(framePath, fillPaint)
                canvas.drawPath(framePath, strokePaint)
            }

            SHAPE_CIRCLE_DOT -> {
                // Outer ring + Inner solid dot
                val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(100, Color.red(colorInt), Color.green(colorInt), Color.blue(colorInt))
                    style = Paint.Style.FILL
                }

                canvas.drawCircle(cx, cy, radius, outerPaint)
                canvas.drawCircle(cx, cy, radius * 0.55f, fillPaint)
                canvas.drawCircle(cx, cy, radius * 0.55f, strokePaint)
            }

            SHAPE_CROSSHAIR -> {
                // Crosshair Target
                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colorInt
                    style = Paint.Style.STROKE
                    strokeWidth = 3f * density
                }

                canvas.drawCircle(cx, cy, radius * 0.6f, linePaint)
                canvas.drawCircle(cx, cy, radius * 0.6f, strokePaint)

                // Lines
                canvas.drawLine(cx, cy - radius, cx, cy - radius * 0.25f, linePaint)
                canvas.drawLine(cx, cy + radius * 0.25f, cx, cy + radius, linePaint)
                canvas.drawLine(cx - radius, cy, cx - radius * 0.25f, cy, linePaint)
                canvas.drawLine(cx + radius * 0.25f, cy, cx + radius, cy, linePaint)

                canvas.drawCircle(cx, cy, 3f * density, fillPaint)
            }

            SHAPE_PIN -> {
                // Pin Drop Icon
                val path = Path().apply {
                    moveTo(cx, cy + radius)
                    cubicTo(cx - radius * 0.8f, cy, cx - radius * 0.8f, cy - radius * 0.5f, cx, cy - radius * 0.9f)
                    cubicTo(cx + radius * 0.8f, cy - radius * 0.9f, cx + radius * 0.8f, cy, cx, cy + radius)
                    close()
                }

                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)

                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(cx, cy - radius * 0.4f, radius * 0.25f, dotPaint)
            }

            else -> {
                canvas.drawCircle(cx, cy, radius * 0.5f, fillPaint)
                canvas.drawCircle(cx, cy, radius * 0.5f, strokePaint)
            }
        }

        return bitmap
    }
}
