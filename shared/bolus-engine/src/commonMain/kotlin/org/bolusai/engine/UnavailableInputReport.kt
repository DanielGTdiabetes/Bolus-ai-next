package org.bolusai.engine

/**
 * A non-empty snapshot of reported causes, without clinical values or precedence.
 * Ordering by stable code is technical, not a ranking of clinical importance.
 * An empty collection is a contract error, never evidence that inputs are valid.
 * This report cannot authorize calculation or treatment.
 */
public class UnavailableInputReport @Throws(IllegalArgumentException::class) public constructor(
    reports: List<UnavailableInput>,
) {
    private val snapshot: List<UnavailableInput> = reports.distinct().sortedBy { it.code }

    init {
        require(snapshot.isNotEmpty()) { "unavailable_input_report.empty" }
    }

    public val contractVersion: Int get() = 1

    /** Returns a copy so callers cannot mutate the captured causes. */
    public val entries: List<UnavailableInput> get() = snapshot.toList()
}
