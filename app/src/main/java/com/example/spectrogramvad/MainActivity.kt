package com.example.spectrogramvad

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

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

    private lateinit var playbackLayout: LinearLayout
    private lateinit var btnPlayPause: Button
    private lateinit var playbackSeekBar: SeekBar
    private lateinit var tvPlaybackTime: TextView

    private val sileroVad = SileroVad()
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var isRecording = false
    private var currentSampleRate = 8000
    private var currentRecordingName: String? = null

    private var isPlaying = false
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private var playbackAudioPath: String? = null
    private var currentPlaybackName: String? = null // For highlighting in list
    private var pcmFileLength: Long = 0
    private var playbackPositionBytes: Long = 0
    private val uiHandler = Handler(Looper.getMainLooper())
    private var playbackUpdater: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spectrogramView = findViewById(R.id.spectrogramView)
        vadProbView = findViewById(R.id.vadProbView)
        btnRecord = findViewById(R.id.btnRecord)
        tvStatus = findViewById(R.id.tvStatus)
        tvProb = findViewById(R.id.tvProb)

        playbackLayout = findViewById(R.id.playbackLayout)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        playbackSeekBar = findViewById(R.id.playbackSeekBar)
        tvPlaybackTime = findViewById(R.id.tvPlaybackTime)

        btnPlayPause.setOnClickListener { if (isPlaying) pausePlayback() else resumePlayback() }
        findViewById<ImageButton>(R.id.btnClosePlayback).setOnClickListener { stopPlayback() }

        playbackSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) seekToMs(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        spectrogramView.setFftSize(prefs.getInt(PREF_FFT_SIZE, 256))
        currentSampleRate = prefs.getInt(PREF_SAMPLE_RATE, 8000)
        sileroVad.setSampleRate(currentSampleRate)
        spectrogramView.sampleRate = currentSampleRate

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        findViewById<ImageButton>(R.id.btnRecordings).setOnClickListener { showRecordingsDialog() }

        spectrogramView.setOnSeekListener(object : SpectrogramView.SeekListener {
            override fun onSeek(ms: Int) { seekToMs(ms) }
            override fun onOffsetChanged(offsetMs: Float) { vadProbView.setViewOffsetMs(offsetMs) }
        })

        btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else if (checkPermission()) startRecording()
        }

        if (!sileroVad.init(this)) tvStatus.text = "VAD init failed"
    }

    private fun showSettingsDialog() {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }
        val srLabel = TextView(this).apply {
            text = "Sample Rate: ${currentSampleRate}Hz"
            setTextColor(0xFFCCCCCC.toInt()); textSize = 14f
        }
        view.addView(srLabel)
        val fftLabel = TextView(this).apply {
            text = "\nFFT Size: ${spectrogramView.getFftSize()}"
            setTextColor(0xFFCCCCCC.toInt()); textSize = 14f
        }
        view.addView(fftLabel)

        MaterialAlertDialogBuilder(this, R.style.SettingsDialog)
            .setTitle("Settings").setView(view)
            .setPositiveButton("Sample Rate") { dialog, _ -> dialog.dismiss(); showSampleRateDialog() }
            .setNeutralButton("FFT Size") { dialog, _ -> dialog.dismiss(); showFftSizeDialog() }
            .setNegativeButton("Close", null).show()
    }

    private fun showSampleRateDialog() {
        val labels = SAMPLE_RATES.map { "${it / 1000}kHz" }.toTypedArray()
        val currentIdx = SAMPLE_RATES.indexOf(currentSampleRate).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.SettingsDialog).setTitle("Sample Rate")
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                val newRate = SAMPLE_RATES[which]
                if (newRate != currentSampleRate) {
                    if (isRecording) stopRecording()
                    currentSampleRate = newRate
                    sileroVad.setSampleRate(newRate)
                    spectrogramView.sampleRate = newRate
                    spectrogramView.clear(); vadProbView.clear()
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putInt(PREF_SAMPLE_RATE, newRate).apply()
                }
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showFftSizeDialog() {
        val labels = FFT_SIZES.map { it.toString() }.toTypedArray()
        val currentIdx = FFT_SIZES.indexOf(spectrogramView.getFftSize()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.SettingsDialog).setTitle("FFT Size")
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                val newSize = FFT_SIZES[which]
                spectrogramView.setFftSize(newSize)
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putInt(PREF_FFT_SIZE, newSize).apply()
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun checkPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startRecording()
    }

    private fun startRecording() {
        stopPlayback()
        val sr = currentSampleRate; val chunkSize = sileroVad.chunkSize
        val bufferSize = maxOf(AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), chunkSize * 2 * 4)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) { tvStatus.text = "AudioRecord init failed"; return }

        sileroVad.reset(); spectrogramView.clear(); vadProbView.clear()
        val name = RecordingManager.createRecordingDir(this); currentRecordingName = name
        val pcmPath = RecordingManager.getAudioPath(this, name)

        isRecording = true; audioRecord?.startRecording()
        btnRecord.text = "STOP"
        tvStatus.text = "Recording... (${sr / 1000}kHz)"

        recordingThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buffer = ShortArray(chunkSize); val floatBuffer = FloatArray(chunkSize)
            val pcmOutputStream = FileOutputStream(pcmPath)
            try {
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, chunkSize) ?: 0
                    if (read <= 0) continue
                    val byteBuffer = ByteArray(read * 2)
                    for (i in 0 until read) {
                        byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                        byteBuffer[i * 2 + 1] = (buffer[i].toInt() shr 8).toByte()
                    }
                    pcmOutputStream.write(byteBuffer)
                    val columns = spectrogramView.addSamples(buffer, read)
                    if (read == chunkSize) {
                        for (i in 0 until chunkSize) floatBuffer[i] = (buffer[i] / 32768.0f).coerceIn(-1f, 1f)
                        val prob = sileroVad.infer(floatBuffer)
                        for (r in 0 until columns) vadProbView.addProb(prob)
                        val isSpeech = prob >= 0.5f
                        runOnUiThread {
                            tvProb.text = String.format("%.2f", prob)
                            tvStatus.text = if (isSpeech) "Speech" else "Silence"
                            tvStatus.setTextColor(if (isSpeech) 0xFFFF6B6B.toInt() else 0xFF4CAF50.toInt())
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() } finally {
                try { pcmOutputStream.flush(); pcmOutputStream.close() } catch (ignored: Exception) {}
            }
        }, "AudioRecordThread")
        recordingThread?.start()
    }

    private fun stopRecording() {
        isRecording = false; recordingThread?.join(1000); recordingThread = null
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        btnRecord.text = "RECORD"; tvStatus.text = "Stopped"; tvStatus.setTextColor(0xFFFFFFFF.toInt())
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording(); stopPlayback(); sileroVad.release()
    }

    // --- Playback ---

    private fun enterPlaybackMode(recordingName: String) {
        val audioPath = RecordingManager.getAudioPath(this, recordingName)
        val audioFile = File(audioPath)
        if (!audioFile.exists()) {
            Toast.makeText(this, "Audio file not found", Toast.LENGTH_SHORT).show()
            return
        }
        stopRecording(); stopPlayback()
        currentPlaybackName = recordingName
        playbackAudioPath = audioPath; pcmFileLength = audioFile.length()
        val durationMs = (pcmFileLength * 1000L / (currentSampleRate * 2)).toInt()

        playbackLayout.visibility = View.VISIBLE
        playbackSeekBar.max = durationMs; playbackSeekBar.progress = 0
        updatePlaybackUI(0)
        btnPlayPause.text = "Play"; playbackPositionBytes = 0; isPlaying = false

        val fullVAD = calculateFullVAD(audioFile)
        vadProbView.setFullVADData(fullVAD)
        vadProbView.setPlaybackMode(true)
        
        val totalSamples = (pcmFileLength / 2).toInt()
        spectrogramView.setPlaybackFile(audioPath, totalSamples)
        updateVisualizationCursor(0)
    }

    private fun calculateFullVAD(file: File): FloatArray {
        val sr = currentSampleRate; val chunkSize = sileroVad.chunkSize
        val WINDOW_SIZE_MS = 20000; val MAX_COLUMNS = 300
        val totalDurationMs = (file.length() * 1000 / (sr * 2)).toInt()
        val totalBars = (totalDurationMs.toLong() * MAX_COLUMNS / WINDOW_SIZE_MS).toInt().coerceAtLeast(1)
        val vadResults = FloatArray(totalBars)
        
        sileroVad.reset()
        try {
            FileInputStream(file).use { fis ->
                val samplesPerBar = (WINDOW_SIZE_MS * sr / 1000) / MAX_COLUMNS
                val byteBuffer = ByteArray(samplesPerBar * 2)
                val floatBuffer = FloatArray(chunkSize)
                for (i in 0 until totalBars) {
                    val read = fis.read(byteBuffer)
                    if (read <= 0) break
                    floatBuffer.fill(0f)
                    val samplesToProcess = minOf(chunkSize, samplesPerBar)
                    for (j in 0 until samplesToProcess) {
                        val s = ((byteBuffer[j * 2].toInt() and 0xFF) or (byteBuffer[j * 2 + 1].toInt() shl 8)).toShort()
                        floatBuffer[j] = (s / 32768.0f).coerceIn(-1f, 1f)
                    }
                    vadResults[i] = sileroVad.infer(floatBuffer)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        sileroVad.reset()
        return vadResults
    }

    private fun resumePlayback() {
        val path = playbackAudioPath ?: return
        if (isPlaying) return
        val sr = currentSampleRate
        val bufSize = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(bufSize).setTransferMode(AudioTrack.MODE_STREAM).build()
        audioTrack?.play(); isPlaying = true; btnPlayPause.text = "Pause"
        playbackThread = Thread({
            var fis: FileInputStream? = null
            try {
                fis = FileInputStream(path); fis.skip(playbackPositionBytes)
                val buffer = ByteArray(bufSize); var pos = playbackPositionBytes
                while (isPlaying && pos < pcmFileLength) {
                    val toRead = minOf(buffer.size.toLong(), pcmFileLength - pos).toInt()
                    val read = fis.read(buffer, 0, toRead)
                    if (read <= 0) break
                    val written = audioTrack?.write(buffer, 0, read) ?: 0
                    if (written > 0) { pos += written; playbackPositionBytes = pos }
                }
            } catch (e: Exception) { e.printStackTrace() } finally {
                fis?.close()
                if (isPlaying && playbackPositionBytes >= pcmFileLength) { runOnUiThread { pausePlayback(); seekToMs(0) } }
            }
        }, "PlaybackThread")
        playbackThread?.start(); startPlaybackUpdater()
    }

    private fun pausePlayback() {
        isPlaying = false; btnPlayPause.text = "Play"; stopPlaybackUpdater()
        audioTrack?.apply { try { pause(); flush(); stop(); release() } catch (e: Exception) {} }; audioTrack = null
        playbackThread?.join(500); playbackThread = null
    }

    private fun stopPlayback() {
        pausePlayback(); playbackLayout.visibility = View.GONE; playbackAudioPath = null
        currentPlaybackName = null
        playbackPositionBytes = 0; spectrogramView.clearPlaybackMode(); vadProbView.clearPlaybackMode()
    }

    private fun seekToMs(ms: Int) {
        val bytePos = (ms.toLong() * currentSampleRate * 2 / 1000) and -2L
        playbackPositionBytes = bytePos.coerceIn(0L, pcmFileLength)
        updatePlaybackUI(ms); updateVisualizationCursor(ms)
        if (isPlaying) { pausePlayback(); resumePlayback() }
    }

    private fun startPlaybackUpdater() {
        stopPlaybackUpdater()
        playbackUpdater = object : Runnable {
            override fun run() {
                if (isPlaying) {
                    val ms = (playbackPositionBytes * 1000 / (currentSampleRate * 2)).toInt()
                    updatePlaybackUI(ms); updateVisualizationCursor(ms)
                    uiHandler.postDelayed(this, 50)
                }
            }
        }
        uiHandler.post(playbackUpdater!!)
    }

    private fun stopPlaybackUpdater() { playbackUpdater?.let { uiHandler.removeCallbacks(it) }; playbackUpdater = null }

    private fun updateVisualizationCursor(ms: Int) {
        val durationMs = (pcmFileLength * 1000 / (currentSampleRate * 2)).toInt()
        val fraction = if (durationMs > 0) ms.toFloat() / durationMs else 0f
        spectrogramView.setCursorPosition(fraction, ms)
        vadProbView.setCursorPosition(ms)
    }

    private fun updatePlaybackUI(ms: Int) {
        val durationMs = (pcmFileLength * 1000 / (currentSampleRate * 2)).toInt()
        playbackSeekBar.progress = ms; tvPlaybackTime.text = "${formatTime(ms)}/${formatTime(durationMs)}"
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000; val m = totalSec / 60; val s = totalSec % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun showRecordingsDialog() {
        val recordings = RecordingManager.listRecordings(this)
        if (recordings.isEmpty()) { Toast.makeText(this, "No recordings found", Toast.LENGTH_SHORT).show(); return }
        val dialogView = layoutInflater.inflate(R.layout.dialog_recordings, null)
        val listView = dialogView.findViewById<ListView>(R.id.recordingsListView)
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = recordings.size
            override fun getItem(position: Int): Any = recordings[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_recording, parent, false)
                val name = recordings[position]
                val tv = view.findViewById<TextView>(R.id.recordingName)
                tv.text = name
                
                // Highlight if currently playing
                if (name == currentPlaybackName) {
                    view.setBackgroundColor(0x44FFFFFF.toInt())
                    tv.setTextColor(0xFFFF6B6B.toInt()) // Accent color
                    tv.setTypeface(null, android.graphics.Typeface.BOLD)
                } else {
                    view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    tv.setTextColor(android.graphics.Color.WHITE)
                    tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                }
                return view
            }
        }
        listView.adapter = adapter
        val dialog = AlertDialog.Builder(this, R.style.SettingsDialog).setTitle("Recordings").setView(dialogView).setNegativeButton("Close", null).create()
        listView.setOnItemClickListener { _, _, position, _ -> 
            val selected = recordings[position]
            dialog.dismiss()
            enterPlaybackMode(selected) 
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val name = recordings[position]
            AlertDialog.Builder(this, R.style.SettingsDialog).setTitle("Delete recording?").setMessage(name).setPositiveButton("Delete") { _, _ -> 
                if (name == currentPlaybackName) stopPlayback()
                RecordingManager.deleteRecording(this, name)
                showRecordingsDialog() 
            }.setNegativeButton("Cancel", null).show()
            true
        }
        dialog.show()
    }
}
