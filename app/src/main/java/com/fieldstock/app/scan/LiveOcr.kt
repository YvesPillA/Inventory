package com.fieldstock.app.scan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Coordinates are relative to the recognized crop, independent of preview rotation/resolution. */
data class CodeBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    fun insideImage() = listOf(left, top, right, bottom).all(Float::isFinite) &&
        left > 0 && top > 0 && right < 1 && bottom < 1 && right > left && bottom > top
    fun inPreview(roi: CodeBox) = CodeBox(
        roi.left + left * (roi.right - roi.left), roi.top + top * (roi.bottom - roi.top),
        roi.left + right * (roi.right - roi.left), roi.top + bottom * (roi.bottom - roi.top),
    )
    fun overlaps(other: CodeBox): Boolean =
        max(left, other.left) < min(right, other.right) && max(top, other.top) < min(bottom, other.bottom)
    companion object {
        fun union(boxes: List<CodeBox>): CodeBox = CodeBox(boxes.minOf { it.left }, boxes.minOf { it.top },
            boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    }
}

data class LiveTextElement(val text: String, val bounds: CodeBox)
data class LiveTextLine(val text: String, val bounds: CodeBox, val elements: List<LiveTextElement> = emptyList())
data class LocatedCode(val candidate: OcrCandidate, val bounds: CodeBox)
data class TrackedCode(val id: Long, val code: LocatedCode, val observations: Int) {
    val stable get() = observations >= 2
}

object LiveOcr {
    /** Extract each physical line separately; repeated values on different lines retain their boxes. */
    fun locate(lines: List<LiveTextLine>, field: IdentifierField): List<LocatedCode> {
        if (field == IdentifierField.NOTE) {
            val visible = lines.filter { it.text.isNotBlank() }
            if (visible.isEmpty() || visible.any { !it.bounds.insideImage() }) return emptyList()
            val text = visible.joinToString("\n") { it.text }
            return OcrCandidates.extract(text, field).map { LocatedCode(it, CodeBox.union(visible.map(LiveTextLine::bounds))) }
        }
        return lines.flatMap { line ->
        var offset = 0
        val spans = line.elements.mapNotNull { element ->
            val start = line.text.indexOf(element.text, offset)
            if (start < 0) null else {
                offset = start + element.text.length
                Triple(start, offset, element.bounds)
            }
        }
        OcrCandidates.extract(line.text, field).mapNotNull { candidate ->
            val start = line.text.indexOf(candidate.raw)
            val end = start + candidate.raw.length
            val elements = if (start < 0) emptyList() else spans.filter { it.first < end && it.second > start }.map { it.third }
            val box = if (elements.isEmpty()) line.bounds else CodeBox.union(elements)
            // A code touching the crop boundary may be the readable fragment of a longer identifier.
            if (box.insideImage()) LocatedCode(candidate, box) else null
        }
        }.sortedWith(compareBy<LocatedCode> { it.bounds.centerY }.thenBy { it.bounds.left })
    }
}

/** One-to-one, frame-local association: no old row survives a missing/replaced detection. */
class LiveCodeTracker {
    private var previous = emptyList<TrackedCode>()
    private var nextId = 1L
    fun update(codes: List<LocatedCode>): List<TrackedCode> {
        val used = mutableSetOf<Long>()
        val current = codes.map { code ->
            val match = previous.filter { old ->
                old.id !in used && old.code.candidate.value == code.candidate.value &&
                    abs(old.code.bounds.centerX - code.bounds.centerX) < 0.15f &&
                    abs(old.code.bounds.centerY - code.bounds.centerY) < 0.10f
            }.minByOrNull { old ->
                abs(old.code.bounds.centerX - code.bounds.centerX) + abs(old.code.bounds.centerY - code.bounds.centerY)
            }
            if (match != null) {
                used += match.id
                TrackedCode(match.id, code, match.observations + 1)
            } else TrackedCode(nextId++, code, 1)
        }
        previous = current
        return current
    }
    fun reset() { previous = emptyList() }
}

data class LiveMarker(val code: TrackedCode, val number: Int, val label: CodeBox, val target: CodeBox)

object LiveOverlayLayout {
    const val ROW_HEIGHT = 48f
    const val GAP = 6f
    /** Label rectangles use dp, with a fixed minimum touch height and no overlap. Overflow stays in the list. */
    fun arrange(codes: List<TrackedCode>, roi: CodeBox, width: Float, height: Float): List<LiveMarker> {
        if (width < 80 || height < ROW_HEIGHT + 16) return emptyList()
        val margin = 8f
        val capacity = ((height - margin * 2 + GAP) / (ROW_HEIGHT + GAP)).toInt().coerceAtLeast(0)
        val shown = codes.take(capacity)
        var nextTop = margin
        return shown.mapIndexed { index, hit ->
            val target = hit.code.bounds.inPreview(roi)
            val remaining = shown.size - index
            val lastTop = height - margin - remaining * ROW_HEIGHT - (remaining - 1) * GAP
            val preferred = target.centerY * height - ROW_HEIGHT / 2
            val top = preferred.coerceIn(nextTop, lastTop.coerceAtLeast(nextTop))
            val maxWidth = min(250f, width - 16)
            val labelWidth = (hit.code.candidate.value.length * 10f + 52).coerceIn(min(130f, maxWidth), maxWidth)
            val left = (target.centerX * width - labelWidth / 2).coerceIn(margin, width - margin - labelWidth)
            nextTop = top + ROW_HEIGHT + GAP
            LiveMarker(hit, index + 1, CodeBox(left, top, left + labelWidth, top + ROW_HEIGHT), target)
        }
    }
}
