package com.kove.mirror

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashHandler : Thread.UncaughtExceptionHandler {
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var cacheDir: File? = null

    fun init(context: Context) {
        cacheDir = context.applicationContext.cacheDir
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackTrace = sw.toString()
            
            // Ana log dosyasına ekle
            DebugLogger.error("💥 CRASH IN THREAD '${thread.name}':\n$stackTrace")
            
            val file = File(cacheDir, "crash_log.txt")
            file.writeText(stackTrace)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        defaultHandler?.uncaughtException(thread, throwable)
    }

    fun getSavedCrash(context: Context): String? {
        val file = File(context.applicationContext.cacheDir, "crash_log.txt")
        if (file.exists()) {
            val text = file.readText()
            file.delete()
            return text
        }
        return null
    }
}
