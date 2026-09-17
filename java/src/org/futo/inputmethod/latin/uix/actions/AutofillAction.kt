package org.futo.inputmethod.latin.uix.actions

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action

val AutofillAction = Action(
    icon = R.drawable.unlock,
    name = R.string.action_passwords_title,
    simplePressImpl = { manager, _ -> manager.requestAutofill() },
    altPressLabel = R.string.action_open_proton_pass,
    altPressImpl = { manager, _ ->
        val context = manager.getContext()
        val intent = context.packageManager.getLaunchIntentForPackage("proton.android.pass")
            ?: context.packageManager.getLaunchIntentForPackage("proton.android.pass.fdroid")
        if (intent == null) {
            Toast.makeText(context, R.string.proton_pass_unavailable, Toast.LENGTH_SHORT).show()
        } else {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.proton_pass_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    },
    windowImpl = null,
)
