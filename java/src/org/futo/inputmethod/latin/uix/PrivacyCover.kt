package org.futo.inputmethod.latin.uix

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.Window
import java.util.WeakHashMap

object PrivacyCover {
    private val covers = WeakHashMap<Window, View>()

    @JvmStatic
    fun show(activity: Activity) {
        val window = activity.window ?: return
        if(covers.containsKey(window)) return

        val cover = View(activity).apply {
            setBackgroundColor(activity.resolveWindowBackgroundColor())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = true
        }

        activity.addContentView(
            cover,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        covers[window] = cover
    }

    @JvmStatic
    fun hide(activity: Activity) {
        val window = activity.window ?: return
        val cover = covers.remove(window) ?: return
        (cover.parent as? ViewGroup)?.removeView(cover)
    }

    private fun Activity.resolveWindowBackgroundColor(): Int {
        val value = TypedValue()
        if(!theme.resolveAttribute(android.R.attr.windowBackground, value, true)) {
            return Color.BLACK
        }

        if(value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data
        }

        if(value.resourceId == 0) return Color.BLACK

        return (getDrawable(value.resourceId) as? ColorDrawable)?.color ?: Color.BLACK
    }
}
