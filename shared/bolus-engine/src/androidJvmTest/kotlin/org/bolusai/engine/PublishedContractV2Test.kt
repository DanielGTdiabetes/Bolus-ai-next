package org.bolusai.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR 0015, section 8: the admissibility table of the test is compared with the one of the published contract
 * (`docs/contracts/unavailable-input-v2.md`), so code, test and document cannot drift apart silently.
 */
class PublishedContractV2Test {
    private val contract: String by lazy {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "docs/contracts/unavailable-input-v2.md") }
            .firstOrNull { it.isFile }
        requireNotNull(file) { "published contract unavailable-input-v2.md not found" }.readText()
    }

    private fun section(name: String): List<String> {
        val start = "<!-- $name:start -->"
        val end = "<!-- $name:end -->"
        assertTrue(contract.contains(start) && contract.contains(end), "missing markers for $name")
        return contract.substringAfter(start).substringBefore(end).lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    @Test
    fun publishedAdmissibilityTableMatchesTheContractAndTheTest(): Unit {
        val rows = section("admissibility").filter { it.startsWith("| `") }
        val inputs = listOf("glucose", "profile", "iob", "meal")
        val published = rows.flatMap { row ->
            val cells = row.trim('|').split('|').map { it.trim() }
            val reason = cells[0].trim('`')
            inputs.mapIndexedNotNull { index, input ->
                when (cells[index + 1]) {
                    "admitido" -> "input.$input.$reason"
                    "no admitido" -> null
                    else -> error("unexpected cell '${cells[index + 1]}' for $reason")
                }
            }
        }.sorted()
        assertEquals(14, rows.size)
        assertEquals(AdmissibilityTable.expectedAdmitted, published)
        val fromCode = InputKind.entries.flatMap { input ->
            UnavailabilityReasonV2.entries.filter { it.isAdmittedFor(input) }.map { "input.${input.code}.${it.code}" }
        }.sorted()
        assertEquals(fromCode, published)
        assertEquals(UnavailabilityReasonV2.entries.map { it.code }.sorted(), rows.map { it.trim('|').split('|')[0].trim().trim('`') }.sorted())
    }

    @Test
    fun publishedRejectionIdentifiersMatchTheCode(): Unit {
        val published = section("errors").filter { it.startsWith("| `") }
            .map { it.trim('|').split('|')[0].trim().trim('`') }
        assertEquals(
            listOf(
                InputUnavailabilityErrors.REASON_NOT_ADMITTED,
                InputUnavailabilityErrors.DETAIL_MALFORMED,
                InputUnavailabilityErrors.DETAIL_FOREIGN_NAMESPACE,
                InputUnavailabilityErrors.TOO_MANY_DETAILS,
                InputUnavailabilityErrors.REPORT_EMPTY,
            ),
            published,
        )
    }
}
