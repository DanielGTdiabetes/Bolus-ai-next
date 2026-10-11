package org.bolusai.dexcomg7

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Synthetic payloads only: no real readings, transmitter identifiers or device data (ADR 0018). */
class DexcomG7PayloadParserTest {
    private val sender = "org.example.synthetic.sender"

    private fun reading(
        value: PayloadValue = PayloadValue.Int32(123),
        timestamp: PayloadValue = PayloadValue.Int64(1_000),
        trend: PayloadValue = PayloadValue.Text("SYNTHETIC_TREND"),
        transmitter: PayloadValue = PayloadValue.Text("SYNTH-TX"),
        egvTxTime: PayloadValue = PayloadValue.Int64(77),
        extra: Map<String, PayloadValue> = emptyMap(),
    ): PayloadValue.Group = PayloadValue.Group(
        mapOf(
            "glucoseValue" to value,
            "timestamp" to timestamp,
            "trendArrow" to trend,
            "transmitterId" to transmitter,
            "egvTxTime" to egvTxTime,
        ) + extra,
    )

    private fun payload(
        readings: Map<String, PayloadValue> = mapOf("0" to reading()),
        action: String? = ObservedDexcomG7Contract.ACTION,
        sensorType: PayloadValue? = PayloadValue.Text("G7"),
        packageName: PayloadValue? = PayloadValue.Text(sender),
        batch: PayloadValue? = null,
        extra: Map<String, PayloadValue> = emptyMap(),
    ): DexcomG7Payload {
        val extras = LinkedHashMap<String, PayloadValue>()
        sensorType?.let { extras["sensorType"] = it }
        packageName?.let { extras["packageName"] = it }
        extras["glucoseValues"] = batch ?: PayloadValue.Group(readings)
        extras += extra
        return DexcomG7Payload(action, extras)
    }

    private fun parsed(payload: DexcomG7Payload): ObservedDexcomG7Message =
        assertIs<DexcomG7ParseResult.Parsed>(DexcomG7PayloadParser.parse(payload, sender)).message

    private fun rejected(payload: DexcomG7Payload): DexcomG7PayloadRejection =
        assertIs<DexcomG7ParseResult.Rejected>(DexcomG7PayloadParser.parse(payload, sender)).rejection

    @Test fun parsesTheObservedShapeWithoutInterpretingAnyValue() {
        val message = parsed(payload())

        assertEquals(ObservedDexcomG7Contract.ID, message.contract)
        assertEquals(ProducerUnitClaim.MG_DL_STATED_BY_PRODUCER, message.unitClaim)
        assertEquals(sender, message.authenticatedPackage)
        assertEquals(
            listOf(ObservedDexcomG7Reading(0, 123, 1_000, "SYNTHETIC_TREND", "SYNTH-TX", 77)),
            message.readings,
        )
        assertEquals(0, message.collapsedDuplicateCount)
        assertEquals(emptyList(), message.unknownTopLevelFields)
        assertEquals(emptyList(), message.unknownReadingFields)
        assertEquals(0, message.unreportableUnknownFieldCount)
    }

    @Test fun everyReadingIsNotApprovedForUseAndStatusHasNoOtherValue() {
        val message = parsed(payload(mapOf("0" to reading(), "1" to reading(timestamp = PayloadValue.Int64(2_000)))))

        assertTrue(message.readings.all { it.status == ObservedReadingStatus.NOT_APPROVED_FOR_USE })
        assertEquals(listOf(ObservedReadingStatus.NOT_APPROVED_FOR_USE), ObservedReadingStatus.entries.toList())
        assertEquals("dexcom_g7.reading.not_approved_for_use", ObservedReadingStatus.NOT_APPROVED_FOR_USE.code)
    }

    @Test fun keepsAnyIntegerValueWithoutAClinicalRange() {
        // Decision D4: structure only. The producer's 1..400 filter is observed behaviour, not a Next rule.
        for (value in listOf(Int.MIN_VALUE, -1, 0, 1, 400, 401, Int.MAX_VALUE)) {
            val message = parsed(payload(mapOf("0" to reading(value = PayloadValue.Int32(value)))))
            assertEquals(value, message.readings.single().producerGlucoseValue)
        }
    }

    @Test fun keepsRawTimestampsWithoutClockOrUnitConversion() {
        val message = parsed(
            payload(mapOf("0" to reading(timestamp = PayloadValue.Int64(-5), egvTxTime = PayloadValue.Int64(Long.MAX_VALUE)))),
        )
        assertEquals(-5, message.readings.single().producerTimestamp)
        assertEquals(Long.MAX_VALUE, message.readings.single().egvTxTime)
    }

    @Test fun keepsProducerIndexOrderEvenWhenTimestampsAreNotOrdered() {
        val message = parsed(
            payload(
                linkedMapOf(
                    "2" to reading(timestamp = PayloadValue.Int64(10)),
                    "0" to reading(timestamp = PayloadValue.Int64(30)),
                    "1" to reading(timestamp = PayloadValue.Int64(20)),
                ),
            ),
        )
        assertEquals(listOf(0, 1, 2), message.readings.map { it.index })
        assertEquals(listOf(30L, 20L, 10L), message.readings.map { it.producerTimestamp })
    }

    @Test fun rejectsAnotherAction() {
        assertEquals(DexcomG7RejectionCode.WRONG_ACTION, rejected(payload(action = "org.example.OTHER")).code)
        assertEquals(DexcomG7RejectionCode.WRONG_ACTION, rejected(payload(action = null)).code)
    }

    @Test fun rejectsOtherSensorsAndMalformedSensorType() {
        assertEquals(
            DexcomG7PayloadRejection(DexcomG7RejectionCode.UNSUPPORTED_SENSOR, "sensorType"),
            rejected(payload(sensorType = PayloadValue.Text("G6"))),
        )
        assertEquals(
            DexcomG7PayloadRejection(DexcomG7RejectionCode.UNSUPPORTED_SENSOR, "sensorType"),
            rejected(payload(sensorType = PayloadValue.Text("g7"))),
        )
        assertEquals(DexcomG7RejectionCode.MISSING_FIELD, rejected(payload(sensorType = null)).code)
        assertEquals(DexcomG7RejectionCode.NULL_FIELD, rejected(payload(sensorType = PayloadValue.Null)).code)
        assertEquals(DexcomG7RejectionCode.WRONG_TYPE, rejected(payload(sensorType = PayloadValue.Int32(7))).code)
        assertEquals(DexcomG7RejectionCode.BLANK_TEXT, rejected(payload(sensorType = PayloadValue.Text(" "))).code)
    }

    @Test fun thePackageExtraIsAClaimThatMustMatchTheAuthenticatedSender() {
        assertEquals(
            DexcomG7PayloadRejection(DexcomG7RejectionCode.PACKAGE_CLAIM_MISMATCH, "packageName"),
            rejected(payload(packageName = PayloadValue.Text("org.example.other"))),
        )
        assertEquals(
            DexcomG7PayloadRejection(DexcomG7RejectionCode.MISSING_FIELD, "packageName"),
            rejected(payload(packageName = null)),
        )
        // A matching claim never replaces authentication: the parser requires a proven package first.
        assertFailsWith<IllegalArgumentException> { DexcomG7PayloadParser.parse(payload(), " ") }
    }

    @Test fun rejectsMissingEmptyOrMistypedBatch() {
        assertEquals(
            DexcomG7PayloadRejection(DexcomG7RejectionCode.EMPTY_BATCH, "glucoseValues"),
            rejected(payload(emptyMap())),
        )
        assertEquals(
            DexcomG7PayloadRejection(DexcomG7RejectionCode.WRONG_TYPE, "glucoseValues"),
            rejected(payload(batch = PayloadValue.Text("0"))),
        )
        assertEquals(
            DexcomG7PayloadRejection(DexcomG7RejectionCode.NULL_FIELD, "glucoseValues"),
            rejected(payload(batch = PayloadValue.Null)),
        )
    }

    @Test fun rejectsNonCanonicalOrNonContiguousIndices() {
        for (key in listOf("01", "-1", "+1", " 0", "a", "", "1234567890")) {
            assertEquals(
                DexcomG7PayloadRejection(DexcomG7RejectionCode.NON_CANONICAL_INDEX, "glucoseValues"),
                rejected(payload(mapOf(key to reading()))),
                "key '$key'",
            )
        }
        assertEquals(
            DexcomG7RejectionCode.NON_CONTIGUOUS_INDEX,
            rejected(payload(mapOf("1" to reading()))).code,
        )
        assertEquals(
            DexcomG7RejectionCode.NON_CONTIGUOUS_INDEX,
            rejected(payload(mapOf("0" to reading(), "2" to reading(timestamp = PayloadValue.Int64(2))))).code,
        )
    }

    @Test fun oneMalformedReadingRejectsTheWholeMessage() {
        // Decision D1: no partial batches.
        val result = DexcomG7PayloadParser.parse(
            payload(mapOf("0" to reading(), "1" to reading(value = PayloadValue.Int64(123)))),
            sender,
        )
        assertEquals(
            DexcomG7ParseResult.Rejected(DexcomG7PayloadRejection(DexcomG7RejectionCode.WRONG_TYPE, "glucoseValue", 1)),
            result,
        )
    }

    @Test fun everyReadingFieldIsRequiredWithItsExactType() {
        val names = listOf("glucoseValue", "timestamp", "trendArrow", "transmitterId", "egvTxTime")
        for (name in names) {
            val withoutField = PayloadValue.Group(reading().entries - name)
            assertEquals(
                DexcomG7PayloadRejection(DexcomG7RejectionCode.MISSING_FIELD, name, 0),
                rejected(payload(mapOf("0" to withoutField))),
            )
            val nullField = PayloadValue.Group(reading().entries + (name to PayloadValue.Null))
            assertEquals(
                DexcomG7PayloadRejection(DexcomG7RejectionCode.NULL_FIELD, name, 0),
                rejected(payload(mapOf("0" to nullField))),
            )
        }
        val wrongTypes = mapOf(
            "glucoseValue" to listOf(PayloadValue.Int64(123), PayloadValue.Text("123"), PayloadValue.Unsupported("java.lang.Double")),
            "timestamp" to listOf(PayloadValue.Int32(1), PayloadValue.Text("1")),
            "trendArrow" to listOf(PayloadValue.Int32(1), PayloadValue.Group(emptyMap())),
            "transmitterId" to listOf(PayloadValue.Int64(1)),
            "egvTxTime" to listOf(PayloadValue.Int32(1), PayloadValue.Unsupported("java.lang.String[]")),
        )
        for ((name, values) in wrongTypes) {
            for (value in values) {
                val mistyped = PayloadValue.Group(reading().entries + (name to value))
                assertEquals(
                    DexcomG7PayloadRejection(DexcomG7RejectionCode.WRONG_TYPE, name, 0),
                    rejected(payload(mapOf("0" to mistyped))),
                )
            }
        }
        for (name in listOf("trendArrow", "transmitterId")) {
            val blank = PayloadValue.Group(reading().entries + (name to PayloadValue.Text("")))
            assertEquals(
                DexcomG7PayloadRejection(DexcomG7RejectionCode.BLANK_TEXT, name, 0),
                rejected(payload(mapOf("0" to blank))),
            )
        }
    }

    @Test fun aReadingThatIsNotAGroupRejectsWithItsIndex() {
        assertEquals(
            DexcomG7PayloadRejection(DexcomG7RejectionCode.WRONG_TYPE, "glucoseValues", 1),
            rejected(payload(mapOf("0" to reading(), "1" to PayloadValue.Int32(5)))),
        )
        assertEquals(
            DexcomG7PayloadRejection(DexcomG7RejectionCode.NULL_FIELD, "glucoseValues", 0),
            rejected(payload(mapOf("0" to PayloadValue.Null))),
        )
    }

    @Test fun identicalDuplicatesCollapseOntoTheFirstIndex() {
        // Decision D3.
        val message = parsed(payload(mapOf("0" to reading(), "1" to reading(), "2" to reading())))
        assertEquals(listOf(0), message.readings.map { it.index })
        assertEquals(2, message.collapsedDuplicateCount)
    }

    @Test fun sameTimestampWithDifferentContentIsAConflict() {
        val variants = listOf(
            reading(value = PayloadValue.Int32(124)),
            reading(trend = PayloadValue.Text("OTHER_TREND")),
            reading(transmitter = PayloadValue.Text("SYNTH-TX-2")),
            reading(egvTxTime = PayloadValue.Int64(78)),
        )
        for (variant in variants) {
            assertEquals(
                DexcomG7PayloadRejection(DexcomG7RejectionCode.CONFLICTING_DUPLICATE, "timestamp", 1),
                rejected(payload(mapOf("0" to reading(), "1" to variant))),
            )
        }
    }

    @Test fun unknownFieldsAreListedByNameAndNeverBlock() {
        // Decision D2.
        val message = parsed(
            payload(
                readings = mapOf(
                    "0" to reading(extra = mapOf("newField" to PayloadValue.Text("secret-value"), "b.c" to PayloadValue.Null)),
                    "1" to reading(timestamp = PayloadValue.Int64(2), extra = mapOf("newField" to PayloadValue.Int32(1))),
                ),
                extra = mapOf("zeta" to PayloadValue.Int64(1), "alpha_1" to PayloadValue.Unsupported("x")),
            ),
        )
        assertEquals(listOf("alpha_1", "zeta"), message.unknownTopLevelFields)
        assertEquals(listOf("b.c", "newField"), message.unknownReadingFields)
        assertEquals(0, message.unreportableUnknownFieldCount)
        assertFalse(message.toString().contains("secret-value"))
    }

    @Test fun unknownNamesOutsideTheGrammarAreOnlyCountedOnce() {
        val odd = "name with spaces"
        val long = "x".repeat(65)
        val message = parsed(
            payload(
                readings = mapOf(
                    "0" to reading(extra = mapOf(odd to PayloadValue.Int32(1))),
                    "1" to reading(timestamp = PayloadValue.Int64(2), extra = mapOf(odd to PayloadValue.Int32(1))),
                ),
                extra = mapOf(long to PayloadValue.Int32(1), "" to PayloadValue.Int32(1)),
            ),
        )
        assertEquals(emptyList(), message.unknownTopLevelFields)
        assertEquals(emptyList(), message.unknownReadingFields)
        assertEquals(3, message.unreportableUnknownFieldCount)
        assertFalse(message.toString().contains(odd))
    }

    @Test fun diagnosticsNeverPrintTheTransmitterOrTextValues() {
        val message = parsed(payload(mapOf("0" to reading(transmitter = PayloadValue.Text("SYNTH-SECRET-TX")))))
        val printed = listOf(
            message.toString(),
            message.readings.single().toString(),
            payload(mapOf("0" to reading(transmitter = PayloadValue.Text("SYNTH-SECRET-TX")))).toString(),
            PayloadValue.Text("SYNTH-SECRET-TX").toString(),
            reading(transmitter = PayloadValue.Text("SYNTH-SECRET-TX")).toString(),
        )
        for (text in printed) assertFalse(text.contains("SYNTH-SECRET-TX"), text)
    }

    @Test fun parsingIsDeterministicAndDefensive() {
        val source = linkedMapOf<String, PayloadValue>("0" to reading())
        val input = payload(source)
        val first = DexcomG7PayloadParser.parse(input, sender)
        source["1"] = reading(timestamp = PayloadValue.Int64(9))
        assertEquals(first, DexcomG7PayloadParser.parse(input, sender))
        assertEquals(1, parsed(input).readings.size)
    }

    @Test fun rejectionCodesAreStable() {
        assertEquals(
            listOf(
                "dexcom_g7.payload.wrong_action",
                "dexcom_g7.payload.missing_field",
                "dexcom_g7.payload.null_field",
                "dexcom_g7.payload.wrong_type",
                "dexcom_g7.payload.blank_text",
                "dexcom_g7.payload.unsupported_sensor",
                "dexcom_g7.payload.package_claim_mismatch",
                "dexcom_g7.payload.empty_batch",
                "dexcom_g7.payload.non_canonical_index",
                "dexcom_g7.payload.non_contiguous_index",
                "dexcom_g7.payload.conflicting_duplicate",
            ),
            DexcomG7RejectionCode.entries.map { it.code },
        )
    }
}
