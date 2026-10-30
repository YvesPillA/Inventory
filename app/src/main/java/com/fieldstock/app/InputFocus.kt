package com.fieldstock.app

import androidx.compose.ui.focus.FocusManager
import com.fieldstock.app.diagnostics.AppDiagnostics

/** A stale focus owner must never cancel a save, page change or scanner launch. */
internal fun clearInputFocus(focus: FocusManager, hideKeyboard: () -> Unit) {
    try {
        focus.clearFocus()
    } catch (failure: IllegalArgumentException) {
        if (failure.message != "ActiveParent with no focused child") throw failure
        AppDiagnostics.event("ui.focus.recovered", level = "WARN", failure = failure)
    } finally {
        hideKeyboard()
    }
}
