package com.example.spectrogramvad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import java.io.File
import java.io.FileInputStream
import kotlin.math.*

class SpectrogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val MAX_COLUMNS = 300
        private const val COLUMN_STEP = 512
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
        fun onOffsetChanged(offsetMs: Float)
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
    private var totalColumnsAdded = 0L

    private var sampleBuffer = ShortArray(2048)
    private var sampleCount = 0
    private var windowCounter = 0

    private var fftReal = DoubleArray(2048)
    private var fftImag = DoubleArray(2048)
    private var hannWindow = makeHannWindow(256)

    // Playback and Paging state
    private var playbackMode = false
    private var totalDurationMs = 0
    private var currentTimeMs = 0
    private var viewOffsetMs = 0f
    private val WINDOW_SIZE_MS = 20000 

    // On-demand rendering state
    private var pcmFilePath: String? = null
    private var lastRenderedOffsetMs = -100000f

    // Scrolling & Fling
    private val scroller = OverScroller(context)
    private val gestureDetector: GestureDetector

    init {
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (!playbackMode || totalDurationMs <= 0) return false
                val labelMarginLeft = 100f
                val plotW = width - labelMarginLeft
                if (plotW <= 0) return false

                val deltaMs = (distanceX / plotW) * WINDOW_SIZE_MS
                viewOffsetMs += deltaMs
                clampViewOffset()
                seekListener?.onOffsetChanged(viewOffsetMs)
                postInvalidateOnAnimation()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (!playbackMode || totalDurationMs <= 0) return false
                val labelMarginLeft = 100f
                val plotW = width - labelMarginLeft
                
                val maxOffset = max(0, totalDurationMs - WINDOW_SIZE_MS)
                scroller.fling(
                    viewOffsetMs.toInt(), 0,
                    (-velocityX * WINDOW_SIZE_MS / plotW).toInt(), 0,
                    0, maxOffset, 0, 0
                )
                postInvalidateOnAnimation()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!playbackMode || totalDurationMs <= 0) return false
                val labelMarginLeft = 100f
                val plotW = width - labelMarginLeft
                if (e.x < labelMarginLeft) return false
                val xInPlot = e.x - labelMarginLeft
                val seekMs = viewOffsetMs + (xInPlot / plotW * WINDOW_SIZE_MS)
                seekListener?.onSeek(seekMs.toInt().coerceIn(0, totalDurationMs))
                return true
            }
        })
    }

    private fun clampViewOffset() {
        // Limit scroll so that data end is at most pinned to the right edge
        val maxOffset = max(0f, (totalDurationMs - WINDOW_SIZE_MS).toFloat())
        viewOffsetMs = viewOffsetMs.coerceIn(0f, maxOffset)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            viewOffsetMs = scroller.currX.toFloat()
            clampViewOffset()
            seekListener?.onOffsetChanged(viewOffsetMs)
            postInvalidateOnAnimation()
        }
    }

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
                totalColumnsAdded = 0
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
        currentTimeMs = currentMs
        // Auto-scroll logic: only shift window if cursor is not in the current view
        if (playbackMode && scroller.isFinished) {
            // Auto-scroll logic: only shift window if cursor is not in the current view
            if (currentTimeMs < viewOffsetMs || currentTimeMs >= viewOffsetMs + WINDOW_SIZE_MS) {
                viewOffsetMs = (currentTimeMs / WINDOW_SIZE_MS * WINDOW_SIZE_MS).toFloat()
                clampViewOffset() // Apply new constraint
                seekListener?.onOffsetChanged(viewOffsetMs)
            }
        }
        postInvalidate()
    }

    fun setViewOffsetMs(offset: Float) {
        if (scroller.isFinished) {
            viewOffsetMs = offset
            postInvalidate()
        }
    }

    fun clearPlaybackMode() {
        playbackMode = false
        pcmFilePath = null
        lastRenderedOffsetMs = -100000f
        totalDurationMs = 0
        currentTimeMs = 0
        viewOffsetMs = 0f
        totalColumnsAdded = 0
        scroller.forceFinished(true)
        setFftSize(fftSize)
        clear()
    }

    fun setPlaybackFile(path: String, totalSamples: Int) {
        pcmFilePath = path
        playbackMode = true
        totalDurationMs = (totalSamples.toLong() * 1000 / sampleRate).toInt()
        viewOffsetMs = 0f
        lastRenderedOffsetMs = -100000f
        
        synchronized(lock) {
            if (offscreen.width != MAX_COLUMNS || offscreen.height != freqBins) {
                offscreen.recycle()
                offscreen = Bitmap.createBitmap(MAX_COLUMNS, freqBins, Bitmap.Config.ARGB_8888)
            }
            offscreen.eraseColor(Color.BLACK)
        }
        postInvalidate()
    }

    private fun renderVisibleWindowOnDemand(offsetMs: Float) {
        if (pcmFilePath == null || abs(offsetMs - lastRenderedOffsetMs) < 10f) return
        
        synchronized(lock) {
            val file = File(pcmFilePath!!)
            if (!file.exists()) return
            
            lastRenderedOffsetMs = offsetMs
            offscreen.eraseColor(Color.BLACK)
            
            val bytesPerMs = sampleRate * 2 / 1000
            val startByte = (offsetMs.toLong() * bytesPerMs) and -2L
            val samplesPerColumn = (WINDOW_SIZE_MS * sampleRate / 1000) / MAX_COLUMNS
            val bytesPerColumn = samplesPerColumn * 2
            
            FileInputStream(file).use { fis ->
                try {
                    fis.skip(startByte)
                } catch (e: Exception) { return@use }
                
                val byteBuffer = ByteArray(bytesPerColumn)
                val shortBuffer = ShortArray(fftSize)
                
                for (col in 0 until MAX_COLUMNS) {
                    val read = fis.read(byteBuffer)
                    if (read <= 0) break
                    
                    val samplesToProcess = minOf(fftSize, samplesPerColumn)
                    for (i in 0 until samplesToProcess) {
                        shortBuffer[i] = ((byteBuffer[i * 2].toInt() and 0xFF) or (byteBuffer[i * 2 + 1].toInt() shl 8)).toShort()
                    }
                    for (i in samplesToProcess until fftSize) shortBuffer[i] = 0

                    val n = fftSize
                    val bins = freqBins
                    for (i in 0 until n) {
                        fftReal[i] = shortBuffer[i].toDouble() * hannWindow[i]
                        fftImag[i] = 0.0
                    }
                    fft(fftReal, fftImag, n)
                    
                    val floor = dbFloor; val ceil = dbCeil; val range = max(1.0, ceil - floor)
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
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!playbackMode || totalDurationMs <= 0) return super.onTouchEvent(event)
        val handled = gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP && scroller.isFinished) {
            val labelMarginLeft = 100f
            val plotW = width - labelMarginLeft
            val xInPlot = (event.x - labelMarginLeft).coerceIn(0f, plotW)
            val seekMs = viewOffsetMs + (xInPlot / plotW * WINDOW_SIZE_MS)
            seekListener?.onSeek(seekMs.toInt().coerceIn(0, totalDurationMs))
        }
        return handled || true
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
        val floor = dbFloor; val ceil = dbCeil; val range = max(1.0, ceil - floor)
        for (k in 0 until bins) {
            val mag = sqrt(fftReal[k] * fftReal[k] + fftImag[k] * fftImag[k]) / n
            val db = 20.0 * log10(mag + 1e-10); val norm = ((db - floor) / range).coerceIn(0.0, 1.0)
            pixelRow[bins - 1 - k] = heatmapColor(norm)
        }
        var columnsAdded = 0
        if (fftSize < COLUMN_STEP) {
            windowCounter++
            if (windowCounter >= COLUMN_STEP / fftSize) {
                windowCounter = 0
                synchronized(lock) {
                    offscreen.setPixels(pixelRow, 0, 1, currentColumn, 0, 1, bins)
                    currentColumn++; totalColumnsAdded++
                    if (currentColumn >= MAX_COLUMNS) { currentColumn = 0; wrapped = true }
                }
                columnsAdded = 1
            }
        } else {
            val count = fftSize / COLUMN_STEP
            synchronized(lock) {
                for (c in 0 until count) {
                    offscreen.setPixels(pixelRow, 0, 1, currentColumn, 0, 1, bins)
                    currentColumn++; totalColumnsAdded++
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
            sampleCount = 0; windowCounter = 0; currentColumn = 0
            totalColumnsAdded = 0; wrapped = false; offscreen.eraseColor(Color.BLACK)
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val viewW = width; val viewH = height
        if (viewW == 0 || viewH == 0) return

        if (playbackMode) renderVisibleWindowOnDemand(viewOffsetMs)

        val snapColumn: Int; val snapWrapped: Boolean; val snapTotalCols: Long; val bins: Int
        synchronized(lock) {
            snapColumn = currentColumn; snapWrapped = wrapped
            snapTotalCols = totalColumnsAdded; bins = freqBins
        }
        if (snapColumn == 0 && !snapWrapped && !playbackMode) return
// Space for labels
val labelMarginLeft = 100f
val labelMarginTop = 30f
val labelMarginBottom = 60f
val plotRect = Rect(labelMarginLeft.toInt(), labelMarginTop.toInt(), viewW, (viewH - labelMarginBottom).toInt())
val plotW = plotRect.width(); val plotH = plotRect.height()

        if (playbackMode) {
            val dst = Rect(plotRect.left, plotRect.top, plotRect.right, plotRect.bottom)
            canvas.drawBitmap(offscreen, null, dst, bitmapPaint)
            
            val cursorX = plotRect.left + ((currentTimeMs - viewOffsetMs).toFloat() / WINDOW_SIZE_MS * plotW)
            if (cursorX >= plotRect.left && cursorX <= plotRect.right) {
                val cursorPaint = Paint().apply { color = Color.WHITE; strokeWidth = 3f }
                canvas.drawLine(cursorX, plotRect.top.toFloat(), cursorX, plotRect.bottom.toFloat(), cursorPaint)
            }

            labelPaint.textAlign = Paint.Align.CENTER
            val startLabelMs = (viewOffsetMs / 5000).toInt() * 5000
            for (tMs in startLabelMs..(viewOffsetMs + WINDOW_SIZE_MS).toInt() step 5000) {
                val tx = plotRect.left + ((tMs - viewOffsetMs).toFloat() / WINDOW_SIZE_MS * plotW)
                if (tx < plotRect.left || tx > plotRect.right) continue
                canvas.drawText("${tMs / 1000}s", tx, viewH - 15f, labelPaint)
            }
        } else {
            // Live Mode
            if (!snapWrapped) {
                val src = Rect(0, 0, snapColumn, bins)
                val dst = Rect(plotRect.left, plotRect.top, plotRect.left + (snapColumn.toFloat() / MAX_COLUMNS * plotW).toInt(), plotRect.bottom)
                canvas.drawBitmap(offscreen, src, dst, bitmapPaint)
            } else {
                val rightPart = MAX_COLUMNS - snapColumn; val leftW = (rightPart.toFloat() / MAX_COLUMNS * plotW).toInt()
                val src1 = Rect(snapColumn, 0, MAX_COLUMNS, bins); val dst1 = Rect(plotRect.left, plotRect.top, plotRect.left + leftW, plotRect.bottom)
                canvas.drawBitmap(offscreen, src1, dst1, bitmapPaint)
                val src2 = Rect(0, 0, snapColumn, bins); val dst2 = Rect(plotRect.left + leftW, plotRect.top, plotRect.right, plotRect.bottom)
                canvas.drawBitmap(offscreen, src2, dst2, bitmapPaint)
            }
            labelPaint.textAlign = Paint.Align.CENTER
            val msPerColumn = WINDOW_SIZE_MS.toFloat() / MAX_COLUMNS; val totalMs = snapTotalCols * msPerColumn
            val firstVisibleMs = max(0f, totalMs - WINDOW_SIZE_MS).toLong()
            val startLabelMs = (firstVisibleMs / 5000 + 1) * 5000
            for (tMs in startLabelMs..totalMs.toLong() step 5000) {
                val colAtTime = (tMs / msPerColumn).toLong() % MAX_COLUMNS
                var viewIndex = colAtTime - snapColumn
                if (viewIndex < 0) viewIndex += MAX_COLUMNS
                val drawX = plotRect.left + (viewIndex.toFloat() / MAX_COLUMNS * plotW)
                canvas.drawText("${tMs / 1000}s", drawX, viewH - 15f, labelPaint)
                canvas.drawLine(drawX, plotRect.bottom.toFloat(), drawX, plotRect.bottom + 10f, Paint().apply { color = Color.WHITE; strokeWidth = 2f })
            }
        }
        labelPaint.textAlign = Paint.Align.RIGHT
        val freqMax = sampleRate / 2
        for (i in 0..4) {
            val freq = freqMax * i / 4; val ty = plotRect.bottom - (i.toFloat() / 4 * plotH)
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
            while (m >= 1 && j >= m) { j -= m; m = m shr 1 }
            j += m
        }
        var step = 2
        while (step <= n) {
            val halfStep = step shr 1; val angle = -2.0 * PI / step
            val wR = cos(angle); val wI = sin(angle)
            var k = 0
            while (k < n) {
                var curR = 1.0; var curI = 0.0
                for (m2 in 0 until halfStep) {
                    val idx1 = k + m2; val idx2 = idx1 + halfStep
                    val tR = curR * real[idx2] - curI * imag[idx2]
                    val tI = curR * imag[idx2] + curI * real[idx2]
                    real[idx2] = real[idx1] - tR; imag[idx2] = imag[idx1] - tI
                    real[idx1] += tR; imag[idx1] += tI
                    val newCurR = curR * wR - curI * wI
                    curI = curR * wI + curI * wR; curR = newCurR
                }
                k += step
            }
            step = step shl 1
        }
    }

    private fun heatmapColor(v: Double): Int {
        val r: Int; val g: Int; val b: Int
        when {
            v < 0.25 -> { val t = v / 0.25; r = 0; g = 0; b = (255 * t).toInt() }
            v < 0.5 -> { val t = (v - 0.25) / 0.25; r = 0; g = (255 * t).toInt(); b = (255 * (1.0 - t)).toInt() }
            v < 0.75 -> { val t = (v - 0.5) / 0.25; r = (255 * t).toInt(); g = 255; b = 0 }
            else -> { val t = (v - 0.75) / 0.25; r = 255; g = (255 * (1.0 - t)).toInt(); b = 0 }
        }
        return Color.rgb(r, g, b)
    }
}
