package org.futo.inputmethod.latin.uix.actions.clipboard

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.uix.actions.throwIfDebug
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.getSettingFlow

interface ScreenshotListener {
    fun onScreenshotAdded(mime: String, uri: Uri)
}

internal fun shouldObserveScreenshots(
    historyEnabled: Boolean,
    incognitoMode: Boolean,
    saveScreenshots: Boolean,
    hasPermission: Boolean
): Boolean = historyEnabled && !incognitoMode && saveScreenshots && hasPermission

class ScreenshotHelper(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val listener: ScreenshotListener
) {
    companion object {
        val permission =
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
    }

    private val contentResolver = context.contentResolver
    private var lastSeenId: Long = -1L
    private var observer: ContentObserver? = null

    private val settingsObservingJob = lifecycleScope.launch {
        combine(
            context.getSettingFlow(ClipboardHistoryEnabled),
            context.getSettingFlow(ClipboardIncognitoMode),
            context.getSettingFlow(ClipboardSaveScreenshots)
        ) { historyEnabled, incognitoMode, saveScreenshots ->
            shouldObserveScreenshots(
                historyEnabled = historyEnabled,
                incognitoMode = incognitoMode,
                saveScreenshots = saveScreenshots,
                hasPermission = hasPermission()
            )
        }.collect { shouldObserve ->
            when {
                shouldObserve && observer == null -> registerObserver()
                !shouldObserve && observer != null -> unregisterObserver()
            }
        }
    }

    private fun registerObserver() {
        lifecycleScope.launch { handleNewScreenshot(dry = true) }

        val newObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                lifecycleScope.launch {
                    delay(16L)
                    handleNewScreenshot()
                }
            }
        }

        observer = newObserver
        try {
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                newObserver
            )
        } catch(e: Exception) {
            observer = null
            throwIfDebug(e)
        }
    }

    private fun unregisterObserver() {
        observer?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch(e: Exception) {
                throwIfDebug(e)
            }
        }
        observer = null
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun onDestroy() {
        unregisterObserver()
        settingsObservingJob.cancel()
    }

    private suspend fun handleNewScreenshot(dry: Boolean = false) = withContext(Dispatchers.IO) {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@withContext
        if(!context.getSetting(ClipboardHistoryEnabled)) return@withContext
        if(context.getSetting(ClipboardIncognitoMode)) return@withContext
        if(!context.getSetting(ClipboardSaveScreenshots)) return@withContext
        if(!hasPermission()) return@withContext

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        val selection = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE '%Pictures/Screenshots%' " +
                "AND ${MediaStore.Images.Media._ID} > ?"
        } else {
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE '%screenshot%' " +
                "AND ${MediaStore.Images.Media._ID} > ?"
        }

        try {
            val queryArgs = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(lastSeenId.toString()))
                putString(
                    ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    if(dry) {
                        "${MediaStore.Images.Media.DATE_ADDED} DESC"
                    } else {
                        "${MediaStore.Images.Media.DATE_ADDED} ASC"
                    }
                )

                if(dry && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
                }
            }

            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                queryArgs,
                null
            )?.use { cursor ->
                if(!cursor.moveToFirst()) return@withContext

                while(true) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    if(id <= lastSeenId) return@withContext

                    val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE))
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    if(!dry) {
                        listener.onScreenshotAdded(mime, uri)
                    }
                    lastSeenId = id

                    if(!cursor.moveToNext()) break
                }
            }
        } catch(e: Exception) {
            throwIfDebug(e)
        }
    }
}
