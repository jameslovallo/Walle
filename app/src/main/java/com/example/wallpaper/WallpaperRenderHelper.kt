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
import android.graphics.Matrix
import androidx.core.graphics.PathParser
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

        // Fill background with black so opacity brightness works as requested
        canvas.drawColor(Color.BLACK)

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

        // Setup Paint with Brightness (opacity over black) and Grayscale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (filters.brightness.coerceIn(0f, 1f) * 255).toInt()
        }

        if (filters.grayscale > 0.01f) {
            val cm = ColorMatrix()
            cm.setSaturation((1.0f - filters.grayscale).coerceIn(0f, 1f))
            paint.colorFilter = ColorMatrixColorFilter(cm)
        }

        val blurRadius = (filters.blur.coerceIn(0f, 1f) * 25f).toInt()
        if (blurRadius >= 1) {
            try {
                val cropped = Bitmap.createBitmap(bitmap, srcRect.left, srcRect.top, srcRect.width(), srcRect.height())
                val scaleFactor = 0.25f
                val smallW = max(1, (cropped.width * scaleFactor).toInt())
                val smallH = max(1, (cropped.height * scaleFactor).toInt())
                val scaled = Bitmap.createScaledBitmap(cropped, smallW, smallH, true)
                val blurred = fastBlur(scaled, (blurRadius * scaleFactor).toInt().coerceAtLeast(1))
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
            val alpha = (filters.vignette.coerceIn(0f, 1f) * 240).toInt()
            val edgeColor = Color.argb(alpha, 0, 0, 0)

            vignettePaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(Color.TRANSPARENT, edgeColor),
                floatArrayOf(0.35f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), vignettePaint)
        }
    }

    enum class FeedbackIconType {
        SKIP,
        PAUSE,
        RESUME
    }

    /**
     * Draws action feedback icon (Skip, Pause, Resume) on the launcher.
     */
    fun drawFeedbackIcon(
        canvas: Canvas,
        x: Float,
        y: Float,
        type: FeedbackIconType,
        progress: Float
    ) {
        val alpha = ((1.0f - progress).coerceIn(0f, 1f) * 255).toInt()
        val scale = 1.0f + 0.3f * progress
        val circleRadius = 48f * scale
        val iconSize = 44f * scale

        // Draw circular background
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.alpha = ((1.0f - progress).coerceIn(0f, 1f) * 165).toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(x, y, circleRadius, bgPaint)

        // Draw accent ring
        val ringColor = when (type) {
            FeedbackIconType.SKIP -> Color.WHITE
            FeedbackIconType.PAUSE -> Color.parseColor("#FFB74D")
            FeedbackIconType.RESUME -> Color.parseColor("#81C784")
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ringColor
            this.alpha = ((1.0f - progress).coerceIn(0f, 1f) * 200).toInt()
            strokeWidth = 3f * scale
            style = Paint.Style.STROKE
        }
        canvas.drawCircle(x, y, circleRadius, strokePaint)

        val pathData = when (type) {
            FeedbackIconType.SKIP -> "M6,18L14.5,12L6,6V18M16,6V18H18V6H16Z"
            FeedbackIconType.PAUSE -> "M14,19H18V5H14M6,19H10V5H6V19Z"
            FeedbackIconType.RESUME -> "M8,5.14V19.14L19,12.14L8,5.14Z"
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.alpha = alpha
            style = Paint.Style.FILL
        }

        canvas.save()
        canvas.translate(x, y)
        val path = PathParser.createPathFromPathData(pathData)
        val s = iconSize / 24f
        val matrix = Matrix().apply {
            postTranslate(-12f, -12f)
            postScale(s, s)
        }
        path.transform(matrix)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    /**
     * Draws the red heart or broken heart feedback icon on the launcher,
     * consistent with the Material Design icons used in the app.
     */
    fun drawHeartFeedback(
        canvas: Canvas,
        x: Float,
        y: Float,
        isFavorited: Boolean,
        progress: Float // 0f (start) to 1f (end)
    ) {
        val alpha = ((1.0f - progress).coerceIn(0f, 1f) * 255).toInt()
        val scale = 1.0f + 0.35f * progress
        val baseSize = 88f * scale

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E53935") // Material Red 600
            this.alpha = alpha
            style = Paint.Style.FILL
        }

        canvas.save()
        canvas.translate(x, y)

        // Material Design Favorite 24x24 icon path
        val materialHeartPathData = "M12,21.35L10.55,20.03C5.4,15.36 2,12.28 2,8.5C2,5.42 4.42,3 7.5,3C9.24,3 10.91,3.81 12,5.09C13.09,3.81 14.76,3 16.5,3C19.58,3 22,5.42 22,8.5C22,12.28 18.6,15.36 13.45,20.04L12,21.35Z"
        val path = PathParser.createPathFromPathData(materialHeartPathData)
        val s = baseSize / 24f
        val matrix = Matrix().apply {
            postTranslate(-12f, -12f)
            postScale(s, s)
        }
        path.transform(matrix)

        canvas.drawPath(path, paint)

        if (!isFavorited) {
            // Draw clean lightning crack through center for broken heart
            val crackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                this.alpha = alpha
                strokeWidth = 3.5f * s
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val crack = Path()
            crack.moveTo(0f, -8f * s)
            crack.lineTo(-3f * s, -2f * s)
            crack.lineTo(2f * s, 3f * s)
            crack.lineTo(-1f * s, 8f * s)
            crack.lineTo(0f, 11f * s)
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
