package com.kove.mirror

import android.content.Context
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
    private var appContext: Context? = null

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    fun addListener(listener: (LogEntry) -> Unit) = listeners.add(listener)
    fun removeListener(listener: (LogEntry) -> Unit) = listeners.remove(listener)

    fun initFile(baseDir: File?) {
        if (baseDir != null) {
            try {
                if (!baseDir.exists()) baseDir.mkdirs()
                logFile = File(baseDir, "kove_mirror_log.txt")
                if (logFile?.exists() == true) logFile?.delete()
                logFile?.createNewFile()
            } catch (e: Exception) {
                android.util.Log.e("KoveMirror", "Log file creation error", e)
            }
        }
    }

    fun getString(resId: Int, vararg formatArgs: Any): String {
        val ctx = appContext
        return if (ctx != null) {
            val localizedCtx = LocaleHelper.applyLocale(ctx)
            if (formatArgs.isNotEmpty()) {
                localizedCtx.getString(resId, *formatArgs)
            } else {
                localizedCtx.getString(resId)
            }
        } else {
            "Resource #$resId"
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
            } catch (_: Exception) {}
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

    fun info(resId: Int, vararg formatArgs: Any)      = info(getString(resId, *formatArgs))
    fun success(resId: Int, vararg formatArgs: Any)   = success(getString(resId, *formatArgs))
    fun warning(resId: Int, vararg formatArgs: Any)   = warning(getString(resId, *formatArgs))
    fun error(resId: Int, vararg formatArgs: Any)     = error(getString(resId, *formatArgs))
    fun data(resId: Int, vararg formatArgs: Any)      = data(getString(resId, *formatArgs))
    fun heartbeat(resId: Int, vararg formatArgs: Any) = heartbeat(getString(resId, *formatArgs))
}
