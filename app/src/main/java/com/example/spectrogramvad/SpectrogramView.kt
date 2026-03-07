package com.example.spectrogramvad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class SpectrogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val MAX_COLUMNS = 300
        private const val COLUMN_STEP = 512  // samples per column (= fftSize 512 speed)
    }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
    }

    private var dbFloor = -20.0
    private var dbCeil = 80.0
    var sampleRate = 8000

    interface SeekListener {
        fun onSeek(ms: Int)
    }
    private var seekListener: SeekListener? = null
    fun setOnSeekListener(listener: SeekListener) {
        seekListener = listener
    }

    // Dynamic FFT state
    private var fftSize = 256
    private var freqBins = fftSize / 2
    private var offscreen = Bitmap.createBitmap(MAX_COLUMNS, freqBins, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.BLACK)
    }
    private var pixelRow = IntArray(freqBins)
    private val lock = Any()
    private var currentColumn = 0
    private var wrapped = false

    private var sampleBuffer = ShortArray(2048) // Max FFT size
    private var sampleCount = 0
    private var windowCounter = 0

    private var fftReal = DoubleArray(2048)
    private var fftImag = DoubleArray(2048)
    private var hannWindow = makeHannWindow(256)

    // Playback and Paging state
    private var playbackMode = false
    private var cursorPosition = 0f // 0.0 to 1.0
    private var totalDurationMs = 0
    private var currentTimeMs = 0
    private val WINDOW_SIZE_MS = 20000 // 20 seconds

    private fun makeHannWindow(n: Int) = DoubleArray(n) { i ->
        0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
    }

    fun setFftSize(size: Int) {
        synchronized(lock) {
            fftSize = size
            freqBins = size / 2
            if (!playbackMode) {
                offscreen.recycle()
                offscreen = Bitmap.createBitmap(MAX_COLUMNS, freqBins, Bitmap.Config.ARGB_8888)
                offscreen.eraseColor(Color.BLACK)
                currentColumn = 0
                wrapped = false
            }
            pixelRow = IntArray(freqBins)
            sampleBuffer = ShortArray(fftSize)
            sampleCount = 0
            fftReal = DoubleArray(fftSize)
            fftImag = DoubleArray(fftSize)
            hannWindow = makeHannWindow(fftSize)
            windowCounter = 0
        }
        postInvalidate()
    }

    fun getFftSize(): Int = fftSize

    fun setCursorPosition(pos: Float, currentMs: Int) {
        cursorPosition = pos.coerceIn(0f, 1f)
        currentTimeMs = currentMs
        postInvalidate()
    }

    fun clearPlaybackMode() {
        playbackMode = false
        cursorPosition = 0f
        totalDurationMs = 0
        currentTimeMs = 0
        setFftSize(fftSize)
        clear()
    }

    fun setFullSpectrogramFromFile(path: String, totalSamples: Int) {
        synchronized(lock) {
            playbackMode = true
            totalDurationMs = (totalSamples.toLong() * 1000 / sampleRate).toInt()
            val totalColumns = (totalDurationMs.toLong() * MAX_COLUMNS / WINDOW_SIZE_MS).toInt().coerceAtLeast(1)
            
            offscreen.recycle()
            offscreen = Bitmap.createBitmap(totalColumns, freqBins, Bitmap.Config.ARGB_8888)
            offscreen.eraseColor(Color.BLACK)
            wrapped = false
            currentColumn = 0
            
            val file = java.io.File(path)
            if (!file.exists()) return

            val samplesPerColumn = (totalSamples.toDouble() / totalColumns).toInt().coerceAtLeast(1)
            val byteBuffer = ByteArray(samplesPerColumn * 2)
            val shortBuffer = ShortArray(fftSize)
            
            java.io.FileInputStream(file).use { fis ->
                for (col in 0 until totalColumns) {
                    val read = fis.read(byteBuffer)
                    if (read <= 0) break
                    
                    val samplesToProcess = minOf(fftSize, samplesPerColumn)
                    for (i in 0 until samplesToProcess) {
                        val s = ((byteBuffer[i * 2].toInt() and 0xFF) or (byteBuffer[i * 2 + 1].toInt() shl 8)).toShort()
                        shortBuffer[i] = s
                    }
                    for (i in samplesToProcess until fftSize) shortBuffer[i] = 0

                    val n = fftSize
                    val bins = freqBins
                    for (i in 0 until n) {
                        fftReal[i] = shortBuffer[i].toDouble() * hannWindow[i]
                        fftImag[i] = 0.0
                    }
                    fft(fftReal, fftImag, n)
                    
                    val floor = dbFloor
                    val ceil = dbCeil
                    val range = max(1.0, ceil - floor)

                    for (k in 0 until bins) {
                        val mag = sqrt(fftReal[k] * fftReal[k] + fftImag[k] * fftImag[k]) / n
                        val db = 20.0 * log10(mag + 1e-10)
                        val norm = ((db - floor) / range).coerceIn(0.0, 1.0)
                        pixelRow[bins - 1 - k] = heatmapColor(norm)
                    }
                    
                    offscreen.setPixels(pixelRow, 0, 1, col, 0, 1, bins)
                }
            }
        }
        postInvalidate()
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!playbackMode || totalDurationMs <= 0) return super.onTouchEvent(event)
        
        if (event.action == android.view.MotionEvent.ACTION_DOWN || event.action == android.view.MotionEvent.ACTION_MOVE) {
            val labelMarginLeft = 100f
            if (event.x < labelMarginLeft) return true

            val page = currentTimeMs / WINDOW_SIZE_MS
            val pageStartMs = page * WINDOW_SIZE_MS
            
            val plotW = width - labelMarginLeft
            val xInPlot = event.x - labelMarginLeft
            val xFraction = (xInPlot / plotW).coerceIn(0f, 1f)
            
            val seekMs = pageStartMs + (xFraction * WINDOW_SIZE_MS).toInt()
            seekListener?.onSeek(seekMs.coerceIn(0, totalDurationMs))
            return true
        }
        return true
    }

    fun addSamples(samples: ShortArray, length: Int): Int {
        if (playbackMode) return 0
        var columns = 0
        for (i in 0 until length) {
            sampleBuffer[sampleCount++] = samples[i]
            if (sampleCount == fftSize) {
                columns += processWindow()
                sampleCount = 0
            }
        }
        return columns
    }

    private fun processWindow(): Int {
        if (playbackMode) return 0
        val n = fftSize
        val bins = freqBins
        for (i in 0 until n) {
            fftReal[i] = sampleBuffer[i].toDouble() * hannWindow[i]
            fftImag[i] = 0.0
        }

        fft(fftReal, fftImag, n)

        val floor = dbFloor
        val ceil = dbCeil
        val range = max(1.0, ceil - floor)

        for (k in 0 until bins) {
            val mag = sqrt(fftReal[k] * fftReal[k] + fftImag[k] * fftImag[k]) / n
            val db = 20.0 * log10(mag + 1e-10)
            val norm = ((db - floor) / range).coerceIn(0.0, 1.0)
            pixelRow[bins - 1 - k] = heatmapColor(norm)
        }

        var columnsAdded = 0

        if (fftSize < COLUMN_STEP) {
            windowCounter++
            if (windowCounter >= COLUMN_STEP / fftSize) {
                windowCounter = 0
                synchronized(lock) {
                    offscreen.setPixels(pixelRow, 0, 1, currentColumn, 0, 1, bins)
                    currentColumn++
                    if (currentColumn >= MAX_COLUMNS) { currentColumn = 0; wrapped = true }
                }
                columnsAdded = 1
            }
        } else {
            val count = fftSize / COLUMN_STEP
            synchronized(lock) {
                for (c in 0 until count) {
                    offscreen.setPixels(pixelRow, 0, 1, currentColumn, 0, 1, bins)
                    currentColumn++
                    if (currentColumn >= MAX_COLUMNS) { currentColumn = 0; wrapped = true }
                }
            }
            columnsAdded = count
        }

        if (columnsAdded > 0) postInvalidate()
        return columnsAdded
    }

    fun clear() {
        synchronized(lock) {
            sampleCount = 0
            windowCounter = 0
            currentColumn = 0
            wrapped = false
            offscreen.eraseColor(Color.BLACK)
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        val viewW = width
        val viewH = height
        if (viewW == 0 || viewH == 0) return

        val snapColumn: Int
        val snapWrapped: Boolean
        val bins: Int
        synchronized(lock) {
            snapColumn = currentColumn
            snapWrapped = wrapped
            bins = freqBins
        }

        if (snapColumn == 0 && !snapWrapped && !playbackMode) return

        // Space for labels
        val labelMarginLeft = 100f
        val labelMarginBottom = 60f
        val plotRect = Rect(labelMarginLeft.toInt(), 0, viewW, (viewH - labelMarginBottom).toInt())
        val plotW = plotRect.width()
        val plotH = plotRect.height()

        if (playbackMode) {
            val page = currentTimeMs / WINDOW_SIZE_MS
            val pageStartMs = page * WINDOW_SIZE_MS
            
            val totalColumns = offscreen.width
            val startCol = (pageStartMs.toLong() * MAX_COLUMNS / WINDOW_SIZE_MS).toInt()
            val endCol = startCol + MAX_COLUMNS
            
            val src = Rect(startCol, 0, minOf(endCol, totalColumns), bins)
            val segmentW = (src.width().toFloat() / MAX_COLUMNS * plotW).toInt()
            val dst = Rect(plotRect.left, plotRect.top, plotRect.left + segmentW, plotRect.bottom)
            canvas.drawBitmap(offscreen, src, dst, bitmapPaint)
            
            val cursorMsInPage = currentTimeMs - pageStartMs
            val cursorX = plotRect.left + (cursorMsInPage.toFloat() / WINDOW_SIZE_MS * plotW)
            val cursorPaint = Paint().apply {
                color = Color.WHITE
                strokeWidth = 3f
            }
            canvas.drawLine(cursorX, plotRect.top.toFloat(), cursorX, plotRect.bottom.toFloat(), cursorPaint)

            labelPaint.textAlign = Paint.Align.CENTER
            for (i in 0..20 step 5) {
                val tx = plotRect.left + (i.toFloat() / 20 * plotW)
                val timeLabel = "${(pageStartMs / 1000) + i}s"
                canvas.drawText(timeLabel, tx, viewH - 15f, labelPaint)
            }
        } else {
            if (!snapWrapped) {
                val src = Rect(0, 0, snapColumn, bins)
                val dst = Rect(plotRect.left, plotRect.top, plotRect.left + (snapColumn.toFloat() / MAX_COLUMNS * plotW).toInt(), plotRect.bottom)
                canvas.drawBitmap(offscreen, src, dst, bitmapPaint)
            } else {
                val rightPart = MAX_COLUMNS - snapColumn
                val leftW = (rightPart.toFloat() / MAX_COLUMNS * plotW).toInt()
                
                val src1 = Rect(snapColumn, 0, MAX_COLUMNS, bins)
                val dst1 = Rect(plotRect.left, plotRect.top, plotRect.left + leftW, plotRect.bottom)
                canvas.drawBitmap(offscreen, src1, dst1, bitmapPaint)

                val src2 = Rect(0, 0, snapColumn, bins)
                val dst2 = Rect(plotRect.left + leftW, plotRect.top, plotRect.right, plotRect.bottom)
                canvas.drawBitmap(offscreen, src2, dst2, bitmapPaint)
            }

            labelPaint.textAlign = Paint.Align.CENTER
            for (i in 0..20 step 5) {
                val tx = plotRect.left + (i.toFloat() / 20 * plotW)
                canvas.drawText("${i}s", tx, viewH - 15f, labelPaint)
            }
        }

        labelPaint.textAlign = Paint.Align.RIGHT
        val freqMax = sampleRate / 2
        for (i in 0..4) {
            val freq = freqMax * i / 4
            val ty = plotRect.bottom - (i.toFloat() / 4 * plotH)
            canvas.drawText("${freq}Hz", labelMarginLeft - 10f, ty + labelPaint.textSize / 3, labelPaint)
        }
    }

    private fun fft(real: DoubleArray, imag: DoubleArray, n: Int) {
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                var tr = real[i]; real[i] = real[j]; real[j] = tr
                tr = imag[i]; imag[i] = imag[j]; imag[j] = tr
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var step = 2
        while (step <= n) {
            val halfStep = step shr 1
            val angle = -2.0 * PI / step
            val wR = cos(angle)
            val wI = sin(angle)
            var k = 0
            while (k < n) {
                var curR = 1.0
                var curI = 0.0
                for (m2 in 0 until halfStep) {
                    val idx1 = k + m2
                    val idx2 = idx1 + halfStep
                    val tR = curR * real[idx2] - curI * imag[idx2]
                    val tI = curR * imag[idx2] + curI * real[idx2]
                    real[idx2] = real[idx1] - tR
                    imag[idx2] = imag[idx1] - tI
                    real[idx1] += tR
                    imag[idx1] += tI
                    val newCurR = curR * wR - curI * wI
                    curI = curR * wI + curI * wR
                    curR = newCurR
                }
                k += step
            }
            step = step shl 1
        }
    }

    private fun heatmapColor(v: Double): Int {
        val r: Int
        val g: Int
        val b: Int
        when {
            v < 0.25 -> {
                val t = v / 0.25
                r = 0; g = 0; b = (255 * t).toInt()
            }
            v < 0.5 -> {
                val t = (v - 0.25) / 0.25
                r = 0; g = (255 * t).toInt(); b = (255 * (1.0 - t)).toInt()
            }
            v < 0.75 -> {
                val t = (v - 0.5) / 0.25
                r = (255 * t).toInt(); g = 255; b = 0
            }
            else -> {
                val t = (v - 0.75) / 0.25
                r = 255; g = (255 * (1.0 - t)).toInt(); b = 0
            }
        }
        return Color.rgb(r, g, b)
    }
}
