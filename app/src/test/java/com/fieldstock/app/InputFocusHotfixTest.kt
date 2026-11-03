package com.fieldstock.app

import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusDirection
import org.junit.Assert.*
import org.junit.Test

class InputFocusHotfixTest {
    private fun manager(failure: IllegalArgumentException?) = object : FocusManager {
        override fun clearFocus(force: Boolean) { failure?.let { throw it } }
        override fun moveFocus(focusDirection: FocusDirection) = false
    }
    @Test fun staleComposeFocusStillHidesKeyboardAndAllowsTheActionToContinue() {
        var hidden = false
        clearInputFocus(manager(IllegalArgumentException("ActiveParent with no focused child"))) { hidden = true }
        assertTrue(hidden)
    }
    @Test fun unrelatedFailuresAreNotSilentlySuppressed() {
        var hidden = false
        assertThrows(IllegalArgumentException::class.java) { clearInputFocus(manager(IllegalArgumentException("unrelated"))) { hidden = true } }
        assertTrue(hidden)
    }
}
