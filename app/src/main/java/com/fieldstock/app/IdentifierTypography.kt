package com.fieldstock.app

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// Glyphs distinguish 0/O and 1/I/l/i; underlying identifiers remain plain ASCII.
internal val IdentifierFont = FontFamily(
    Font(R.font.identifier_regular, FontWeight.Normal),
    Font(R.font.identifier_semibold, FontWeight.SemiBold),
    Font(R.font.identifier_bold, FontWeight.Bold),
)
