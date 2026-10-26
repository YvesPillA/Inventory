package com.fieldstock.app.scan

import com.fieldstock.app.i18n.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val MarkerBlue = Color(0xFF125BCB)
private val MarkerCyan = Color(0xFF66C4FF)

@Composable
internal fun LiveScanOverlay(codes: List<TrackedCode>, roi: CodeBox, enabled: Boolean, select: (TrackedCode) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val markers = LiveOverlayLayout.arrange(codes, roi, maxWidth.value, maxHeight.value)
        Canvas(Modifier.fillMaxSize()) {
            codes.forEach { hit ->
                val box = hit.code.bounds.inPreview(roi)
                drawRect(MarkerCyan, Offset(box.left * size.width, box.top * size.height),
                    Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height),
                    style = Stroke(2.dp.toPx()))
            }
            markers.forEach { marker ->
                drawLine(MarkerCyan, Offset(marker.target.centerX * size.width, marker.target.centerY * size.height),
                    Offset(marker.label.centerX.dp.toPx(), marker.label.centerY.dp.toPx()), strokeWidth = 1.5.dp.toPx())
            }
        }
        markers.forEach { marker ->
            val value = marker.code.code.candidate.value
            Box(
                Modifier.offset { IntOffset(with(density) { marker.label.left.dp.toPx() }.roundToInt(),
                    with(density) { marker.label.top.dp.toPx() }.roundToInt()) }
                    .width((marker.label.right - marker.label.left).dp).height(LiveOverlayLayout.ROW_HEIGHT.dp)
                    .clip(RoundedCornerShape(6.dp)).background(MarkerBlue.copy(alpha = 0.94f))
                    .border(1.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled) { select(marker.code) }
                    .semantics { contentDescription = tr(TextKey.ON_SCREEN_CODE, marker.number, value) }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("${marker.number}  $value", color = Color.White, fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun LiveCodeList(codes: List<TrackedCode>, paused: Boolean, enabled: Boolean, select: (TrackedCode) -> Unit) {
    Column(Modifier.fillMaxWidth().height(144.dp).background(Color.White).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(
            if (codes.isEmpty()) if (paused) tr(TextKey.NO_VALID_IDENTIFIERS_IN_THIS_FRAME_CONTINUE_SCANNING) else tr(TextKey.HOLD_STILL_BRIEFLY_TO_SEE_IDENTIFIERS_LINE_BY_LINE)
            else tr(TextKey.IDENTIFIERS_SCROLL_TO_SELECT, if (paused) tr(TextKey.FRAME_PAUSED) else tr(TextKey.SCANNING_CONTINUOUSLY), codes.size),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp),
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(codes, key = { _, hit -> hit.id }) { index, hit ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0xFFE8F0FE))
                    .clickable(enabled = enabled) { select(hit) }
                    .semantics { contentDescription = tr(TextKey.LISTED_CODE, index + 1, hit.code.candidate.value) }
                    .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", color = MarkerBlue, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                    Text(hit.code.candidate.value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(tr(TextKey.SELECT_2), color = MarkerBlue, fontSize = 12.sp)
                }
            }
        }
    }
}
