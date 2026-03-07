package com.example.spectrogramvad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class VadProbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BAR_COUNT = SpectrogramView.MAX_COLUMNS  // sync with spectrogram
    }

    private val probBuffer = FloatArray(BAR_COUNT)
    private var currentColumn = 0
    private var wrapped = false
    private var speechThreshold = 0.5f

    private val barPaint = Paint()
    private val linePaint = Paint().apply { strokeWidth = 2f }
    private val bgPaint = Paint().apply { color = 0xFF1A1A2E.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = 20f
    }

    private var cursorPosition = 0f
    private var playbackMode = false

    fun setCursorPosition(pos: Float) {
        cursorPosition = pos.coerceIn(0f, 1f)
        postInvalidate()
    }

    fun clearPlaybackMode() {
        playbackMode = false
        cursorPosition = 0f
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
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // Labels margin sync with SpectrogramView
        val labelMarginLeft = 100f
        val plotW = w - labelMarginLeft
        val barWidth = plotW / BAR_COUNT

        // Threshold line
        val threshY = h * (1f - speechThreshold)
        linePaint.color = 0x60FF6B6B.toInt()
        canvas.drawLine(labelMarginLeft, threshY, w.toFloat(), threshY, linePaint)

        // Draw Bars using Circular Buffer Logic
        if (playbackMode) {
            // In playback, we just draw empty background or simple line
            // since we don't store full VAD history yet.
        } else {
            for (i in 0 until BAR_COUNT) {
                // Determine which buffer index to draw at this view position 'i'
                val bufferIdx = if (!wrapped) {
                    if (i >= currentColumn) continue
                    i
                } else {
                    // When wrapped, the oldest data is at currentColumn
                    (currentColumn + i) % BAR_COUNT
                }

                val prob = probBuffer[bufferIdx]
                if (prob <= 0.001f) continue
                
                val barH = prob * h
                val left = labelMarginLeft + (i * barWidth)
                val right = left + barWidth

                barPaint.color = if (prob >= speechThreshold) {
                    0xCCFF6B6B.toInt()  // red = speech
                } else {
                    0x664CAF50.toInt()  // dim green = silence
                }
                canvas.drawRect(left, h - barH, right, h.toFloat(), barPaint)
            }
        }

        if (playbackMode) {
            val cursorX = labelMarginLeft + (cursorPosition * plotW)
            val cursorPaint = Paint().apply {
                color = android.graphics.Color.WHITE
                strokeWidth = 3f
            }
            canvas.drawLine(cursorX, 0f, cursorX, h.toFloat(), cursorPaint)
        }

        canvas.drawText("VAD", 4f, h - 4f, labelPaint)
    }
}
