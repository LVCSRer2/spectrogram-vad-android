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
        color = 0x99FFFFFF.toInt()
        textSize = 20f
    }

    private var dbFloor = -20.0
    private var dbCeil = 80.0
    var sampleRate = 8000

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

    private var sampleBuffer = ShortArray(fftSize)
    private var sampleCount = 0
    private var windowCounter = 0

    private var fftReal = DoubleArray(fftSize)
    private var fftImag = DoubleArray(fftSize)
    private var hannWindow = makeHannWindow(fftSize)

    private fun makeHannWindow(n: Int) = DoubleArray(n) { i ->
        0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
    }

    fun setFftSize(size: Int) {
        synchronized(lock) {
            fftSize = size
            freqBins = size / 2
            offscreen.recycle()
            offscreen = Bitmap.createBitmap(MAX_COLUMNS, freqBins, Bitmap.Config.ARGB_8888)
            offscreen.eraseColor(Color.BLACK)
            pixelRow = IntArray(freqBins)
            sampleBuffer = ShortArray(fftSize)
            sampleCount = 0
            fftReal = DoubleArray(fftSize)
            fftImag = DoubleArray(fftSize)
            hannWindow = makeHannWindow(fftSize)
            windowCounter = 0
            currentColumn = 0
            wrapped = false
        }
        postInvalidate()
    }

    fun getFftSize(): Int = fftSize

    private var playbackMode = false
    private var cursorPosition = 0f // 0.0 to 1.0

    fun setCursorPosition(pos: Float) {
        cursorPosition = pos.coerceIn(0f, 1f)
        postInvalidate()
    }

    fun clearPlaybackMode() {
        playbackMode = false
        cursorPosition = 0f
        clear()
    }

    /**
     * Renders a full spectrogram from a PCM file into the offscreen bitmap.
     * For simplicity, we just downsample/segment to fit MAX_COLUMNS.
     */
    fun setFullSpectrogramFromFile(path: String, totalSamples: Int) {
        synchronized(lock) {
            playbackMode = true
            offscreen.eraseColor(Color.BLACK)
            wrapped = false
            currentColumn = 0
            
            val file = java.io.File(path)
            if (!file.exists()) return

            val samplesPerColumn = (totalSamples / MAX_COLUMNS).coerceAtLeast(fftSize)
            val byteBuffer = ByteArray(samplesPerColumn * 2)
            val shortBuffer = ShortArray(fftSize)
            
            java.io.FileInputStream(file).use { fis ->
                for (col in 0 until MAX_COLUMNS) {
                    val read = fis.read(byteBuffer)
                    if (read <= 0) break
                    
                    // Use the first fftSize samples of this segment for this column
                    for (i in 0 until fftSize) {
                        val s = ((byteBuffer[i * 2].toInt() and 0xFF) or (byteBuffer[i * 2 + 1].toInt() shl 8)).toShort()
                        shortBuffer[i] = s
                    }
                    
                    // Compute FFT for this column
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
                    currentColumn++
                }
            }
        }
        postInvalidate()
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
            fftReal[i] = sampleBuffer[i] * hannWindow[i]
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
            // Small FFT: emit one column every COLUMN_STEP/fftSize windows
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
            // Large FFT: emit fftSize/COLUMN_STEP columns per window
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

        val dst = Rect(0, 0, viewW, viewH)

        if (playbackMode) {
            val src = Rect(0, 0, MAX_COLUMNS, bins)
            canvas.drawBitmap(offscreen, src, dst, bitmapPaint)
            
            // Draw cursor
            val cursorX = cursorPosition * viewW
            val cursorPaint = Paint().apply {
                color = Color.WHITE
                strokeWidth = 3f
            }
            canvas.drawLine(cursorX, 0f, cursorX, viewH.toFloat(), cursorPaint)
        } else if (!snapWrapped) {
            val src = Rect(0, 0, snapColumn, bins)
            canvas.drawBitmap(offscreen, src, dst, bitmapPaint)
        } else {
            val rightPart = MAX_COLUMNS - snapColumn
            val leftW = (rightPart.toLong() * viewW / MAX_COLUMNS).toInt()
            if (rightPart > 0) {
                val src1 = Rect(snapColumn, 0, MAX_COLUMNS, bins)
                val dst1 = Rect(0, 0, leftW, viewH)
                canvas.drawBitmap(offscreen, src1, dst1, bitmapPaint)
            }
            if (snapColumn > 0) {
                val src2 = Rect(0, 0, snapColumn, bins)
                val dst2 = Rect(leftW, 0, viewW, viewH)
                canvas.drawBitmap(offscreen, src2, dst2, bitmapPaint)
            }
        }

        // Frequency labels
        val freqMax = sampleRate / 2
        canvas.drawText("${freqMax}Hz", 4f, labelPaint.textSize + 2f, labelPaint)
        canvas.drawText("0Hz", 4f, viewH - 4f, labelPaint)
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
