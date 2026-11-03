package com.fieldstock.app.onboarding

import android.content.Context

/** Only the initial language choice opts in; existing installations keep their normal startup. */
internal object TutorialPreferences {
    const val PENDING = "first_use_tutorial_pending"
    private const val COMPLETED = "first_use_tutorial_completed"

    fun pending(context: Context): Boolean =
        context.getSharedPreferences("fieldstock", Context.MODE_PRIVATE).getBoolean(PENDING, false) && !completed(context)

    fun completed(context: Context): Boolean =
        context.getSharedPreferences("fieldstock", Context.MODE_PRIVATE).getBoolean(COMPLETED, false)

    fun finish(context: Context) {
        context.getSharedPreferences("fieldstock", Context.MODE_PRIVATE).edit().putBoolean(COMPLETED, true).remove(PENDING).apply()
    }
}
