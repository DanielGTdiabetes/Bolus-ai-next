package org.bolusai.profile

import kotlin.test.*

/** ADR 0012, section 4.4: no stored value can be read under a different glucose unit. */
class ProfileUnitTest {
    private val repository = MemoryProfileRepository()
    private val profiles = profiles(repository)

    private fun firstVersion(): ProfileVersion = profiles.saved(profiles.newProfile().with(content()))

    @Test fun dependentValuesRequireADeclaredUnit() {
        assertFailsWith<IllegalArgumentException> { content(unit = Setting.NotConfigured) }
        assertEquals(ProfileFailure.UNIT_REQUIRED, ProfileContent.problem(1, Setting.NotConfigured,
            content().schedules))
        val noUnit = ProfileContent.empty()
        assertEquals(ProfileEdit.Rejected(ProfileFailure.UNIT_REQUIRED),
            noUnit.withAllDayValue(ProfileParameter.GLUCOSE_TARGET, entered("110")))
        // The carb ratio does not depend on the glucose unit.
        assertIs<ProfileEdit.Changed>(noUnit.withAllDayValue(ProfileParameter.CARB_RATIO, entered("10")))
    }

    @Test fun changingUnitClearsDependentValuesAndNeverConverts() {
        val original = content()
        val changed = original.withGlucoseUnit(mmol)
        assertEquals(mmol, changed.glucoseUnit)
        assertEquals(original.schedule(ProfileParameter.CARB_RATIO), changed.schedule(ProfileParameter.CARB_RATIO))
        listOf(ProfileParameter.INSULIN_SENSITIVITY, ProfileParameter.GLUCOSE_TARGET).forEach {
            assertEquals(ParameterSchedule.allDay(it), changed.schedule(it))
        }
        // Switching back does not bring the old numbers back under any unit.
        val back = changed.withGlucoseUnit(mgdl)
        assertFalse(back.hasGlucoseDependentValues)
        assertEquals(original, original.withGlucoseUnit(mgdl))
        assertFalse(original.withGlucoseUnit(Setting.NotConfigured).hasGlucoseDependentValues)
    }

    @Test fun unitChangeIsItsOwnVersionAndOlderVersionsKeepTheirUnit() {
        val v1 = firstVersion()
        val editor = profiles.edit(profiles.loaded()).let { it.with(it.content.withGlucoseUnit(mmol)) }
        assertTrue(editor.glucoseDependentValuesLocked)
        // While locked, dependent values cannot be entered in the same version.
        val typed = (editor.content.withAllDayValue(ProfileParameter.GLUCOSE_TARGET, entered("6.1")) as ProfileEdit.Changed).content
        assertEquals(ProfileSave.Failed(ProfileFailure.UNIT_CHANGE_WITH_VALUES), profiles.save(editor.with(typed)))
        assertEquals(listOf(v1), repository.versions)
        val v2 = profiles.saved(editor)
        assertEquals(mmol, v2.content.glucoseUnit)
        assertFalse(v2.content.hasGlucoseDependentValues)
        // The next version starts from mmol/L and accepts values in that unit.
        val next = profiles.edit(profiles.loaded())
        assertFalse(next.glucoseDependentValuesLocked)
        val v3 = profiles.saved(next.with((next.content.withAllDayValue(ProfileParameter.GLUCOSE_TARGET,
            entered("6.1")) as ProfileEdit.Changed).content))
        assertEquals(mmol, v3.content.glucoseUnit)
        val history = profiles.loaded()
        assertEquals(v1, history.version(1))
        assertEquals(mgdl, history.version(1)!!.content.glucoseUnit)
        assertEquals(entered("110"), history.version(1)!!.content.schedule(ProfileParameter.GLUCOSE_TARGET).segments.single().value)
    }

    @Test fun storagePolicyRejectsHandBuiltUnitChangeWithValuesEvenWithoutEditor() {
        val v1 = firstVersion()
        // Same numbers, other unit: exactly the silent reinterpretation that must be impossible.
        val reinterpreted = content(unit = mmol)
        val result = repository.save(ProfileWrite(1, reinterpreted, ProfileOrigin.MANUAL, null), 5, "w")
        assertEquals(ProfileSave.Failed(ProfileFailure.UNIT_CHANGE_WITH_VALUES), result)
        val otherNumbers = content(unit = mmol, sensitivity = entered("2.2"), target = entered("6.1"))
        assertEquals(ProfileSave.Failed(ProfileFailure.UNIT_CHANGE_WITH_VALUES),
            repository.save(ProfileWrite(1, otherNumbers, ProfileOrigin.MANUAL, null), 5, "w"))
        assertEquals(listOf(v1), repository.versions)
    }

    @Test fun declaringAUnitForTheFirstTimeReinterpretsNothing() {
        val v1 = profiles.saved(profiles.newProfile().let {
            it.with((it.content.withAllDayValue(ProfileParameter.CARB_RATIO, entered("10")) as ProfileEdit.Changed).content)
        })
        assertEquals(Setting.NotConfigured, v1.content.glucoseUnit)
        val editor = profiles.edit(profiles.loaded()).let { it.with(it.content.withGlucoseUnit(mmol)) }
        assertFalse(editor.glucoseDependentValuesLocked)
        val v2 = profiles.saved(editor.with((editor.content.withAllDayValue(ProfileParameter.GLUCOSE_TARGET,
            entered("6.1")) as ProfileEdit.Changed).content))
        assertEquals(2, v2.version)
    }

    @Test fun restoringAVersionInAnotherUnitKeepsItsOriginalUnit() {
        val v1 = firstVersion()
        profiles.saved(profiles.edit(profiles.loaded()).let { it.with(it.content.withGlucoseUnit(mmol)) })
        val ready = profiles.restore(profiles.loaded(), 1) as ProfileRestore.Ready
        assertEquals(mgdl, ready.editor.content.glucoseUnit)
        assertFalse(ready.editor.glucoseDependentValuesLocked)
        val v3 = profiles.saved(ready.editor)
        assertEquals(ProfileOrigin.RESTORED, v3.origin)
        assertEquals(v1.content, v3.content)
        assertEquals(v1.contentSha256, v3.contentSha256)
    }

    @Test fun restoreThenChangeUnitWithValuesIsRejected() {
        firstVersion()
        profiles.saved(profiles.edit(profiles.loaded()).let { it.with(it.content.withGlucoseUnit(mmol)) })
        val ready = profiles.restore(profiles.loaded(), 1) as ProfileRestore.Ready
        // Editing the restored mg/dL content while keeping its unit is fine: values stay paired with mg/dL.
        val edited = ready.editor.with(content(ratio = entered("11")))
        assertEquals(ProfileOrigin.MANUAL, edited.origin)
        // Handing the mg/dL numbers to mmol/L is not.
        val forged = ProfileWrite(2, content(unit = mmol), ProfileOrigin.MANUAL, 1)
        assertEquals(ProfileSave.Failed(ProfileFailure.UNIT_CHANGE_WITH_VALUES), repository.save(forged, 9, "w"))
        assertEquals(3, profiles.saved(edited).version)
    }

    @Test fun historyWithATamperedUnitTransitionFailsClosed() {
        val v1 = firstVersion()
        val forged = content(unit = mmol)
        repository.versions.add(ProfileVersion(2, forged, ProfileCodec.sha256(forged), ProfileOrigin.MANUAL, null, 3, "w"))
        assertEquals(ProfileHistory.Failed(ProfileFailure.INVALID_RECORD), profiles.read())
        assertEquals(v1, repository.versions.first())
    }
}
