package com.fieldstock.app.scan

import com.fieldstock.app.i18n.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Positions are in the original string, including the SN hyphen. No guessed replacement is applied. */
object OcrCharacterReview {
    private val groups = listOf("0Oo", "1Iil", "8Bb")
    fun options(character: Char): List<Char> = groups.firstOrNull { character in it }?.toList().orEmpty()
    fun positions(value: String): List<Int> = value.indices.filter { options(value[it]).isNotEmpty() }
    fun replace(value: String, index: Int, replacement: Char): String {
        require(index in value.indices && replacement in options(value[index])) { tr(TextKey.CHOOSE_A_REPLACEMENT_FOR_THIS_CHARACTER) }
        return value.replaceRange(index, index + 1, replacement.toString())
    }
    fun label(character: Char): String = when (character) {
        in '0'..'9' -> tr(TextKey.DIGIT, character)
        in 'A'..'Z' -> tr(TextKey.UPPERCASE, character)
        else -> tr(TextKey.LOWERCASE, character)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CharacterReview(value: String, onChange: (String) -> Unit) {
    val positions = OcrCharacterReview.positions(value)
    var chosen by remember(value.length) { mutableIntStateOf(-1) }
    if (positions.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr(TextKey.REVIEW_AMBIGUOUS_CHARACTERS), style = MaterialTheme.typography.titleSmall)
        Text(tr(TextKey.SELECT_A_POSITION_THEN_TAP_A_REPLACEMENT_A_HIGHLIGHT), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            positions.forEach { index ->
                FilterChip(
                    selected = chosen == index,
                    onClick = { chosen = index },
                    label = { Text("${index + 1}：${value[index]}", fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = tr(TextKey.REVIEW_CHARACTER, index + 1, value[index]) },
                )
            }
        }
        if (chosen in positions) {
            Text(tr(TextKey.CHARACTER, chosen + 1, OcrCharacterReview.label(value[chosen])), fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OcrCharacterReview.options(value[chosen]).forEach { replacement ->
                    OutlinedButton(
                        onClick = { onChange(OcrCharacterReview.replace(value, chosen, replacement)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text(OcrCharacterReview.label(replacement), fontFamily = FontFamily.Monospace) }
                }
            }
        }
    }
}
