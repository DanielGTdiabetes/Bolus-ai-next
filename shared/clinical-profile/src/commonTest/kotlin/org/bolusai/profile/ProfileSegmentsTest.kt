package org.bolusai.profile

import kotlin.random.Random
import kotlin.test.*

/** ADR 0013: segment editing. Synthetic values only; none is a clinical recommendation. */
class ProfileSegmentsTest {
    private val ratio = ProfileParameter.CARB_RATIO
    private val isf = ProfileParameter.INSULIN_SENSITIVITY
    private val target = ProfileParameter.GLUCOSE_TARGET
    private val day = SegmentRef(0, 1440)

    private fun split(content: ProfileContent, parameter: ProfileParameter, ref: SegmentRef, at: Int) =
        content.edited(SegmentOperation.Split(parameter, ref, at))
    private fun merge(content: ProfileContent, parameter: ProfileParameter, ref: SegmentRef, next: SegmentRef) =
        content.edited(SegmentOperation.MergeWithNext(parameter, ref, next))
    private fun move(content: ProfileContent, parameter: ProfileParameter, before: SegmentRef, after: SegmentRef, to: Int) =
        content.edited(SegmentOperation.MoveBoundary(parameter, before, after, to))
    private fun set(content: ProfileContent, parameter: ProfileParameter, ref: SegmentRef, value: ProfileValue) =
        content.edited(SegmentOperation.SetValue(parameter, ref, value))

    private fun assertInvariants(content: ProfileContent) {
        assertNull(ProfileContent.problem(content.schemaVersion, content.glucoseUnit, content.schedules))
        content.schedules.forEach { schedule ->
            assertEquals(0, schedule.segments.first().startMinute)
            assertEquals(1440, schedule.segments.last().endMinute)
            schedule.segments.zipWithNext().forEach { (a, b) -> assertEquals(a.endMinute, b.startMinute) }
        }
    }

    // Split

    @Test fun splitAtAnInteriorMinuteKeepsTheExactValueOnBothSides() {
        listOf(entered("10"), entered("0"), ProfileValue.NotConfigured).forEach { value ->
            val base = content(ratio = value)
            val result = split(base, ratio, day, 360).content()
            assertEquals(listOf(segment(0, 360, value), segment(360, 1440, value)), result.schedule(ratio).segments)
            assertEquals(base.schedule(isf), result.schedule(isf))
            assertInvariants(result)
        }
    }

    @Test fun splitOnAnEdgeOrOutsideIsRejected() {
        val base = content().withSchedule(ratio, segment(0, 360, entered("8")), segment(360, 1440, entered("9")))
        listOf(0, 360, -1, 1440, 1441).forEach {
            assertEquals(ProfileEdit.Rejected(ProfileFailure.SPLIT_OUT_OF_RANGE), split(base, ratio, SegmentRef(0, 360), it), "$it")
        }
        assertEquals(ProfileEdit.Rejected(ProfileFailure.SPLIT_OUT_OF_RANGE), split(base, ratio, SegmentRef(360, 1440), 200))
        // One-minute resolution: the first and last interior minutes are valid, nothing is rounded.
        assertEquals(listOf(segment(0, 1), segment(1, 1440)), split(content(ratio = ProfileValue.NotConfigured), ratio, day, 1)
            .content().schedule(ratio).segments)
        assertEquals(1439, split(content(), ratio, day, 1439).content().schedule(ratio).segments[1].startMinute)
        assertEquals(367, split(content(), ratio, day, 367).content().schedule(ratio).segments[1].startMinute)
    }

    @Test fun repeatedSplitOnAnIntervalThatNoLongerExistsChangesNothing() {
        val once = split(content(), ratio, day, 360).content()
        assertEquals(ProfileEdit.Rejected(ProfileFailure.STALE_SEGMENT), split(once, ratio, day, 360))
        assertEquals(ProfileEdit.Rejected(ProfileFailure.STALE_SEGMENT), split(once, ratio, SegmentRef(0, 300), 100))
    }

    @Test fun splitLimitAppliesOnlyToSplitting() {
        // 47 segments: splitting still works and reaches 48.
        val at47 = content().withSchedule(ratio, *(0 until 47).map { segment(it * 30, if (it == 46) 1440 else (it + 1) * 30, entered("${it + 1}")) }.toTypedArray())
        assertTrue(at47.schedule(ratio).canSplit)
        val at48 = split(at47, ratio, SegmentRef(1380, 1440), 1410).content()
        assertEquals(48, at48.schedule(ratio).segments.size)
        assertFalse(at48.schedule(ratio).canSplit)
        assertEquals(ProfileEdit.Rejected(ProfileFailure.SEGMENT_LIMIT_REACHED), split(at48, ratio, SegmentRef(1410, 1440), 1420))
    }

    @Test fun moreThan48DistinctSegmentsStayReadableEditableAndSaveableWithoutReduction() {
        val many = (0 until 60).map { segment(it * 24, (it + 1) * 24, entered("${it + 1}")) }
        val base = content().withSchedule(ratio, *many.toTypedArray())
        // Nothing in the model rejects it, no merge is possible, only splitting is refused.
        assertNull(ProfileContent.problem(1, base.glucoseUnit, base.schedules))
        assertTrue(many.none { base.schedule(ratio).canMergeWithNext(SegmentRef.of(it)) })
        assertEquals(ProfileEdit.Rejected(ProfileFailure.SEGMENT_LIMIT_REACHED), split(base, ratio, SegmentRef(0, 24), 12))
        val valued = set(base, ratio, SegmentRef(24, 48), entered("99")).content()
        val moved = move(valued, ratio, SegmentRef(24, 48), SegmentRef(48, 72), 50).content()
        assertEquals(60, moved.schedule(ratio).segments.size)
        val repository = MemoryProfileRepository()
        val profiles = profiles(repository)
        profiles.saved(profiles.newProfile().with(base))
        val v2 = profiles.saved(profiles.edit(profiles.loaded()).with(moved))
        assertEquals(60, v2.content.schedule(ratio).segments.size)
        assertEquals(60, profiles.loaded().latest.content.schedule(ratio).segments.size)
    }

    // Merge

    @Test fun mergeOnlyJoinsExactlyEqualValues() {
        val equal = content().withSchedule(ratio, segment(0, 360, entered("10")), segment(360, 720, entered("10")),
            segment(720, 1440, entered("12")))
        assertTrue(equal.schedule(ratio).canMergeWithNext(SegmentRef(0, 360)))
        assertFalse(equal.schedule(ratio).canMergeWithNext(SegmentRef(360, 720)))
        assertEquals(listOf(segment(0, 720, entered("10")), segment(720, 1440, entered("12"))),
            merge(equal, ratio, SegmentRef(0, 360), SegmentRef(360, 720)).content().schedule(ratio).segments)
        assertEquals(ProfileEdit.Rejected(ProfileFailure.MERGE_VALUES_DIFFER),
            merge(equal, ratio, SegmentRef(360, 720), SegmentRef(720, 1440)))

        val missing = content().withSchedule(ratio, segment(0, 360), segment(360, 1440))
        assertEquals(listOf(segment(0, 1440)), merge(missing, ratio, SegmentRef(0, 360), SegmentRef(360, 1440))
            .content().schedule(ratio).segments)
        // Not configured and zero are different values.
        val zero = content().withSchedule(ratio, segment(0, 360), segment(360, 1440, entered("0")))
        assertFalse(zero.schedule(ratio).canMergeWithNext(SegmentRef(0, 360)))
        assertEquals(ProfileEdit.Rejected(ProfileFailure.MERGE_VALUES_DIFFER), merge(zero, ratio, SegmentRef(0, 360), SegmentRef(360, 1440)))
    }

    @Test fun lastAndFirstSegmentsAreNotAdjacentAndStaleMergesChangeNothing() {
        val base = content().withSchedule(ratio, segment(0, 360, entered("10")), segment(360, 1320, entered("11")),
            segment(1320, 1440, entered("10")))
        assertFalse(base.schedule(ratio).canMergeWithNext(SegmentRef(1320, 1440)))
        assertEquals(ProfileEdit.Rejected(ProfileFailure.STALE_SEGMENT), merge(base, ratio, SegmentRef(1320, 1440), SegmentRef(0, 360)))
        val merged = content().withSchedule(ratio, segment(0, 360, entered("10")), segment(360, 1440, entered("10")))
        val once = merge(merged, ratio, SegmentRef(0, 360), SegmentRef(360, 1440)).content()
        assertEquals(ProfileEdit.Rejected(ProfileFailure.STALE_SEGMENT), merge(once, ratio, SegmentRef(0, 360), SegmentRef(360, 1440)))
    }

    // Boundaries

    @Test fun movingABoundaryMovesBothSidesAndKeepsValues() {
        val base = content().withSchedule(ratio, segment(0, 360, entered("8")), segment(360, 720, entered("9")),
            segment(720, 1440))
        val moved = move(base, ratio, SegmentRef(0, 360), SegmentRef(360, 720), 420).content()
        assertEquals(listOf(segment(0, 420, entered("8")), segment(420, 720, entered("9")), segment(720, 1440)),
            moved.schedule(ratio).segments)
        assertEquals(listOf(segment(0, 60, entered("8")), segment(60, 720, entered("9")), segment(720, 1440)),
            move(base, ratio, SegmentRef(0, 360), SegmentRef(360, 720), 60).content().schedule(ratio).segments)
        assertInvariants(moved)
    }

    @Test fun boundaryCannotCollapseJumpOrLeaveTheDay() {
        val base = content().withSchedule(ratio, segment(0, 360, entered("8")), segment(360, 720, entered("9")),
            segment(720, 1440, entered("10")))
        listOf(0, 720, 800, -5, 1440).forEach {
            assertEquals(ProfileEdit.Rejected(ProfileFailure.BOUNDARY_OUT_OF_RANGE),
                move(base, ratio, SegmentRef(0, 360), SegmentRef(360, 720), it), "$it")
        }
        // The fixed start 00:00 and end 24:00 are not boundaries: there is no segment before the first or after the last.
        assertEquals(ProfileEdit.Rejected(ProfileFailure.STALE_SEGMENT),
            move(base, ratio, SegmentRef(720, 1440), SegmentRef(1440, 1440 + 1), 1000))
        // A stale view of either side is rejected even if the boundary itself still exists.
        assertEquals(ProfileEdit.Rejected(ProfileFailure.STALE_SEGMENT),
            move(base, ratio, SegmentRef(0, 360), SegmentRef(360, 800), 400))
    }

    // Values

    @Test fun eachSegmentValueIsIndependentAndBlankIsNeverZero() {
        val base = content(ratio = entered("10")).let { split(it, ratio, day, 720).content() }
        val cleared = set(base, ratio, SegmentRef(0, 720), ProfileValue.NotConfigured).content()
        assertEquals(listOf(segment(0, 720), segment(720, 1440, entered("10"))), cleared.schedule(ratio).segments)
        val zero = set(cleared, ratio, SegmentRef(0, 720), entered("0")).content()
        assertNotEquals(ProfileCodec.sha256(cleared), ProfileCodec.sha256(zero))
        assertEquals(ProfileEdit.Rejected(ProfileFailure.STALE_SEGMENT), set(base, ratio, day, entered("1")))
        assertEquals(ProfileEdit.Rejected(ProfileFailure.UNIT_REQUIRED),
            set(ProfileContent.empty(), target, day, entered("110")))
    }

    // Structure is intent

    @Test fun adjacentEqualSegmentsArePreservedAndFingerprintedDifferently() {
        val single = content(ratio = entered("10"))
        val split = split(single, ratio, day, 360).content()
        assertNotEquals(ProfileCodec.sha256(single), ProfileCodec.sha256(split))
        assertEquals(split, ProfileCodec.decode(ProfileCodec.encode(split)))
        // A structure-only change is a new version, not "unchanged".
        val repository = MemoryProfileRepository()
        val profiles = profiles(repository)
        profiles.saved(profiles.newProfile().with(single))
        val v2 = profiles.saved(profiles.edit(profiles.loaded()).with(split))
        assertEquals(2, v2.content.schedule(ratio).segments.size)
        assertEquals(ProfileSave.Failed(ProfileFailure.UNCHANGED), profiles.save(profiles.edit(profiles.loaded())))
    }

    @Test fun randomOperationSequencesNeverBreakCoverage() {
        val random = Random(20260928)
        var current = content()
        repeat(4_000) {
            val parameter = listOf(ratio, isf, target)[random.nextInt(3)]
            val segments = current.schedule(parameter).segments
            val index = random.nextInt(segments.size)
            val ref = SegmentRef.of(segments[index])
            val next = segments.getOrNull(index + 1)?.let { SegmentRef.of(it) } ?: SegmentRef(1440, 1441)
            val operation = when (random.nextInt(4)) {
                0 -> SegmentOperation.Split(parameter, ref, random.nextInt(-2, 1443))
                1 -> SegmentOperation.MergeWithNext(parameter, ref, next)
                2 -> SegmentOperation.MoveBoundary(parameter, ref, next, random.nextInt(-2, 1443))
                else -> SegmentOperation.SetValue(parameter, ref,
                    if (random.nextBoolean()) ProfileValue.NotConfigured else entered("${random.nextInt(0, 4)}"))
            }
            when (val edit = current.edited(operation)) {
                is ProfileEdit.Changed -> current = edit.content
                is ProfileEdit.Rejected -> Unit
            }
            assertInvariants(current)
        }
    }

    // Unit change (T3)

    @Test fun unitChangeKeepsEveryBoundaryClearsValuesAndMergesNothing() {
        val original = content()
            .withSchedule(isf, segment(0, 360, entered("40")), segment(360, 720, entered("45")), segment(720, 1440, entered("50")))
            .withSchedule(target, segment(0, 480, entered("110")), segment(480, 1440))
            .withSchedule(ratio, segment(0, 600, entered("10")), segment(600, 1440, entered("12")))
        val changed = original.withGlucoseUnit(mmol)
        assertEquals(listOf(segment(0, 360), segment(360, 720), segment(720, 1440)), changed.schedule(isf).segments)
        assertEquals(listOf(segment(0, 480), segment(480, 1440)), changed.schedule(target).segments)
        assertEquals(original.schedule(ratio), changed.schedule(ratio))
        assertFalse(changed.hasGlucoseDependentValues)
        // Back to the initial unit: boundaries stay, values do not come back.
        val back = changed.withGlucoseUnit(mgdl)
        assertEquals(changed.schedule(isf), back.schedule(isf))
        assertFalse(back.hasGlucoseDependentValues)
    }

    @Test fun unitChangeLocksDependentStructureAndIsSavedWithItsBoundaries() {
        val repository = MemoryProfileRepository()
        val profiles = profiles(repository)
        val first = content().withSchedule(isf, segment(0, 360, entered("40")), segment(360, 720, entered("45")),
            segment(720, 1440, entered("50")))
        profiles.saved(profiles.newProfile().with(first))
        // An unsaved split made before the unit change is kept too.
        val editing = (profiles.edit(profiles.loaded()).edited(SegmentOperation.Split(isf, SegmentRef(720, 1440), 1080))
            as ProfileEditorEdit.Changed).editor
        val editor = editing.with(editing.content.withGlucoseUnit(mmol))
        assertTrue(editor.scheduleLocked(isf))
        assertTrue(editor.scheduleLocked(target))
        assertFalse(editor.scheduleLocked(ratio))
        val merge = SegmentOperation.MergeWithNext(isf, SegmentRef(0, 360), SegmentRef(360, 720))
        assertEquals(ProfileEditorEdit.Rejected(ProfileFailure.SCHEDULE_LOCKED), editor.edited(merge))
        assertEquals(ProfileEditorEdit.Rejected(ProfileFailure.SCHEDULE_LOCKED),
            editor.edited(SegmentOperation.Split(isf, SegmentRef(0, 360), 100)))
        assertEquals(ProfileEditorEdit.Rejected(ProfileFailure.SCHEDULE_LOCKED),
            editor.edited(SegmentOperation.MoveBoundary(isf, SegmentRef(0, 360), SegmentRef(360, 720), 400)))
        assertEquals(ProfileEditorEdit.Rejected(ProfileFailure.UNIT_CHANGE_WITH_VALUES),
            editor.edited(SegmentOperation.SetValue(isf, SegmentRef(0, 360), entered("2.2"))))
        assertIs<ProfileEditorEdit.Changed>(editor.edited(SegmentOperation.Split(ratio, day, 600)))
        val v2 = profiles.saved(editor)
        assertEquals(listOf(segment(0, 360), segment(360, 720), segment(720, 1080), segment(1080, 1440)),
            v2.content.schedule(isf).segments)
        // In the next version the same boundaries accept values in the new unit and can be merged explicitly.
        val next = profiles.edit(profiles.loaded())
        assertFalse(next.scheduleLocked(isf))
        assertIs<ProfileEditorEdit.Changed>(next.edited(merge))
        assertIs<ProfileEditorEdit.Changed>(next.edited(SegmentOperation.SetValue(isf, SegmentRef(0, 360), entered("2.2"))))
    }

    // Restoration

    @Test fun splitThenMergeReturnsToAnExactRestoration() {
        val repository = MemoryProfileRepository()
        val profiles = profiles(repository)
        val multi = content().withSchedule(ratio, segment(0, 360, entered("10")), segment(360, 720, entered("10")),
            segment(720, 1440, ProfileValue.NotConfigured)).withSchedule(target, segment(0, 600, entered("0")), segment(600, 1440, entered("110")))
        val v1 = profiles.saved(profiles.newProfile().with(multi))
        profiles.saved(profiles.edit(profiles.loaded()).with(content()))
        val ready = profiles.restore(profiles.loaded(), 1) as ProfileRestore.Ready
        assertEquals(multi, ready.editor.content)
        val split = (ready.editor.edited(SegmentOperation.Split(ratio, SegmentRef(720, 1440), 1000)) as ProfileEditorEdit.Changed).editor
        assertEquals(ProfileOrigin.MANUAL, split.origin)
        assertEquals(1L, split.restoredFrom)
        val back = (split.edited(SegmentOperation.MergeWithNext(ratio, SegmentRef(720, 1000), SegmentRef(1000, 1440)))
            as ProfileEditorEdit.Changed).editor
        assertEquals(ProfileOrigin.RESTORED, back.origin)
        val v3 = profiles.saved(back)
        assertEquals(ProfileOrigin.RESTORED, v3.origin)
        assertEquals(v1.contentSha256, v3.contentSha256)
        assertEquals(3, v3.content.schedule(ratio).segments.size)
    }

    // Modified indicator (T5)

    @Test fun modifiedIndicatorIsDerivedAndNeverPartOfContent() {
        val start = content(ratio = entered("10"))
        val editor = ProfileEditor(1, start, start, null)
        assertFalse(editor.isModified(ratio, segment(0, 1440, entered("10"))))
        val split = (editor.edited(SegmentOperation.Split(ratio, day, 360)) as ProfileEditorEdit.Changed).editor
        split.content.schedule(ratio).segments.forEach { assertTrue(split.isModified(ratio, it)) }
        assertFalse(split.isModified(isf, split.content.schedule(isf).segments.single()))
        val merged = (split.edited(SegmentOperation.MergeWithNext(ratio, SegmentRef(0, 360), SegmentRef(360, 1440)))
            as ProfileEditorEdit.Changed).editor
        assertFalse(merged.isModified(ratio, merged.content.schedule(ratio).segments.single()))
        assertEquals(start, merged.content)
        assertEquals(ProfileCodec.sha256(start), ProfileCodec.sha256(merged.content))
        // Zero against not configured counts as modified.
        val zero = (editor.edited(SegmentOperation.SetValue(ratio, day, entered("0"))) as ProfileEditorEdit.Changed).editor
        assertTrue(zero.isModified(ratio, zero.content.schedule(ratio).segments.single()))
        // After a unit change, dependent segments that had a value are modified.
        val unit = editor.with(start.withGlucoseUnit(mmol))
        assertTrue(unit.isModified(isf, unit.content.schedule(isf).segments.single()))
    }

    // Times

    @Test fun timesAre24HourWithOneMinuteResolution() {
        assertEquals("00:00", ProfileTimes.format(0))
        assertEquals("06:07", ProfileTimes.format(367))
        assertEquals("23:59", ProfileTimes.format(1439))
        assertEquals("24:00", ProfileTimes.format(1440))
        assertFailsWith<IllegalArgumentException> { ProfileTimes.format(1441) }
        mapOf("6:00" to 360, "06:00" to 360, " 23:59 " to 1439, "0:01" to 1, "00:00" to 0, "12:07" to 727).forEach { (text, minute) ->
            assertEquals(TimeInput.Valid(minute), ProfileTimes.parse(text), text)
        }
        assertEquals(TimeInput.Blank, ProfileTimes.parse("  "))
        listOf("24:00", "600", "6", "6.00", "6h", "06:00 h", "06:00:00", "6:0", "6:60", "25:00", "6 AM", "6:00pm",
            "-1:00", "06,00", "٠٦:٠٠", "06：00").forEach {
            assertEquals(TimeInput.Invalid(ProfileFailure.INVALID_TIME), ProfileTimes.parse(it), it)
        }
    }

    @Test fun midnightIsNeverAValidBoundary() {
        // 00:00 parses but is not interior to any segment; 24:00 does not even parse.
        val base = content()
        assertEquals(ProfileEdit.Rejected(ProfileFailure.SPLIT_OUT_OF_RANGE), split(base, ratio, day, 0))
        assertEquals(TimeInput.Invalid(ProfileFailure.INVALID_TIME), ProfileTimes.parse("24:00"))
        // 22:00–06:00 is two segments.
        val night = content().withSchedule(ratio, segment(0, 360, entered("8")), segment(360, 1320, entered("10")),
            segment(1320, 1440, entered("8")))
        assertInvariants(night)
    }

    @Test fun everyEditingResultStillBlocksCalculation() {
        val profiles = profiles(MemoryProfileRepository())
        val editor = (profiles.newProfile().edited(SegmentOperation.Split(ratio, day, 720)) as ProfileEditorEdit.Changed).editor
        val saved = profiles.saved(editor)
        assertFalse(saved.allowsCalculation)
        assertFalse(saved.allowsTreatment)
        assertFalse(profiles.read().allowsCalculation)
    }
}
