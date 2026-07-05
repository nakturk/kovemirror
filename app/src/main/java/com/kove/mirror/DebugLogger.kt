package com.kove.mirror

import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel { INFO, SUCCESS, WARNING, ERROR, DATA, HEARTBEAT }

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val message: String
)

object DebugLogger {
    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var logFile: File? = null

    fun addListener(listener: (LogEntry) -> Unit) = listeners.add(listener)
    fun removeListener(listener: (LogEntry) -> Unit) = listeners.remove(listener)

    fun initFile(baseDir: File?) {
        if (baseDir != null) {
            try {
                if (!baseDir.exists()) baseDir.mkdirs()
                logFile = File(baseDir, "kove_mirror_log.txt")
                // Her başlatıldığında temizle
                if (logFile?.exists() == true) logFile?.delete()
                logFile?.createNewFile()
                info("📂 Log file created: ${logFile?.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.e("KoveMirror", "Log file creation error", e)
            }
        }
    }

    fun log(level: LogLevel, message: String) {
        val tag = "KoveMirror"
        val levelStr = level.name
        val timestamp = timeFormat.format(Date())
        val formattedLine = "[$timestamp] [$levelStr] $message"

        when (level) {
            LogLevel.SUCCESS   -> android.util.Log.i(tag, "[SUCCESS] $message")
            LogLevel.ERROR     -> android.util.Log.e(tag, "[ERROR] $message")
            LogLevel.WARNING   -> android.util.Log.w(tag, "[WARNING] $message")
            LogLevel.HEARTBEAT -> android.util.Log.d(tag, "[HEARTBEAT] $message")
            LogLevel.DATA      -> android.util.Log.d(tag, "[DATA] $message")
            LogLevel.INFO      -> android.util.Log.i(tag, "[INFO] $message")
        }

        logFile?.let { file ->
            try {
                file.appendText(formattedLine + "\n")
            } catch (e: Exception) {
                // Ignore
            }
        }

        val entry = LogEntry(timestamp, level, message)
        mainHandler.post { listeners.forEach { it(entry) } }
    }

    fun info(msg: String)      = log(LogLevel.INFO, msg)
    fun success(msg: String)   = log(LogLevel.SUCCESS, msg)
    fun warning(msg: String)   = log(LogLevel.WARNING, msg)
    fun error(msg: String)     = log(LogLevel.ERROR, msg)
    fun data(msg: String)      = log(LogLevel.DATA, msg)
    fun heartbeat(msg: String) = log(LogLevel.HEARTBEAT, msg)
}
