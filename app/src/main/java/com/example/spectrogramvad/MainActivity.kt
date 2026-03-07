package com.example.spectrogramvad

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : AppCompatActivity(), RecordingService.RecordingListener {

    companion object {
        private const val LOG_TAG = "MainActivity"
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
    private lateinit var btnPause: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvProb: TextView

    // Bluetooth SCO
    private lateinit var audioManager: AudioManager
    private var bluetoothScoOn = false
    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                Log.i(LOG_TAG, "Bluetooth SCO connected")
            } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                Log.i(LOG_TAG, "Bluetooth SCO disconnected")
            }
        }
    }

    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            for (device in addedDevices) {
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    startBluetoothMic()
                    if (isRecording) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Bluetooth Connected", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            for (device in removedDevices) {
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    stopBluetoothMic()
                    if (isRecording) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Bluetooth Disconnected", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
    }

    // Recording Service
    private var recordingService: RecordingService? = null
    private var isBound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.RecordingBinder
            recordingService = binder.getService()
            recordingService?.setListener(this@MainActivity)
            isBound = true
            updateUIFromServiceState()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isBound = false
        }
    }

    private lateinit var playbackLayout: LinearLayout
    private lateinit var btnPlayPause: Button
    private lateinit var playbackSeekBar: SeekBar
    private lateinit var tvPlaybackTime: TextView

    private var isRecording = false
    private var isPaused = false
    private var currentSampleRate = 8000
    private var currentRecordingName: String? = null
    private var vadOutputStream: DataOutputStream? = null

    private var isPlaying = false
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private var playbackAudioPath: String? = null
    private var currentPlaybackName: String? = null
    private var pcmFileLength: Long = 0
    private var playbackPositionBytes: Long = 0
    private val uiHandler = Handler(Looper.getMainLooper())
    private var playbackUpdater: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        spectrogramView = findViewById(R.id.spectrogramView)
        vadProbView = findViewById(R.id.vadProbView)
        btnRecord = findViewById(R.id.btnRecord)
        btnPause = findViewById(R.id.btnPause)
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
        spectrogramView.sampleRate = currentSampleRate

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        findViewById<ImageButton>(R.id.btnRecordings).setOnClickListener { showRecordingsDialog() }

        spectrogramView.setOnSeekListener(object : SpectrogramView.SeekListener {
            override fun onSeek(ms: Int) { seekToMs(ms) }
            override fun onOffsetChanged(offsetMs: Float) { vadProbView.setViewOffsetMs(offsetMs) }
            override fun onZoomChanged(windowSizeMs: Int) { vadProbView.setWindowSizeMs(windowSizeMs) }
        })

        vadProbView.setOnSeekListener(object : VadProbView.SeekListener {
            override fun onSeek(ms: Int) { seekToMs(ms) }
            override fun onOffsetChanged(offsetMs: Float) { spectrogramView.setViewOffsetMs(offsetMs) }
        })

        btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else if (checkPermission()) startRecording()
        }

        btnPause.setOnClickListener {
            if (isRecording) {
                if (isPaused) recordingService?.resumeRecording() else recordingService?.pauseRecording()
            }
        }

        registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        startBluetoothMic()

        val intent = Intent(this, RecordingService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun updateUIFromServiceState() {
        recordingService?.let {
            isRecording = it.isRecording()
            isPaused = it.isPaused()
            onStateChanged(isRecording, isPaused)
        }
    }

    override fun onDataRead(buffer: ShortArray, read: Int, prob: Float) {
        runOnUiThread {
            // Actual columns rendered by SpectrogramView
            val actualCols = spectrogramView.addSamples(buffer, read)
            
            // Sync VAD recording with spectrogram columns
            for (r in 0 until actualCols) {
                vadProbView.addProb(prob)
                try {
                    vadOutputStream?.writeFloat(prob)
                } catch (e: Exception) {}
            }
            
            val isSpeech = prob >= 0.5f
            tvProb.text = String.format("%.2f", prob)
            tvStatus.text = if (isSpeech) "Speech" else "Recording..."
            tvStatus.setTextColor(if (isSpeech) 0xFFFF6B6B.toInt() else 0xFF4CAF50.toInt())
        }
    }

    override fun onStateChanged(recording: Boolean, paused: Boolean) {
        val wasRecording = this.isRecording
        this.isRecording = recording
        this.isPaused = paused
        runOnUiThread {
            if (recording) {
                btnRecord.text = "STOP"
                btnPause.visibility = View.VISIBLE
                btnPause.text = if (paused) "RESUME" else "PAUSE"
                tvStatus.text = if (paused) "Paused" else "Recording..."
                if (paused) tvStatus.setTextColor(0xFFFFFFFF.toInt())
            } else {
                btnRecord.text = "RECORD"
                btnPause.visibility = View.GONE
                tvStatus.text = "Stopped"
                tvStatus.setTextColor(0xFFFFFFFF.toInt())
                
                // Close VAD stream
                try { vadOutputStream?.flush(); vadOutputStream?.close() } catch (e: Exception) {}
                vadOutputStream = null

                if (wasRecording) {
                    currentRecordingName?.let { enterPlaybackMode(it) }
                }
            }
        }
    }

    private fun startBluetoothMic() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val devices = audioManager.availableCommunicationDevices
                for (device in devices) {
                    if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        audioManager.setCommunicationDevice(device)
                        bluetoothScoOn = true
                        return
                    }
                }
            }
            if (audioManager.isBluetoothScoAvailableOffCall) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                bluetoothScoOn = true
            }
        } catch (e: Exception) { Log.e(LOG_TAG, "Bluetooth SCO error: ${e.message}") }
    }

    private fun stopBluetoothMic() {
        if (bluetoothScoOn) {
            try {
                if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
                else { audioManager.isBluetoothScoOn = false; audioManager.stopBluetoothSco(); audioManager.mode = AudioManager.MODE_NORMAL }
            } catch (e: Exception) {}
            bluetoothScoOn = false
        }
    }

    private fun showSettingsDialog() {
        val view = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 0) }
        val srLabel = TextView(this).apply { text = "Sample Rate: ${currentSampleRate}Hz"; setTextColor(0xFFCCCCCC.toInt()); textSize = 14f }
        view.addView(srLabel)
        val fftLabel = TextView(this).apply { text = "\nFFT Size: ${spectrogramView.getFftSize()}"; setTextColor(0xFFCCCCCC.toInt()); textSize = 14f }
        view.addView(fftLabel)
        MaterialAlertDialogBuilder(this, R.style.SettingsDialog).setTitle("Settings").setView(view)
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
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startRecording()
    }

    private fun startRecording() {
        stopPlayback()
        spectrogramView.clear(); vadProbView.clear()
        val name = RecordingManager.createRecordingDir(this); currentRecordingName = name
        val pcmPath = RecordingManager.getAudioPath(this, name)
        val vadPath = RecordingManager.getVadPath(this, name)
        
        try {
            vadOutputStream = DataOutputStream(FileOutputStream(vadPath))
        } catch (e: Exception) { e.printStackTrace() }

        recordingService?.startRecording(currentSampleRate, pcmPath)
    }

    private fun stopRecording() {
        recordingService?.stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) { unbindService(connection); isBound = false }
        try { unregisterReceiver(scoReceiver) } catch (e: Exception) {}
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        stopBluetoothMic()
        stopPlayback()
    }

    // --- Playback ---

    private fun enterPlaybackMode(recordingName: String) {
        val audioPath = RecordingManager.getAudioPath(this, recordingName)
        val vadPath = RecordingManager.getVadPath(this, recordingName)
        val audioFile = File(audioPath); val vadFile = File(vadPath)
        if (!audioFile.exists()) { Toast.makeText(this, "Audio file not found", Toast.LENGTH_SHORT).show(); return }
        stopRecording(); stopPlayback()
        currentPlaybackName = recordingName
        playbackAudioPath = audioPath; pcmFileLength = audioFile.length()
        val durationMs = (pcmFileLength * 1000L / (currentSampleRate * 2)).toInt()
        playbackLayout.visibility = View.VISIBLE
        playbackSeekBar.max = durationMs; playbackSeekBar.progress = 0
        updatePlaybackUI(0)
        btnPlayPause.text = "Play"; playbackPositionBytes = 0; isPlaying = false

        if (vadFile.exists()) {
            val cachedProbs = readVadFile(vadFile)
            vadProbView.setFullVADData(cachedProbs, durationMs)
            vadProbView.setPlaybackMode(true)
            spectrogramView.setPlaybackFile(audioPath, (pcmFileLength / 2).toInt())
            updateVisualizationCursor(0)
        } else {
            tvStatus.text = "Loading VAD..."
            Thread({
                val calculatedProbs = calculateFullVAD(audioFile)
                runOnUiThread {
                    vadProbView.setFullVADData(calculatedProbs, durationMs)
                    vadProbView.setPlaybackMode(true)
                    spectrogramView.setPlaybackFile(audioPath, (pcmFileLength / 2).toInt())
                    updateVisualizationCursor(0)
                    tvStatus.text = "Ready"
                }
            }, "VadCalcThread").start()
        }
    }

    private fun readVadFile(file: File): FloatArray {
        val list = mutableListOf<Float>()
        try { DataInputStream(FileInputStream(file)).use { dis -> while (dis.available() > 0) list.add(dis.readFloat()) } } catch (e: Exception) { e.printStackTrace() }
        return list.toFloatArray()
    }

    private fun calculateFullVAD(file: File): FloatArray {
        val sr = currentSampleRate; val WINDOW_SIZE_MS = 20000; val MAX_COLUMNS = 300
        val totalDurationMs = (file.length() * 1000 / (sr * 2)).toInt()
        val totalBars = (totalDurationMs.toLong() * MAX_COLUMNS / WINDOW_SIZE_MS).toInt().coerceAtLeast(1)
        val vadResults = FloatArray(totalBars)
        val tempVad = SileroVad().apply { init(this@MainActivity); setSampleRate(sr) }
        try {
            FileInputStream(file).use { fis ->
                val samplesPerBar = (WINDOW_SIZE_MS * sr / 1000) / MAX_COLUMNS
                val byteBuffer = ByteArray(samplesPerBar * 2); val floatBuffer = FloatArray(tempVad.chunkSize)
                for (i in 0 until totalBars) {
                    val read = fis.read(byteBuffer); if (read <= 0) break
                    floatBuffer.fill(0f)
                    val samplesToProcess = minOf(tempVad.chunkSize, samplesPerBar)
                    for (j in 0 until samplesToProcess) {
                        val s = ((byteBuffer[j * 2].toInt() and 0xFF) or (byteBuffer[j * 2 + 1].toInt() shl 8)).toShort()
                        floatBuffer[j] = (s / 32768.0f).coerceIn(-1f, 1f)
                    }
                    vadResults[i] = tempVad.infer(floatBuffer)
                }
            }
        } catch (e: Exception) { e.printStackTrace() } finally { tempVad.release() }
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

    private fun formatTimeFull(ms: Int): String {
        val totalSec = ms / 1000; val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
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
                val tvName = view.findViewById<TextView>(R.id.recordingName)
                val tvDuration = view.findViewById<TextView>(R.id.recordingDuration)
                tvName.text = name
                val durationMs = RecordingManager.getDurationMs(this@MainActivity, name, currentSampleRate)
                tvDuration.text = "[${formatTimeFull(durationMs.toInt())}]"
                if (name == currentPlaybackName) {
                    view.setBackgroundColor(0x44FFFFFF.toInt())
                    tvName.setTextColor(0xFFFF6B6B.toInt())
                    tvName.setTypeface(null, android.graphics.Typeface.BOLD)
                } else {
                    view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    tvName.setTextColor(android.graphics.Color.WHITE)
                    tvName.setTypeface(null, android.graphics.Typeface.NORMAL)
                }
                return view
            }
        }
        listView.adapter = adapter
        val dialog = AlertDialog.Builder(this, R.style.SettingsDialog).setTitle("Recordings").setView(dialogView).setNegativeButton("Close", null).create()
        listView.setOnItemClickListener { _, _, position, _ -> dialog.dismiss(); enterPlaybackMode(recordings[position]) }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val name = recordings[position]
            AlertDialog.Builder(this, R.style.SettingsDialog).setTitle("Delete recording?").setMessage(name).setPositiveButton("Delete") { _, _ -> if (name == currentPlaybackName) stopPlayback(); RecordingManager.deleteRecording(this, name); showRecordingsDialog() }.setNegativeButton("Cancel", null).show()
            true
        }
        dialog.show()
    }
}
