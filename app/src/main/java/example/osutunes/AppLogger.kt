package com.example.osutunes

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_NAME = "osuTunes_log.txt"
    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private fun getLogFile(context: Context): File {
        val dir = context.getExternalCacheDir()?.let { File(it, LOG_DIR_NAME) } 
            ?: File(context.cacheDir, LOG_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, LOG_FILE_NAME)
    }

    fun getLogPath(context: Context): String {
        return getLogFile(context).absolutePath
    }
    
    fun clearLogs(context: Context) {
        try {
            getLogFile(context).writeText("")
            Log.i("AppLogger", "Logs cleared on fresh launch")
        } catch (e: IOException) {
            Log.e("AppLogger", "Failed to clear logs", e)
        }
    }

    fun info(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        writeLine(context, "I/$tag: $message" + if (throwable != null) "\n${Log.getStackTraceString(throwable)}" else "")
        if (throwable != null) {
            Log.i(tag, message, throwable)
        } else {
            Log.i(tag, message)
        }
    }

    fun error(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        writeLine(context, "E/$tag: $message" + if (throwable != null) "\n${Log.getStackTraceString(throwable)}" else "")
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }

    private fun writeLine(context: Context, line: String) {
        try {
            getLogFile(context).appendText("${DATE_FORMAT.format(Date())} $line\n")
        } catch (e: IOException) {
            Log.e("AppLogger", "Unable to write log file", e)
        }
    }
}
