package com.example.spectrogramvad

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST = 100
        private const val PREF_NAME = "spectrogram_vad_prefs"
        private const val PREF_FFT_SIZE = "fft_size"
        private const val PREF_SAMPLE_RATE = "sample_rate"
        private val FFT_SIZES = intArrayOf(64, 128, 256, 512, 1024, 2048)
        private val SAMPLE_RATES = intArrayOf(8000, 16000)
    }

    private lateinit var spectrogramView: SpectrogramView
    private lateinit var vadProbView: VadProbView
    private lateinit var btnRecord: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvProb: TextView

    private val sileroVad = SileroVad()
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var isRecording = false
    private var currentSampleRate = 8000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spectrogramView = findViewById(R.id.spectrogramView)
        vadProbView = findViewById(R.id.vadProbView)
        btnRecord = findViewById(R.id.btnRecord)
        tvStatus = findViewById(R.id.tvStatus)
        tvProb = findViewById(R.id.tvProb)

        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        spectrogramView.setFftSize(prefs.getInt(PREF_FFT_SIZE, 256))
        currentSampleRate = prefs.getInt(PREF_SAMPLE_RATE, 8000)
        sileroVad.setSampleRate(currentSampleRate)
        spectrogramView.sampleRate = currentSampleRate

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            showSettingsDialog()
        }

        btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                if (checkPermission()) {
                    startRecording()
                }
            }
        }

        if (!sileroVad.init(this)) {
            tvStatus.text = "VAD init failed"
        }
    }

    private fun showSettingsDialog() {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        // Sample Rate section
        val srLabel = TextView(this).apply {
            text = "Sample Rate: ${currentSampleRate}Hz"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 14f
        }
        view.addView(srLabel)

        val srLabels = SAMPLE_RATES.map { "${it / 1000}kHz" }.toTypedArray()
        val srIdx = SAMPLE_RATES.indexOf(currentSampleRate).coerceAtLeast(0)

        // FFT Size section
        val fftLabel = TextView(this).apply {
            text = "\nFFT Size: ${spectrogramView.getFftSize()}"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 14f
        }
        view.addView(fftLabel)

        MaterialAlertDialogBuilder(this, R.style.SettingsDialog)
            .setTitle("Settings")
            .setView(view)
            .setPositiveButton("Sample Rate") { dialog, _ ->
                dialog.dismiss()
                showSampleRateDialog()
            }
            .setNeutralButton("FFT Size") { dialog, _ ->
                dialog.dismiss()
                showFftSizeDialog()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSampleRateDialog() {
        val labels = SAMPLE_RATES.map { "${it / 1000}kHz" }.toTypedArray()
        val currentIdx = SAMPLE_RATES.indexOf(currentSampleRate).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this, R.style.SettingsDialog)
            .setTitle("Sample Rate")
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                val newRate = SAMPLE_RATES[which]
                if (newRate != currentSampleRate) {
                    val wasRecording = isRecording
                    if (wasRecording) stopRecording()

                    currentSampleRate = newRate
                    sileroVad.setSampleRate(newRate)
                    spectrogramView.sampleRate = newRate
                    spectrogramView.clear()
                    vadProbView.clear()

                    getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                        .edit().putInt(PREF_SAMPLE_RATE, newRate).apply()

                    if (wasRecording) startRecording()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFftSizeDialog() {
        val labels = FFT_SIZES.map { it.toString() }.toTypedArray()
        val currentIdx = FFT_SIZES.indexOf(spectrogramView.getFftSize()).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this, R.style.SettingsDialog)
            .setTitle("FFT Size")
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                val newSize = FFT_SIZES[which]
                spectrogramView.setFftSize(newSize)
                getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .edit().putInt(PREF_FFT_SIZE, newSize).apply()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST
            )
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        }
    }

    private fun startRecording() {
        val sr = currentSampleRate
        val chunkSize = sileroVad.chunkSize

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(
                sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ),
            chunkSize * 2 * 4
        )

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sr,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            tvStatus.text = "AudioRecord init failed"
            return
        }

        sileroVad.reset()
        spectrogramView.clear()
        vadProbView.clear()

        isRecording = true
        audioRecord?.startRecording()
        btnRecord.text = "Stop"
        tvStatus.text = "Recording... (${sr / 1000}kHz)"

        recordingThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buffer = ShortArray(chunkSize)
            val floatBuffer = FloatArray(chunkSize)

            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, chunkSize) ?: 0
                if (read <= 0) continue

                val columns = spectrogramView.addSamples(buffer, read)

                if (read == chunkSize) {
                    for (i in 0 until chunkSize) {
                        floatBuffer[i] = (buffer[i] / 32768.0f).coerceIn(-1f, 1f)
                    }
                    val prob = sileroVad.infer(floatBuffer)
                    for (r in 0 until columns) {
                        vadProbView.addProb(prob)
                    }

                    val isSpeech = prob >= 0.5f
                    runOnUiThread {
                        tvProb.text = String.format("%.2f", prob)
                        tvStatus.text = if (isSpeech) "Speech" else "Silence"
                        tvStatus.setTextColor(
                            if (isSpeech) 0xFFFF6B6B.toInt() else 0xFF4CAF50.toInt()
                        )
                    }
                }
            }
        }, "AudioRecordThread")
        recordingThread?.start()
    }

    private fun stopRecording() {
        isRecording = false
        recordingThread?.join(1000)
        recordingThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        btnRecord.text = "Start"
        tvStatus.text = "Stopped"
        tvStatus.setTextColor(0xFFFFFFFF.toInt())
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        sileroVad.release()
    }
}
