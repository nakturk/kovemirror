package com.kove.mirror

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object SecurityManager {

    private const val TAG = "SecurityManager"

    /**
     * Checks if a debugger is currently attached or if the app is debuggable in release mode.
     * @return true if debugger is detected, false otherwise.
     */
    fun isDebuggerAttached(context: Context): Boolean {
        // Check if debugger is actively connected
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            Log.w(TAG, "Debugger is actively connected!")
            return true
        }

        // Check if the application has the debuggable flag set
        // In a true release build, this should be false.
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        
        // Only return true if we are in a release build but somehow debuggable flag is true
        // If BuildConfig.DEBUG is true, we expect the debuggable flag, so it's fine.
        if (isDebuggable && !BuildConfig.DEBUG) {
            Log.w(TAG, "App is debuggable in a release build!")
            return true
        }

        return false
    }

    /**
     * Attempts to detect if the device is rooted by checking common binaries and paths.
     * @return true if root is likely detected, false otherwise.
     */
    fun isDeviceRooted(): Boolean {
        return checkRootFiles() || checkSuCommand()
    }

    private fun checkRootFiles(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        for (path in paths) {
            if (File(path).exists()) {
                Log.w(TAG, "Root detected: Found binary at $path")
                return true
            }
        }
        return false
    }

    private fun checkSuCommand(): Boolean {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            if (reader.readLine() != null) {
                Log.w(TAG, "Root detected: su command executed successfully")
                return true
            }
            return false
        } catch (t: Throwable) {
            return false
        } finally {
            process?.destroy()
        }
    }
}
