package com.example.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.example.data.model.ModeFilterSettings
import kotlin.math.hypot
import kotlin.math.max

object WallpaperRenderHelper {

    fun renderWallpaperToCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        horizontalOffset: Float,
        filters: ModeFilterSettings
    ) {
        if (screenWidth <= 0 || screenHeight <= 0) return

        val bWidth = bitmap.width.toFloat()
        val bHeight = bitmap.height.toFloat()
        val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()
        val bitmapAspect = bWidth / bHeight

        val srcRect = Rect()
        if (bitmapAspect >= screenAspect) {
            // Bitmap is wider than screen: crop horizontally using horizontalOffset
            val visibleWidthInBitmap = bHeight * screenAspect
            val maxScrollX = max(0f, bWidth - visibleWidthInBitmap)
            val left = (horizontalOffset.coerceIn(0f, 1f) * maxScrollX).toInt()
            srcRect.set(left, 0, (left + visibleWidthInBitmap).toInt(), bHeight.toInt())
        } else {
            // Bitmap is taller than screen: center vertically
            val visibleHeightInBitmap = bWidth / screenAspect
            val maxScrollY = max(0f, bHeight - visibleHeightInBitmap)
            val top = (0.5f * maxScrollY).toInt()
            srcRect.set(0, top, bWidth.toInt(), (top + visibleHeightInBitmap).toInt())
        }

        val dstRect = Rect(0, 0, screenWidth, screenHeight)

        // Setup Paint with Brightness and Contrast
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val cm = ColorMatrix()
        val c = filters.contrast
        val b = (filters.brightness - 1.0f) * 255f
        val t = (1.0f - c) * 128f + b

        cm.set(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(cm)

        if (filters.blur > 1f) {
            // If blur is active, create downscaled cropped bitmap and blur
            try {
                val cropped = Bitmap.createBitmap(bitmap, srcRect.left, srcRect.top, srcRect.width(), srcRect.height())
                val scaleFactor = 0.25f
                val smallW = max(1, (cropped.width * scaleFactor).toInt())
                val smallH = max(1, (cropped.height * scaleFactor).toInt())
                val scaled = Bitmap.createScaledBitmap(cropped, smallW, smallH, true)
                val blurred = fastBlur(scaled, (filters.blur * scaleFactor).toInt().coerceAtLeast(1))
                canvas.drawBitmap(blurred, null, dstRect, paint)
                if (cropped != bitmap) cropped.recycle()
                if (scaled != blurred) scaled.recycle()
            } catch (e: Exception) {
                canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            }
        } else {
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        }

        // Apply Vignette if active
        if (filters.vignette > 0.02f) {
            val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val cx = screenWidth / 2f
            val cy = screenHeight / 2f
            val radius = hypot(cx, cy)
            val alpha = (filters.vignette.coerceIn(0f, 1f) * 230).toInt()
            val edgeColor = Color.argb(alpha, 0, 0, 0)

            vignettePaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(Color.TRANSPARENT, edgeColor),
                floatArrayOf(0.4f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), vignettePaint)
        }
    }

    /**
     * Draws the red heart or broken heart feedback icon on the launcher.
     */
    fun drawHeartFeedback(
        canvas: Canvas,
        x: Float,
        y: Float,
        isFavorited: Boolean,
        progress: Float // 0f (start) to 1f (end)
    ) {
        val alpha = ((1.0f - progress).coerceIn(0f, 1f) * 255).toInt()
        val scale = 1.0f + 0.3f * progress // slight expanding pop effect
        val baseSize = 80f * scale

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF1744") // Vibrant Red
            this.alpha = alpha
            style = Paint.Style.FILL
        }

        canvas.save()
        canvas.translate(x, y)

        val path = Path()
        // Draw heart shape centered at (0, 0)
        val s = baseSize / 100f
        path.moveTo(0f, -20f * s)
        path.cubicTo(-40f * s, -70f * s, -90f * s, -10f * s, 0f, 70f * s)
        path.cubicTo(90f * s, -10f * s, 40f * s, -70f * s, 0f, -20f * s)
        path.close()

        canvas.drawPath(path, paint)

        if (!isFavorited) {
            // Draw crack / lightning line through center for broken heart
            val crackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                this.alpha = alpha
                strokeWidth = 6f * s
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
            val crack = Path()
            crack.moveTo(0f, -40f * s)
            crack.lineTo(-12f * s, -15f * s)
            crack.lineTo(10f * s, 10f * s)
            crack.lineTo(-5f * s, 35f * s)
            crack.lineTo(0f, 65f * s)
            canvas.drawPath(crack, crackPaint)
        }

        canvas.restore()
    }

    /**
     * Stack Blur Algorithm for Android Bitmaps.
     */
    fun fastBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return sentBitmap
        val w = sentBitmap.width
        val h = sentBitmap.height
        val pix = IntArray(w * h)
        sentBitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (idx in 0 until 256 * divsum) {
            dv[idx] = idx / divsum
        }

        yi = 0
        var yw = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        for (curY in 0 until h) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            for (curI in -radius..radius) {
                p = pix[yi + curI.coerceIn(0, wm)]
                sir = stack[curI + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rbs = r1 - Math.abs(curI)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (curI > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            stackpointer = radius

            for (curX in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (curY == 0) {
                    vmin[curX] = (curX + radius + 1).coerceAtMost(wm)
                }
                p = pix[yw + vmin[curX]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
            }
            yw += w
        }

        for (curX in 0 until w) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            yp = -radius * w
            for (curI in -radius..radius) {
                yi = max(0, yp) + curX
                sir = stack[curI + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                rbs = r1 - Math.abs(curI)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (curI > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (curI < hm) {
                    yp += w
                }
            }
            yi = curX
            stackpointer = radius
            for (curY in 0 until h) {
                pix[yi] = (-0x1000000 and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (curX == 0) {
                    vmin[curY] = (curY + r1).coerceAtMost(hm) * w
                }
                p = curX + vmin[curY]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
            }
        }

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        output.setPixels(pix, 0, w, 0, 0, w, h)
        return output
    }
}
