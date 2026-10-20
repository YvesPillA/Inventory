package com.fieldstock.app.scan

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
        require(index in value.indices && replacement in options(value[index])) { "请选择当前字符的替换选项" }
        return value.replaceRange(index, index + 1, replacement.toString())
    }
    fun label(character: Char): String = when (character) {
        in '0'..'9' -> "数字 $character"
        in 'A'..'Z' -> "大写 $character"
        else -> "小写 $character"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CharacterReview(value: String, onChange: (String) -> Unit) {
    val positions = OcrCharacterReview.positions(value)
    var chosen by remember(value.length) { mutableIntStateOf(-1) }
    if (positions.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("易混字符逐位核对", style = MaterialTheme.typography.titleSmall)
        Text("点选位置后按标签替换。标记表示容易混淆，不代表识别有误。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            positions.forEach { index ->
                FilterChip(
                    selected = chosen == index,
                    onClick = { chosen = index },
                    label = { Text("${index + 1}：${value[index]}", fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "核对第 ${index + 1} 位 ${value[index]}" },
                )
            }
        }
        if (chosen in positions) {
            Text("第 ${chosen + 1} 位：${OcrCharacterReview.label(value[chosen])}", fontSize = 14.sp)
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
