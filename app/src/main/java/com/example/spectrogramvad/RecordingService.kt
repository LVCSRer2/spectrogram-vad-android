package com.example.spectrogramvad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.File

class RecordingService : Service() {

    companion object {
        private const val CHANNEL_ID = "RecordingChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "RecordingService"
    }

    private val binder = RecordingBinder()
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false
    private var isPaused = false
    private var sampleRate = 8000
    private val sileroVad = SileroVad()

    interface RecordingListener {
        fun onDataRead(buffer: ShortArray, read: Int, prob: Float, columns: Int)
        fun onStateChanged(recording: Boolean, paused: Boolean)
    }

    private var listener: RecordingListener? = null

    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sileroVad.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    fun setListener(l: RecordingListener?) {
        this.listener = l
    }

    fun startRecording(sr: Int, pcmPath: String, vadPath: String) {
        if (isRecording) return
        this.sampleRate = sr
        sileroVad.setSampleRate(sr)
        sileroVad.reset()

        val chunkSize = sileroVad.chunkSize
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            chunkSize * 2 * 4
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sr,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord SecurityException: ${e.message}")
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            return
        }

        isRecording = true
        isPaused = false
        audioRecord?.startRecording()
        
        startForeground(NOTIFICATION_ID, createNotification("Recording..."))

        recordingThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buffer = ShortArray(chunkSize)
            val floatBuffer = FloatArray(chunkSize)
            val pcmOutputStream = FileOutputStream(pcmPath)
            val vadOutputStream = DataOutputStream(FileOutputStream(vadPath))

            try {
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, chunkSize) ?: 0
                    if (read <= 0 || isPaused) {
                        if (isPaused) Thread.sleep(50)
                        continue
                    }

                    // Save PCM
                    val byteBuffer = ByteArray(read * 2)
                    for (i in 0 until read) {
                        byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                        byteBuffer[i * 2 + 1] = (buffer[i].toInt() shr 8).toByte()
                    }
                    pcmOutputStream.write(byteBuffer)

                    // VAD and Notify
                    if (read == chunkSize) {
                        for (i in 0 until chunkSize) floatBuffer[i] = (buffer[i] / 32768.0f).coerceIn(-1f, 1f)
                        val prob = sileroVad.infer(floatBuffer)
                        
                        // We assume 512 samples per column for visualization sync
                        // This is a simplification, ideally columns should be calculated by the View
                        // but we need to write to VAD file here.
                        val columns = 1 // Simplified for background
                        for (r in 0 until columns) {
                            vadOutputStream.writeFloat(prob)
                        }
                        
                        listener?.onDataRead(buffer, read, prob, columns)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    pcmOutputStream.flush(); pcmOutputStream.close()
                    vadOutputStream.flush(); vadOutputStream.close()
                } catch (ignored: Exception) {}
            }
        }, "RecordingThread")
        recordingThread?.start()
        listener?.onStateChanged(isRecording, isPaused)
    }

    fun stopRecording() {
        isRecording = false
        isPaused = false
        recordingThread?.join(1000)
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        stopForeground(true)
        listener?.onStateChanged(isRecording, isPaused)
    }

    fun pauseRecording() {
        if (isRecording) {
            isPaused = true
            updateNotification("Paused")
            listener?.onStateChanged(isRecording, isPaused)
        }
    }

    fun resumeRecording() {
        if (isRecording) {
            isPaused = false
            updateNotification("Recording...")
            listener?.onStateChanged(isRecording, isPaused)
        }
    }

    fun isRecording() = isRecording
    fun isPaused() = isPaused

    private fun createNotification(content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Spectrogram VAD")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.presence_audio_busy)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Recording Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        sileroVad.release()
    }
}
