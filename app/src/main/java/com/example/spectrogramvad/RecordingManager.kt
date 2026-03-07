package com.example.spectrogramvad

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.Date
import java.util.Locale

object RecordingManager {

    private const val RECORDINGS_DIR = "recordings"

    private fun getRecordingsRoot(context: Context): File {
        val root = File(context.filesDir, RECORDINGS_DIR)
        if (!root.exists()) {
            root.mkdirs()
        }
        return root
    }

    /** Create a new recording directory with timestamp name, return the directory name. */
    fun createRecordingDir(context: Context): String {
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(getRecordingsRoot(context), name)
        dir.mkdirs()
        return name
    }

    /** List recording names sorted newest first. */
    fun listRecordings(context: Context): List<String> {
        val root = getRecordingsRoot(context)
        val names = root.list()
        if (names == null || names.isEmpty()) {
            return emptyList()
        }
        val list = ArrayList(Arrays.asList(*names))
        Collections.sort(list, Collections.reverseOrder())
        return list
    }

    fun getAudioPath(context: Context, name: String): String {
        return File(File(getRecordingsRoot(context), name), "audio.pcm").absolutePath
    }

    fun getDurationMs(context: Context, name: String, sampleRate: Int): Long {
        val file = File(getAudioPath(context, name))
        if (!file.exists()) return 0L
        // PCM 16-bit Mono: 2 bytes per sample
        return (file.length() * 1000L / (sampleRate * 2))
    }

    fun deleteRecording(context: Context, name: String) {
        val dir = File(getRecordingsRoot(context), name)
        if (dir.exists() && dir.isDirectory) {
            val files = dir.listFiles()
            if (files != null) {
                for (f in files) {
                    f.delete()
                }
            }
            dir.delete()
        }
    }
}
