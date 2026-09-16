package org.futo.inputmethod.latin

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.edit
import org.futo.inputmethod.latin.uix.isDirectBootUnlocked
import kotlin.system.exitProcess

class CrashLoggingApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(LocalCrashHandler(
            store = localCrashReportStore(this),
            buildDetails = """
                App: ${BuildConfig.APPLICATION_ID}
                Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
                Build: ${BuildConfig.BUILD_TYPE}
                Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
                Device: ${Build.MANUFACTURER} ${Build.MODEL}
            """.trimIndent(),
            next = previous ?: Thread.UncaughtExceptionHandler { _, _ ->
                Process.killProcess(Process.myPid())
                exitProcess(10)
            },
            onFailure = { Log.e("CrashReports", "Could not save crash report", it) }
        ))

        if(isDirectBootUnlocked) {
            try {
                if (getSharedPreferences("migrate", MODE_PRIVATE).getBoolean(
                        "wiped_work",
                        false
                    ) == false
                ) {
                    deleteDatabase("androidx.work.workdb")
                    getSharedPreferences("androidx.work.util.preferences", MODE_PRIVATE)
                        .edit { clear() }
                    getSharedPreferences("migrate", MODE_PRIVATE)
                        .edit { putBoolean("wiped_work", true) }
                }
            } catch(e: Exception) {
                e.printStackTrace()
            }
        }
    }
}