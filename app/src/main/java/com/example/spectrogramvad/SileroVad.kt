package com.example.spectrogramvad

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

class SileroVad {

    companion object {
        private const val TAG = "SileroVad"
        private const val CONTEXT_SIZE = 64    // Silero VAD v5 context for 16kHz
        private const val CONTEXT_SIZE_8K = 32  // context for 8kHz

        fun chunkSizeFor(sampleRate: Int): Int = when (sampleRate) {
            16000 -> 512   // 32ms
            else -> 256    // 32ms at 8kHz
        }
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var state = Array(2) { FloatArray(128) }
    private var context = FloatArray(CONTEXT_SIZE)
    private var initialized = false

    var sampleRate = 8000
        private set
    var chunkSize = 256
        private set

    @Volatile
    var lastProb = 0f
        private set

    fun init(appContext: Context): Boolean {
        return try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = appContext.assets.open("silero_vad.onnx").use { it.readBytes() }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            ortSession = ortEnv!!.createSession(modelBytes, opts)
            state = Array(2) { FloatArray(128) }
            context = FloatArray(if (sampleRate == 16000) CONTEXT_SIZE else CONTEXT_SIZE_8K)
            initialized = true
            Log.i(TAG, "Silero VAD initialized (${sampleRate}Hz, chunk=$chunkSize)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}")
            false
        }
    }

    fun setSampleRate(sr: Int) {
        sampleRate = sr
        chunkSize = chunkSizeFor(sr)
        reset()
    }

    fun infer(audioChunk: FloatArray): Float {
        if (!initialized) return 0f
        return try {
            val env = ortEnv!!

            val inputData: FloatArray
            val inputLen: Long

            // Silero VAD v5: prepend context for better accuracy
            val ctxSize = if (sampleRate == 16000) CONTEXT_SIZE else CONTEXT_SIZE_8K
            val size = ctxSize + chunkSize
            inputData = FloatArray(size)
            System.arraycopy(context, 0, inputData, 0, ctxSize)
            System.arraycopy(audioChunk, 0, inputData, ctxSize, chunkSize)
            // Save tail as context for next inference
            val newCtx = FloatArray(ctxSize)
            System.arraycopy(audioChunk, chunkSize - ctxSize, newCtx, 0, ctxSize)
            context = newCtx
            inputLen = size.toLong()

            val inputTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(inputData), longArrayOf(1, inputLen)
            )

            val stateData = Array(2) { i -> Array(1) { state[i].copyOf() } }
            val stateTensor = OnnxTensor.createTensor(env, stateData)

            // wenet uses shape [1] for 8kHz; 16kHz works with scalar []
            val srTensor = if (sampleRate == 16000) {
                OnnxTensor.createTensor(
                    env, LongBuffer.wrap(longArrayOf(sampleRate.toLong())), longArrayOf()
                )
            } else {
                OnnxTensor.createTensor(
                    env, LongBuffer.wrap(longArrayOf(sampleRate.toLong())), longArrayOf(1)
                )
            }

            val inputs = mapOf(
                "input" to inputTensor,
                "state" to stateTensor,
                "sr" to srTensor
            )

            val result = ortSession!!.run(inputs)

            @Suppress("UNCHECKED_CAST")
            val output = result[0].value as Array<FloatArray>
            val prob = output[0][0]

            @Suppress("UNCHECKED_CAST")
            val newState = result[1].value as Array<Array<FloatArray>>
            state[0] = newState[0][0].copyOf()
            state[1] = newState[1][0].copyOf()

            result.close()
            inputTensor.close()
            stateTensor.close()
            srTensor.close()

            lastProb = prob
            prob
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            0f
        }
    }

    fun reset() {
        state = Array(2) { FloatArray(128) }
        context = FloatArray(if (sampleRate == 16000) CONTEXT_SIZE else CONTEXT_SIZE_8K)
        lastProb = 0f
    }

    fun release() {
        ortSession?.close()
        ortSession = null
        initialized = false
    }
}
