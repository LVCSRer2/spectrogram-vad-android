package com.example.spectrogramvad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import kotlin.math.max

class VadProbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BAR_COUNT = SpectrogramView.MAX_COLUMNS
    }

    private val probBuffer = FloatArray(BAR_COUNT)
    private var currentColumn = 0
    private var wrapped = false
    private var speechThreshold = 0.5f

    // Playback data
    private var playbackProbs: FloatArray? = null
    private var totalDurationMs = 0
    private var viewOffsetMs = 0f
    private var currentTimeMs = 0
    private var windowSizeMs = 20000

    private val barPaint = Paint()
    private val linePaint = Paint().apply { strokeWidth = 2f }
    private val bgPaint = Paint().apply { color = 0xFF1A1A2E.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = 20f
    }

    private var playbackMode = false

    interface SeekListener {
        fun onSeek(ms: Int)
        fun onOffsetChanged(offsetMs: Float)
    }
    private var seekListener: SeekListener? = null
    fun setOnSeekListener(listener: SeekListener) {
        seekListener = listener
    }

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

                val deltaMs = (distanceX / plotW) * windowSizeMs
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
                val maxOffset = max(0, totalDurationMs - windowSizeMs)
                scroller.fling(
                    viewOffsetMs.toInt(), 0,
                    (-velocityX * windowSizeMs / plotW).toInt(), 0,
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
                val seekMs = viewOffsetMs + (xInPlot / plotW * windowSizeMs)
                seekListener?.onSeek(seekMs.toInt().coerceIn(0, totalDurationMs))
                return true
            }
        })
    }

    private fun clampViewOffset() {
        val maxOffset = max(0f, (totalDurationMs - windowSizeMs).toFloat())
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!playbackMode || totalDurationMs <= 0) return super.onTouchEvent(event)
        val handled = gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP && scroller.isFinished) {
            val labelMarginLeft = 100f
            val plotW = width - labelMarginLeft
            val xInPlot = (event.x - labelMarginLeft).coerceIn(0f, plotW)
            val seekMs = viewOffsetMs + (xInPlot / plotW * windowSizeMs)
            seekListener?.onSeek(seekMs.toInt().coerceIn(0, totalDurationMs))
        }
        return handled || true
    }

    fun setCursorPosition(currentMs: Int) {
        this.currentTimeMs = currentMs
        if (playbackMode && scroller.isFinished) {
            if (currentTimeMs < viewOffsetMs || currentTimeMs >= viewOffsetMs + windowSizeMs) {
                viewOffsetMs = (currentTimeMs / windowSizeMs * windowSizeMs).toFloat()
                clampViewOffset()
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

    fun setWindowSizeMs(ms: Int) {
        windowSizeMs = ms
        clampViewOffset()
        postInvalidate()
    }

    fun setFullVADData(probs: FloatArray, totalDuration: Int) {
        playbackProbs = probs
        totalDurationMs = totalDuration
        postInvalidate()
    }

    fun clearPlaybackMode() {
        playbackMode = false
        playbackProbs = null
        totalDurationMs = 0
        viewOffsetMs = 0f
        currentTimeMs = 0
        windowSizeMs = 20000
        scroller.forceFinished(true)
        clear()
    }

    fun setPlaybackMode(enabled: Boolean) {
        playbackMode = enabled
        postInvalidate()
    }

    fun addProb(prob: Float) {
        if (playbackMode) return
        probBuffer[currentColumn] = prob
        currentColumn++
        if (currentColumn >= BAR_COUNT) {
            currentColumn = 0
            wrapped = true
        }
        postInvalidate()
    }

    fun clear() {
        probBuffer.fill(0f)
        currentColumn = 0
        wrapped = false
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width; val h = height
        if (w == 0 || h == 0) return

        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val labelMarginLeft = 100f
        val plotW = w - labelMarginLeft
        val barWidth = plotW / BAR_COUNT

        val threshY = h * (1f - speechThreshold)
        linePaint.color = 0x60FF6B6B.toInt()
        canvas.drawLine(labelMarginLeft, threshY, w.toFloat(), threshY, linePaint)

        if (playbackMode && playbackProbs != null) {
            val probs = playbackProbs!!
            val msPerBar = 20000f / BAR_COUNT // Use recording-time resolution for data index
            val startIdx = (viewOffsetMs / msPerBar).toInt()
            
            // During playback, we need to map data index to the current window size
            val barsToDraw = (windowSizeMs / msPerBar).toInt()
            val zoomFactor = windowSizeMs.toFloat() / 20000f
            
            for (i in 0 until BAR_COUNT) {
                // i-th bar in view corresponds to data index:
                val dataIdx = startIdx + (i * zoomFactor).toInt()
                if (dataIdx < 0 || dataIdx >= probs.size) continue
                
                val prob = probs[dataIdx]
                if (prob <= 0.001f) continue
                
                val barH = prob * h
                val left = labelMarginLeft + (i * barWidth)
                val right = left + barWidth
                barPaint.color = if (prob >= speechThreshold) 0xCCFF6B6B.toInt() else 0x664CAF50.toInt()
                canvas.drawRect(left, h - barH, right, h.toFloat(), barPaint)
            }
            
            val cursorX = labelMarginLeft + ((currentTimeMs - viewOffsetMs).toFloat() / windowSizeMs * plotW)
            if (cursorX >= labelMarginLeft && cursorX <= w) {
                val cursorPaint = Paint().apply { color = android.graphics.Color.WHITE; strokeWidth = 3f }
                canvas.drawLine(cursorX, 0f, cursorX, h.toFloat(), cursorPaint)
            }
            
        } else if (!playbackMode) {
            for (i in 0 until BAR_COUNT) {
                val bufferIdx = if (!wrapped) {
                    if (i >= currentColumn) continue
                    i
                } else {
                    (currentColumn + i) % BAR_COUNT
                }
                val prob = probBuffer[bufferIdx]
                if (prob <= 0.001f) continue
                val barH = prob * h
                val left = labelMarginLeft + (i * barWidth)
                val right = left + barWidth
                barPaint.color = if (prob >= speechThreshold) 0xCCFF6B6B.toInt() else 0x664CAF50.toInt()
                canvas.drawRect(left, h - barH, right, h.toFloat(), barPaint)
            }
        }
        canvas.drawText("VAD", 4f, h - 4f, labelPaint)
    }
}
