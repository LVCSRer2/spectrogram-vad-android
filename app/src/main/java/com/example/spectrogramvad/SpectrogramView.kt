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
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import kotlin.math.*

class SpectrogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val SAMPLES_PER_COL = 512
        private const val MIN_WINDOW_MS = 2000 
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
        fun onZoomChanged(windowSizeMs: Int)
        fun onColumnAdded()
    }
    private var seekListener: SeekListener? = null
    fun setOnSeekListener(listener: SeekListener) {
        seekListener = listener
    }

    private var fftSize = 256
    private var freqBins = fftSize / 2
    
    private var offscreen = Bitmap.createBitmap(1024, freqBins, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.BLACK)
    }
    private var pixelRow = IntArray(freqBins) // Recovered variable
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

    private var playbackMode = false
    private var totalDurationMs = 0
    private var currentTimeMs = 0
    private var viewOffsetMs = 0f
    private var windowSizeMs = 20000 

    private var pcmFilePath: String? = null
    private var lastRenderedOffsetMs = -100000f
    private var lastRenderedWindowMs = -1

    // Background Rendering
    private val renderExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var isRendering = false

    private val scroller = OverScroller(context).apply {
        setFriction(0.015f)
    }
    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector

    init {
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (!playbackMode || totalDurationMs <= 0) return false
                val labelMarginLeft = 100f; val plotW = width - labelMarginLeft
                if (plotW <= 0) return false
                val deltaMs = (distanceX / plotW) * windowSizeMs
                viewOffsetMs += deltaMs
                clampViewOffset()
                seekListener?.onOffsetChanged(viewOffsetMs)
                postInvalidateOnAnimation()
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (!playbackMode || totalDurationMs <= 0) return false
                val labelMarginLeft = 100f; val plotW = width - labelMarginLeft
                val maxOffset = max(0, totalDurationMs - windowSizeMs)
                val vX = (velocityX * 1.5f).toInt()
                scroller.fling(viewOffsetMs.toInt(), 0, (-vX * windowSizeMs / plotW).toInt(), 0, 0, maxOffset, 0, 0)
                postInvalidateOnAnimation()
                return true
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!playbackMode || totalDurationMs <= 0) return false
                val labelMarginLeft = 100f; val plotW = width - labelMarginLeft
                if (e.x < labelMarginLeft) return false
                val xInPlot = e.x - labelMarginLeft
                val seekMs = viewOffsetMs + (xInPlot / plotW * windowSizeMs)
                seekListener?.onSeek(seekMs.toInt().coerceIn(0, totalDurationMs))
                return true
            }
        })

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!playbackMode || totalDurationMs <= 0) return false
                val oldWindow = windowSizeMs.toFloat()
                val factor = if (detector.scaleFactor > 1.0f) {
                    1.0f + (detector.scaleFactor - 1.0f) * 1.5f
                } else {
                    1.0f - (1.0f - detector.scaleFactor) * 1.5f
                }
                val newWindow = (oldWindow / factor).toInt().coerceIn(MIN_WINDOW_MS, max(MIN_WINDOW_MS, totalDurationMs))
                if (newWindow != windowSizeMs) {
                    val focusX = detector.focusX - 100f; val plotW = width - 100f
                    if (plotW > 0) {
                        val focusFraction = (focusX / plotW).coerceIn(0f, 1f)
                        val focusTimeMs = viewOffsetMs + focusFraction * oldWindow
                        viewOffsetMs = (focusTimeMs - focusFraction * newWindow)
                    }
                    windowSizeMs = newWindow; clampViewOffset()
                    seekListener?.onZoomChanged(windowSizeMs); seekListener?.onOffsetChanged(viewOffsetMs)
                    postInvalidateOnAnimation()
                }
                return true
            }
        })
    }

    private fun getColsInWindow(windowMs: Int): Int {
        return (windowMs.toLong() * sampleRate / (1000L * SAMPLES_PER_COL)).toInt()
    }

    private fun clampViewOffset() {
        val maxOffset = max(0f, (totalDurationMs - windowSizeMs).toFloat())
        viewOffsetMs = viewOffsetMs.coerceIn(0f, maxOffset)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            viewOffsetMs = scroller.currX.toFloat()
            clampViewOffset(); seekListener?.onOffsetChanged(viewOffsetMs)
            postInvalidateOnAnimation()
        }
    }

    private fun makeHannWindow(n: Int) = DoubleArray(n) { i -> 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1))) }

    private fun ensureBitmapSize(cols: Int, bins: Int) {
        synchronized(lock) {
            if (offscreen.width < cols || offscreen.height != bins) {
                offscreen.recycle()
                val safeCols = min(cols, 4096) 
                offscreen = Bitmap.createBitmap(safeCols, bins, Bitmap.Config.ARGB_8888)
                lastRenderedOffsetMs = -100000f 
            }
        }
    }

    fun setFftSize(size: Int) {
        synchronized(lock) {
            fftSize = size; freqBins = size / 2
            if (!playbackMode) {
                val initialCols = 1024
                ensureBitmapSize(initialCols, freqBins)
                offscreen.eraseColor(Color.BLACK)
                currentColumn = 0; wrapped = false; totalColumnsAdded = 0
            }
            pixelRow = IntArray(freqBins) // Reset recovered variable
            sampleBuffer = ShortArray(fftSize); sampleCount = 0
            fftReal = DoubleArray(fftSize); fftImag = DoubleArray(fftSize); hannWindow = makeHannWindow(fftSize); windowCounter = 0
        }
        postInvalidate()
    }

    fun getFftSize(): Int = fftSize

    fun setCursorPosition(pos: Float, currentMs: Int) {
        currentTimeMs = currentMs
        if (playbackMode && scroller.isFinished && !scaleDetector.isInProgress) {
            if (currentTimeMs < viewOffsetMs || currentTimeMs >= viewOffsetMs + windowSizeMs) {
                viewOffsetMs = (currentTimeMs / windowSizeMs * windowSizeMs).toFloat()
                clampViewOffset(); seekListener?.onOffsetChanged(viewOffsetMs)
            }
        }
        postInvalidate()
    }

    fun setViewOffsetMs(offset: Float) { if (scroller.isFinished && !scaleDetector.isInProgress) { viewOffsetMs = offset; postInvalidate() } }

    fun setWindowSizeMs(ms: Int) {
        windowSizeMs = ms.coerceIn(MIN_WINDOW_MS, max(MIN_WINDOW_MS, totalDurationMs))
        clampViewOffset(); postInvalidate()
    }

    fun setDbRange(floor: Double, ceil: Double) {
        this.dbFloor = floor; this.dbCeil = ceil
        lastRenderedOffsetMs = -100000f; postInvalidate()
    }

    fun clearPlaybackMode() {
        playbackMode = false; pcmFilePath = null; lastRenderedOffsetMs = -100000f; lastRenderedWindowMs = -1
        totalDurationMs = 0; currentTimeMs = 0; viewOffsetMs = 0f; windowSizeMs = 20000
        scroller.forceFinished(true); setFftSize(fftSize); clear()
    }

    fun setPlaybackFile(path: String, totalSamples: Int) {
        pcmFilePath = path; playbackMode = true
        totalDurationMs = (totalSamples.toLong() * 1000 / sampleRate).toInt()
        viewOffsetMs = 0f; lastRenderedOffsetMs = -100000f; lastRenderedWindowMs = -1
        synchronized(lock) {
            val plotW = (width - 100).coerceAtLeast(1024)
            ensureBitmapSize(plotW, freqBins)
            offscreen.eraseColor(Color.BLACK)
        }
        postInvalidate()
    }

    private fun renderVisibleWindowOnDemand(offsetMs: Float) {
        if (pcmFilePath == null || isRendering) return
        if (abs(offsetMs - lastRenderedOffsetMs) < 2f && windowSizeMs == lastRenderedWindowMs) return
        
        val labelMarginLeft = 100f
        val plotW = (width - labelMarginLeft).toInt().coerceAtLeast(1)
        ensureBitmapSize(plotW, freqBins)

        isRendering = true
        val currentWinSize = windowSizeMs
        val currentPath = pcmFilePath!!
        val currentSR = sampleRate
        val currentBins = freqBins
        val currentFFT = fftSize
        val floor = dbFloor
        val ceil = dbCeil

        renderExecutor.execute {
            try {
                val file = File(currentPath)
                if (!file.exists()) return@execute
                val bytesPerMs = currentSR * 2 / 1000.0
                val startByte = (offsetMs * bytesPerMs).toLong() and -2L
                val totalSamplesInWindow = (currentWinSize.toLong() * currentSR / 1000)
                val samplesPerColumn = (totalSamplesInWindow / plotW).toInt().coerceAtLeast(1)
                val bytesPerColumn = samplesPerColumn * 2
                
                val tempBitmap = Bitmap.createBitmap(plotW, currentBins, Bitmap.Config.ARGB_8888)
                val row = IntArray(currentBins)
                val real = DoubleArray(currentFFT)
                val imag = DoubleArray(currentFFT)
                val window = makeHannWindow(currentFFT)
                
                FileInputStream(file).use { fis ->
                    fis.skip(startByte)
                    val byteBuffer = ByteArray(bytesPerColumn)
                    val shortBuffer = ShortArray(currentFFT)
                    for (col in 0 until plotW) {
                        val read = fis.read(byteBuffer)
                        if (read <= 0) break
                        val samplesToProcess = minOf(currentFFT, samplesPerColumn)
                        for (i in 0 until samplesToProcess) shortBuffer[i] = ((byteBuffer[i * 2].toInt() and 0xFF) or (byteBuffer[i * 2 + 1].toInt() shl 8)).toShort()
                        for (i in samplesToProcess until currentFFT) shortBuffer[i] = 0
                        for (i in 0 until currentFFT) { real[i] = shortBuffer[i].toDouble() * window[i]; imag[i] = 0.0 }
                        fft(real, imag, currentFFT)
                        val range = max(1.0, ceil - floor)
                        for (k in 0 until currentBins) {
                            val mag = sqrt(real[k] * real[k] + imag[k] * imag[k]) / currentFFT
                            val db = 20.0 * log10(mag + 1e-10)
                            val norm = ((db - floor) / range).coerceIn(0.0, 1.0)
                            row[currentBins - 1 - k] = heatmapColor(norm)
                        }
                        tempBitmap.setPixels(row, 0, 1, col, 0, 1, currentBins)
                    }
                }
                synchronized(lock) {
                    offscreen.recycle()
                    offscreen = tempBitmap
                    lastRenderedOffsetMs = offsetMs
                    lastRenderedWindowMs = currentWinSize
                }
                postInvalidate()
            } catch (e: Exception) { e.printStackTrace() } finally { isRendering = false }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!playbackMode || totalDurationMs <= 0) return super.onTouchEvent(event)
        var handled = scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) handled = gestureDetector.onTouchEvent(event) || handled
        if (event.action == MotionEvent.ACTION_UP && scroller.isFinished && !scaleDetector.isInProgress) {
            val labelMarginLeft = 100f; val plotW = width - labelMarginLeft
            val xInPlot = (event.x - labelMarginLeft).coerceIn(0f, plotW)
            val seekMs = viewOffsetMs + (xInPlot / plotW * windowSizeMs)
            seekListener?.onSeek(seekMs.toInt().coerceIn(0, totalDurationMs))
        }
        return handled || true
    }

    fun addSamples(samples: ShortArray, length: Int): Int {
        if (playbackMode) return 0
        var colsAtStart = totalColumnsAdded
        for (i in 0 until length) {
            sampleBuffer[sampleCount++] = samples[i]
            if (sampleCount == fftSize) { processWindow(); sampleCount = 0 }
        }
        return (totalColumnsAdded - colsAtStart).toInt()
    }

    private fun processWindow() {
        if (playbackMode) return
        val n = fftSize; val bins = freqBins
        for (i in 0 until n) { fftReal[i] = sampleBuffer[i].toDouble() * hannWindow[i]; fftImag[i] = 0.0 }
        fft(fftReal, fftImag, n)
        val floor = dbFloor; val ceil = dbCeil; val range = max(1.0, ceil - floor)
        for (k in 0 until bins) {
            val mag = sqrt(fftReal[k] * fftReal[k] + fftImag[k] * fftImag[k]) / n
            val db = 20.0 * log10(mag + 1e-10); val norm = ((db - floor) / range).coerceIn(0.0, 1.0)
            pixelRow[bins - 1 - k] = heatmapColor(norm) // Using recovered pixelRow
        }
        val colsIn20s = (20000L * sampleRate / (1000L * SAMPLES_PER_COL)).toInt()
        if (fftSize < SAMPLES_PER_COL) {
            windowCounter++
            if (windowCounter >= SAMPLES_PER_COL / fftSize) {
                windowCounter = 0
                synchronized(lock) {
                    if (offscreen.width < colsIn20s) ensureBitmapSize(colsIn20s + 100, bins)
                    offscreen.setPixels(pixelRow, 0, 1, currentColumn, 0, 1, bins)
                    currentColumn++; totalColumnsAdded++; if (currentColumn >= colsIn20s) { currentColumn = 0; wrapped = true }
                }
                seekListener?.onColumnAdded()
            }
        } else {
            val count = fftSize / SAMPLES_PER_COL
            synchronized(lock) {
                if (offscreen.width < colsIn20s) ensureBitmapSize(colsIn20s + 100, bins)
                for (c in 0 until count) {
                    offscreen.setPixels(pixelRow, 0, 1, currentColumn, 0, 1, bins)
                    currentColumn++; totalColumnsAdded++; if (currentColumn >= colsIn20s) { currentColumn = 0; wrapped = true }
                    seekListener?.onColumnAdded()
                }
            }
        }
        postInvalidate()
    }

    fun clear() {
        synchronized(lock) { sampleCount = 0; windowCounter = 0; currentColumn = 0; totalColumnsAdded = 0; wrapped = false; offscreen.eraseColor(Color.BLACK) }
        postInvalidate()
    }

    private fun formatLabel(tMs: Int): String {
        val totalSec = tMs / 1000; val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        return when { h > 0 -> String.format("%dh %dm", h, m); m > 0 -> String.format("%dm %ds", m, s); else -> String.format("%ds", s) }
    }

    private fun getBestStepMs(winMs: Int): Int {
        return when {
            winMs > 3600000 -> 600000; winMs > 1800000 -> 300000; winMs > 600000 -> 120000; winMs > 300000 -> 60000
            winMs > 120000 -> 30000; winMs > 60000 -> 10000; winMs > 20000 -> 5000; winMs > 10000 -> 2000; else -> 1000
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK); val viewW = width; val viewH = height; if (viewW == 0 || viewH == 0) return
        if (playbackMode) renderVisibleWindowOnDemand(viewOffsetMs)
        val snapColumn: Int; val snapWrapped: Boolean; val snapTotalCols: Long; val bins: Int
        synchronized(lock) { snapColumn = currentColumn; snapWrapped = wrapped; snapTotalCols = totalColumnsAdded; bins = freqBins }
        if (snapColumn == 0 && !snapWrapped && !playbackMode) return
        val labelMarginLeft = 100f; val labelMarginTop = 30f; val labelMarginBottom = 60f
        val plotRect = Rect(labelMarginLeft.toInt(), labelMarginTop.toInt(), viewW, (viewH - labelMarginBottom).toInt())
        val plotW = plotRect.width(); val plotH = plotRect.height()
        val colsInView = getColsInWindow(if (playbackMode) windowSizeMs else 20000)

        if (playbackMode) {
            val dst = Rect(plotRect.left, plotRect.top, plotRect.right, plotRect.bottom)
            synchronized(lock) {
                if (!offscreen.isRecycled) {
                    canvas.drawBitmap(offscreen, null, dst, bitmapPaint)
                }
            }
            val cursorX = plotRect.left + ((currentTimeMs - viewOffsetMs).toFloat() / windowSizeMs * plotW)
            if (cursorX >= plotRect.left && cursorX <= plotRect.right) {
                val cursorPaint = Paint().apply { color = Color.WHITE; strokeWidth = 3f }
                canvas.drawLine(cursorX, plotRect.top.toFloat(), cursorX, plotRect.bottom.toFloat(), cursorPaint)
            }
            labelPaint.textAlign = Paint.Align.CENTER
            val stepMs = getBestStepMs(windowSizeMs)
            val startLabelMs = (viewOffsetMs.toInt() / stepMs) * stepMs
            for (tMs in startLabelMs..(viewOffsetMs + windowSizeMs).toInt() step stepMs) {
                val tx = plotRect.left + ((tMs - viewOffsetMs).toFloat() / windowSizeMs * plotW)
                if (tx < plotRect.left || tx > plotRect.right) continue
                canvas.drawText(formatLabel(tMs), tx, viewH - 15f, labelPaint)
            }
        } else {
            val colsIn20s = (20000L * sampleRate / (1000L * SAMPLES_PER_COL)).toInt()
            if (!snapWrapped) {
                val src = Rect(0, 0, snapColumn, bins)
                val dst = Rect(plotRect.left, plotRect.top, plotRect.left + (snapColumn.toFloat() / colsIn20s * plotW).toInt(), plotRect.bottom)
                canvas.drawBitmap(offscreen, src, dst, bitmapPaint)
            } else {
                val rightPart = colsIn20s - snapColumn; val leftW = (rightPart.toFloat() / colsIn20s * plotW).toInt()
                val src1 = Rect(snapColumn, 0, colsIn20s, bins); val dst1 = Rect(plotRect.left, plotRect.top, plotRect.left + leftW, plotRect.bottom)
                canvas.drawBitmap(offscreen, src1, dst1, bitmapPaint)
                val src2 = Rect(0, 0, snapColumn, bins); val dst2 = Rect(plotRect.left + leftW, plotRect.top, plotRect.right, plotRect.bottom)
                canvas.drawBitmap(offscreen, src2, dst2, bitmapPaint)
            }
            labelPaint.textAlign = Paint.Align.CENTER
            val msPerColumn = (SAMPLES_PER_COL * 1000f / sampleRate)
            val totalMs = snapTotalCols * msPerColumn
            val firstVisibleMs = max(0f, totalMs - 20000).toLong(); val startLabelMs = (firstVisibleMs / 5000 + 1) * 5000
            for (tMs in startLabelMs..totalMs.toLong() step 5000) {
                val colAtTime = (tMs / msPerColumn).toLong() % colsIn20s
                var viewIndex = (colAtTime - snapColumn).toInt(); if (viewIndex < 0) viewIndex += colsIn20s
                val drawX = plotRect.left + (viewIndex.toFloat() / colsIn20s * plotW)
                canvas.drawText(formatLabel(tMs.toInt()), drawX, viewH - 15f, labelPaint)
                canvas.drawLine(drawX, plotRect.bottom.toFloat(), drawX, plotRect.bottom + 10f, Paint().apply { color = Color.WHITE; strokeWidth = 2f })
            }
        }
        labelPaint.textAlign = Paint.Align.RIGHT; val freqMax = sampleRate / 2
        for (i in 0..4) { val freq = freqMax * i / 4; val ty = plotRect.bottom - (i.toFloat() / 4 * plotH); canvas.drawText("${freq}Hz", labelMarginLeft - 10f, ty + labelPaint.textSize / 3, labelPaint) }
    }

    private fun fft(real: DoubleArray, imag: DoubleArray, n: Int) {
        var j = 0; for (i in 0 until n) { if (i < j) { var tr = real[i]; real[i] = real[j]; real[j] = tr; tr = imag[i]; imag[i] = imag[j]; imag[j] = tr }; var m = n shr 1; while (m >= 1 && j >= m) { j -= m; m = m shr 1 }; j += m }
        var step = 2; while (step <= n) { val halfStep = step shr 1; val angle = -2.0 * PI / step; val wR = cos(angle); val wI = sin(angle); var k = 0; while (k < n) { var curR = 1.0; var curI = 0.0; for (m2 in 0 until halfStep) { val idx1 = k + m2; val idx2 = idx1 + halfStep; val tR = curR * real[idx2] - curI * imag[idx2]; val tI = curR * imag[idx2] + curI * real[idx2]; real[idx2] = real[idx1] - tR; imag[idx2] = imag[idx1] - tI; real[idx1] += tR; imag[idx1] += tI; val newCurR = curR * wR - curI * wI; curI = curR * wI + curI * wR; curR = newCurR }; k += step }; step = step shl 1 }
    }

    private fun heatmapColor(v: Double): Int {
        val r: Int; val g: Int; val b: Int
        when { v < 0.25 -> { val t = v / 0.25; r = 0; g = 0; b = (255 * t).toInt() }; v < 0.5 -> { val t = (v - 0.25) / 0.25; r = 0; g = (255 * t).toInt(); b = (255 * (1.0 - t)).toInt() }; v < 0.75 -> { val t = (v - 0.5) / 0.25; r = (255 * t).toInt(); g = 255; b = 0 }; else -> { val t = (v - 0.75) / 0.25; r = 255; g = (255 * (1.0 - t)).toInt(); b = 0 } }
        return Color.rgb(r, g, b)
    }
}
