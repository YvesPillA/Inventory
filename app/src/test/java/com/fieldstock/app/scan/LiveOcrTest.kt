package com.fieldstock.app.scan

import org.junit.Assert.*
import org.junit.Test

class LiveOcrTest {
    private fun line(text: String, top: Float = 0.2f, left: Float = 0.1f) =
        LiveTextLine(text, CodeBox(left, top, 0.85f, top + 0.06f))
    private fun codes(vararg text: String) = LiveOcr.locate(text.mapIndexed { i, value -> line(value, 0.15f + i * 0.2f) }, IdentifierField.BOM)
    private fun tracked(count: Int) = (1..count).map { i ->
        TrackedCode(i.toLong(), LocatedCode(OcrCandidate("A$i", "A$i"), CodeBox(0.1f, 0.4f, 0.8f, 0.45f)), 2)
    }

    @Test fun allPhysicalLinesAreLocatedInVisualOrder() {
        val data = LiveOcr.locate(listOf(line("BOM: B456", 0.6f), line("BOM: A123", 0.2f), line("SN: AA-001", 0.8f)), IdentifierField.BOM)
        assertEquals(listOf("A123", "B456"), data.map { it.candidate.value })
        assertEquals(listOf(0.2f, 0.6f), data.map { it.bounds.top })
    }

    @Test fun repeatedValueOnDifferentLinesKeepsEveryPosition() {
        val data = codes("000123A", "000123A", "000123A")
        assertEquals(3, data.size)
        assertEquals(3, data.map { it.bounds.top }.distinct().size)
    }

    @Test fun separateSnFragmentsAreNeverJoinedAcrossLines() {
        val data = LiveOcr.locate(listOf(line("ABCDEFG"), line("-1234567", 0.5f)), IdentifierField.SN)
        assertTrue(data.isEmpty())
    }

    @Test fun acceptsMaximumSnButNeverTruncatesOverlongValues() {
        val data = LiveOcr.locate(listOf(line("ABCDEFG-1234567"), line("ABCDEFGH-1234567", 0.5f)), IdentifierField.SN)
        assertEquals(listOf("ABCDEFG-1234567"), data.map { it.candidate.value })
        assertTrue(codes("123456789012345").isEmpty())
    }

    @Test fun mixedLineUsesTheRequestedFieldAndItsOwnElementBounds() {
        val bom = CodeBox(0.20f, 0.2f, 0.35f, 0.26f)
        val sn = CodeBox(0.55f, 0.2f, 0.90f, 0.26f)
        val row = LiveTextLine("BOM: A123 SN: AA-001", CodeBox(0.05f, 0.2f, 0.90f, 0.26f), listOf(
            LiveTextElement("BOM:", CodeBox(0.05f, 0.2f, 0.15f, 0.26f)), LiveTextElement("A123", bom),
            LiveTextElement("SN:", CodeBox(0.4f, 0.2f, 0.5f, 0.26f)), LiveTextElement("AA-001", sn),
        ))
        assertEquals(bom, LiveOcr.locate(listOf(row), IdentifierField.BOM).single().bounds)
        assertEquals(sn, LiveOcr.locate(listOf(row), IdentifierField.SN).single().bounds)
    }

    @Test fun explicitSpaceCorrectionKeepsTheUnionOfItsElements() {
        val row = LiveTextLine("BOM: 0012 3456", CodeBox(0.05f, 0.2f, 0.9f, 0.3f), listOf(
            LiveTextElement("BOM:", CodeBox(0.05f, 0.2f, 0.15f, 0.3f)),
            LiveTextElement("0012", CodeBox(0.2f, 0.2f, 0.4f, 0.3f)),
            LiveTextElement("3456", CodeBox(0.5f, 0.2f, 0.7f, 0.3f)),
        ))
        val found = LiveOcr.locate(listOf(row), IdentifierField.BOM).single()
        assertEquals("00123456", found.candidate.value)
        assertTrue(found.candidate.corrections.isNotEmpty())
        assertEquals(CodeBox(0.2f, 0.2f, 0.7f, 0.3f), found.bounds)
    }

    @Test fun rejectsPartlyClippedAndInvalidBounds() {
        val boxes = listOf(CodeBox(0f, 0.2f, 0.8f, 0.3f), CodeBox(0.2f, 0.2f, 1f, 0.3f),
            CodeBox(-0.1f, 0.2f, 0.8f, 0.3f), CodeBox(Float.NaN, 0.2f, 0.8f, 0.3f))
        boxes.forEach { assertTrue(LiveOcr.locate(listOf(LiveTextLine("A123", it)), IdentifierField.BOM).isEmpty()) }
    }

    @Test fun stableRowsRequireTwoConsecutiveMatchingObservations() {
        val tracker = LiveCodeTracker()
        val first = tracker.update(codes("A123")).single()
        val second = tracker.update(codes("A123")).single()
        assertFalse(first.stable)
        assertTrue(second.stable)
        assertEquals(first.id, second.id)
    }

    @Test fun disappearedRowIsRemovedImmediatelyAndGetsNewIdWhenItReturns() {
        val tracker = LiveCodeTracker()
        val first = tracker.update(codes("A123")).single()
        assertTrue(tracker.update(emptyList()).isEmpty())
        val returned = tracker.update(codes("A123")).single()
        assertNotEquals(first.id, returned.id)
        assertFalse(returned.stable)
    }

    @Test fun changedCodeCannotReuseTheOldClickTarget() {
        val tracker = LiveCodeTracker()
        val old = tracker.update(codes("A123")).single()
        val changed = tracker.update(codes("A128")).single()
        assertNotEquals(old.id, changed.id)
        assertFalse(changed.stable)
    }

    @Test fun identicalRowsHaveOneToOneIdentityEvenWhenModelOrderChanges() {
        val tracker = LiveCodeTracker()
        val first = tracker.update(codes("A123", "A123"))
        val second = tracker.update(codes("A123", "A123").reversed())
        assertEquals(first.first().id, second.last().id)
        assertEquals(first.last().id, second.first().id)
        assertEquals(2, second.map { it.id }.distinct().size)
    }

    @Test fun smallMotionKeepsTheSameTarget() {
        val tracker = LiveCodeTracker()
        val source = codes("A123").single()
        val old = tracker.update(listOf(source)).single()
        val shifted = source.copy(bounds = CodeBox(0.12f, 0.17f, 0.87f, 0.23f))
        val current = tracker.update(listOf(shifted)).single()
        assertEquals(old.id, current.id)
        assertEquals(shifted.bounds, current.code.bounds)
    }

    @Test fun largePositionJumpStartsASeparateTarget() {
        val tracker = LiveCodeTracker()
        val old = tracker.update(codes("A123")).single()
        val moved = tracker.update(listOf(LocatedCode(OcrCandidate("A123", "A123"), CodeBox(0.1f, 0.7f, 0.8f, 0.8f)))).single()
        assertNotEquals(old.id, moved.id)
    }

    @Test fun resetDoesNotRecycleOldClickIdentifiers() {
        val tracker = LiveCodeTracker()
        val old = tracker.update(codes("A123")).single()
        tracker.reset()
        assertNotEquals(old.id, tracker.update(codes("A123")).single().id)
    }

    @Test fun denseLabelsKeepMinimumTouchHeightAndDoNotOverlap() {
        val all = tracked(12)
        val arranged = LiveOverlayLayout.arrange(all, CodeBox(0.06f, 0.12f, 0.94f, 0.88f), 360f, 240f)
        assertEquals(4, arranged.size)
        assertEquals(12, all.size) // Overflow remains available to the complete row list.
        arranged.forEach {
            assertEquals(48f, it.label.bottom - it.label.top, 0.001f)
            assertTrue(it.label.left >= 8 && it.label.right <= 352 && it.label.top >= 8 && it.label.bottom <= 232)
        }
        arranged.zipWithNext().forEach { (a, b) -> assertTrue(a.label.bottom + 6 <= b.label.top) }
    }

    @Test fun narrowAndShortViewportsDoNotProduceOverlappingOrInvalidControls() {
        assertTrue(LiveOverlayLayout.arrange(tracked(3), CodeBox(0f, 0f, 1f, 1f), 60f, 240f).isEmpty())
        assertTrue(LiveOverlayLayout.arrange(tracked(3), CodeBox(0f, 0f, 1f, 1f), 360f, 40f).isEmpty())
        val narrow = LiveOverlayLayout.arrange(tracked(3), CodeBox(0f, 0f, 1f, 1f), 80f, 180f)
        assertTrue(narrow.isNotEmpty())
        assertTrue(narrow.all { it.label.right <= 72 && it.label.left >= 8 })
    }

    @Test fun allThreeNormalRowsRemainClickable() {
        val markers = LiveOverlayLayout.arrange(tracked(3), CodeBox(0.06f, 0.12f, 0.94f, 0.88f), 360f, 240f)
        assertEquals(listOf(1, 2, 3), markers.map { it.number })
        markers.forEachIndexed { i, a -> markers.drop(i + 1).forEach { b -> assertFalse(a.label.overlaps(b.label)) } }
    }

    @Test fun cropCoordinatesMapBackToTheDisplayedPreview() {
        val projected = CodeBox(0.25f, 0.5f, 0.75f, 0.9f).inPreview(CodeBox(0.06f, 0.12f, 0.94f, 0.88f))
        assertEquals(0.28f, projected.left, 0.001f)
        assertEquals(0.5f, projected.top, 0.001f)
        assertEquals(0.72f, projected.right, 0.001f)
        assertEquals(0.804f, projected.bottom, 0.001f)
    }
}
