package org.bolusai.dexcomg7

/**
 * Field names and literals of docs/contracts/modified-dexcom-g7-broadcast-observed-v1.md. They are transport
 * identifiers, not clinical parameters.
 */
public object ObservedDexcomG7Contract {
    public const val ID: String = "modified-dexcom-g7-broadcast-observed-v1"
    public const val ACTION: String = "com.dexcom.cgm.EXTERNAL_BROADCAST"
    public const val SENSOR_TYPE: String = "sensorType"
    public const val SENSOR_TYPE_G7: String = "G7"
    public const val PACKAGE_NAME: String = "packageName"
    public const val GLUCOSE_VALUES: String = "glucoseValues"
    public const val GLUCOSE_VALUE: String = "glucoseValue"
    public const val TIMESTAMP: String = "timestamp"
    public const val TREND_ARROW: String = "trendArrow"
    public const val TRANSMITTER_ID: String = "transmitterId"
    public const val EGV_TX_TIME: String = "egvTxTime"

    internal val topLevelFields: Set<String> = setOf(SENSOR_TYPE, PACKAGE_NAME, GLUCOSE_VALUES)
    internal val readingFields: Set<String> = setOf(GLUCOSE_VALUE, TIMESTAMP, TREND_ARROW, TRANSMITTER_ID, EGV_TX_TIME)
}

/** Stable rejection identifiers. Codes never depend on ordinals or translated text. */
public enum class DexcomG7RejectionCode(public val code: String) {
    WRONG_ACTION("dexcom_g7.payload.wrong_action"),
    MISSING_FIELD("dexcom_g7.payload.missing_field"),
    NULL_FIELD("dexcom_g7.payload.null_field"),
    WRONG_TYPE("dexcom_g7.payload.wrong_type"),
    BLANK_TEXT("dexcom_g7.payload.blank_text"),
    UNSUPPORTED_SENSOR("dexcom_g7.payload.unsupported_sensor"),
    PACKAGE_CLAIM_MISMATCH("dexcom_g7.payload.package_claim_mismatch"),
    EMPTY_BATCH("dexcom_g7.payload.empty_batch"),
    NON_CANONICAL_INDEX("dexcom_g7.payload.non_canonical_index"),
    NON_CONTIGUOUS_INDEX("dexcom_g7.payload.non_contiguous_index"),
    CONFLICTING_DUPLICATE("dexcom_g7.payload.conflicting_duplicate"),
}

/**
 * Why a whole message was rejected (decision D1: no partial batches). [field] is always a contract field name and
 * [index] a canonical reading position, so neither carries producer-controlled text.
 */
public data class DexcomG7PayloadRejection(
    public val code: DexcomG7RejectionCode,
    public val field: String? = null,
    public val index: Int? = null,
)

/** The only status this contract can produce. A future approved status must be added and reviewed explicitly. */
public enum class ObservedReadingStatus(public val code: String) {
    NOT_APPROVED_FOR_USE("dexcom_g7.reading.not_approved_for_use"),
}

/**
 * One reading exactly as the producer stated it. Nothing here is a validated glucose value: the unit is the
 * producer's claim, the timestamps keep their raw producer semantics, the trend name is not interpreted and the
 * transmitter identifier is not trusted as identity.
 */
public data class ObservedDexcomG7Reading(
    public val index: Int,
    public val producerGlucoseValue: Int,
    public val producerTimestamp: Long,
    public val producerTrendArrow: String,
    public val transmitterId: String,
    public val egvTxTime: Long,
) {
    public val status: ObservedReadingStatus get() = ObservedReadingStatus.NOT_APPROVED_FOR_USE

    internal fun sameContent(other: ObservedDexcomG7Reading): Boolean =
        producerGlucoseValue == other.producerGlucoseValue &&
            producerTimestamp == other.producerTimestamp &&
            producerTrendArrow == other.producerTrendArrow &&
            transmitterId == other.transmitterId &&
            egvTxTime == other.egvTxTime

    /** The transmitter identifier identifies a device: it never reaches logs or diagnostics. */
    override fun toString(): String =
        "ObservedDexcomG7Reading(index=$index, status=${status.code}, transmitterId=<redacted>)"
}

/** The unit as asserted by the producer specification, kept as a claim and never inferred or converted. */
public enum class ProducerUnitClaim(public val code: String) {
    MG_DL_STATED_BY_PRODUCER("mg/dL"),
}

/**
 * A structurally valid message. Readings keep the producer's index order (order semantics are pending); identical
 * duplicates are collapsed onto the first index (decision D3). Unknown field names are listed, never their values
 * (decision D2). Names outside a conservative grammar are only counted, once per distinct name.
 */
public class ObservedDexcomG7Message(
    public val authenticatedPackage: String,
    readings: List<ObservedDexcomG7Reading>,
    public val collapsedDuplicateCount: Int,
    unknownTopLevelFields: List<String>,
    unknownReadingFields: List<String>,
    public val unreportableUnknownFieldCount: Int,
) {
    public val contract: String get() = ObservedDexcomG7Contract.ID
    public val unitClaim: ProducerUnitClaim get() = ProducerUnitClaim.MG_DL_STATED_BY_PRODUCER
    public val readings: List<ObservedDexcomG7Reading> = readings.toList()
    public val unknownTopLevelFields: List<String> = unknownTopLevelFields.toList()
    public val unknownReadingFields: List<String> = unknownReadingFields.toList()

    override fun equals(other: Any?): Boolean =
        other is ObservedDexcomG7Message &&
            other.authenticatedPackage == authenticatedPackage &&
            other.readings == readings &&
            other.collapsedDuplicateCount == collapsedDuplicateCount &&
            other.unknownTopLevelFields == unknownTopLevelFields &&
            other.unknownReadingFields == unknownReadingFields &&
            other.unreportableUnknownFieldCount == unreportableUnknownFieldCount

    override fun hashCode(): Int {
        var result = authenticatedPackage.hashCode()
        result = 31 * result + readings.hashCode()
        result = 31 * result + collapsedDuplicateCount
        result = 31 * result + unknownTopLevelFields.hashCode()
        result = 31 * result + unknownReadingFields.hashCode()
        return 31 * result + unreportableUnknownFieldCount
    }

    override fun toString(): String =
        "ObservedDexcomG7Message(contract=$contract, readings=${readings.size}, " +
            "collapsedDuplicates=$collapsedDuplicateCount, unknownTopLevel=$unknownTopLevelFields, " +
            "unknownReading=$unknownReadingFields, unreportableUnknown=$unreportableUnknownFieldCount)"
}

public sealed interface DexcomG7ParseResult {
    public data class Parsed(public val message: ObservedDexcomG7Message) : DexcomG7ParseResult

    public data class Rejected(public val rejection: DexcomG7PayloadRejection) : DexcomG7ParseResult
}

/**
 * Pure structural parser of the observed transport v1 (ADR 0018). Deterministic, without clock, storage, network
 * or platform types. It applies no clinical range, freshness, unit conversion, trend or identity rule and its
 * output cannot feed a calculation.
 */
public object DexcomG7PayloadParser {
    private val canonicalIndex = Regex("0|[1-9][0-9]{0,8}")
    private val reportableName = Regex("[A-Za-z0-9_.]{1,64}")

    /**
     * [authenticatedPackage] is the package the sender authentication proved. The `packageName` extra is only a
     * claim and must equal it; it is never evidence of identity.
     */
    public fun parse(payload: DexcomG7Payload, authenticatedPackage: String): DexcomG7ParseResult {
        require(authenticatedPackage.isNotBlank()) { "The authenticated package must be known before parsing" }
        return try {
            DexcomG7ParseResult.Parsed(parseMessage(payload, authenticatedPackage))
        } catch (rejected: PayloadRejected) {
            DexcomG7ParseResult.Rejected(rejected.rejection)
        }
    }

    private class PayloadRejected(val rejection: DexcomG7PayloadRejection) : Exception(rejection.code.code)

    private fun reject(code: DexcomG7RejectionCode, field: String? = null, index: Int? = null): Nothing =
        throw PayloadRejected(DexcomG7PayloadRejection(code, field, index))

    private fun parseMessage(payload: DexcomG7Payload, authenticatedPackage: String): ObservedDexcomG7Message {
        if (payload.action != ObservedDexcomG7Contract.ACTION) reject(DexcomG7RejectionCode.WRONG_ACTION)
        val extras = payload.extras

        val sensorType = text(extras, ObservedDexcomG7Contract.SENSOR_TYPE, null)
        if (sensorType != ObservedDexcomG7Contract.SENSOR_TYPE_G7) {
            reject(DexcomG7RejectionCode.UNSUPPORTED_SENSOR, ObservedDexcomG7Contract.SENSOR_TYPE)
        }
        val packageClaim = text(extras, ObservedDexcomG7Contract.PACKAGE_NAME, null)
        if (packageClaim != authenticatedPackage) {
            reject(DexcomG7RejectionCode.PACKAGE_CLAIM_MISMATCH, ObservedDexcomG7Contract.PACKAGE_NAME)
        }
        val batch = group(extras, ObservedDexcomG7Contract.GLUCOSE_VALUES, null)
        if (batch.isEmpty()) reject(DexcomG7RejectionCode.EMPTY_BATCH, ObservedDexcomG7Contract.GLUCOSE_VALUES)

        val positions = batch.keys.map { key ->
            if (!canonicalIndex.matches(key)) {
                reject(DexcomG7RejectionCode.NON_CANONICAL_INDEX, ObservedDexcomG7Contract.GLUCOSE_VALUES)
            }
            key.toInt()
        }.sorted()
        positions.forEachIndexed { expected, actual ->
            if (expected != actual) {
                reject(DexcomG7RejectionCode.NON_CONTIGUOUS_INDEX, ObservedDexcomG7Contract.GLUCOSE_VALUES)
            }
        }

        val unknownNames = UnknownNames(reportableName)
        unknownNames.collect(extras.keys - ObservedDexcomG7Contract.topLevelFields, topLevel = true)

        val kept = ArrayList<ObservedDexcomG7Reading>(positions.size)
        var collapsed = 0
        for (position in positions) {
            val entry = batch.getValue(position.toString())
            val fields = when (entry) {
                is PayloadValue.Group -> entry.entries
                PayloadValue.Null -> reject(DexcomG7RejectionCode.NULL_FIELD, ObservedDexcomG7Contract.GLUCOSE_VALUES, position)
                else -> reject(DexcomG7RejectionCode.WRONG_TYPE, ObservedDexcomG7Contract.GLUCOSE_VALUES, position)
            }
            unknownNames.collect(fields.keys - ObservedDexcomG7Contract.readingFields, topLevel = false)
            val reading = ObservedDexcomG7Reading(
                index = position,
                producerGlucoseValue = int32(fields, ObservedDexcomG7Contract.GLUCOSE_VALUE, position),
                producerTimestamp = int64(fields, ObservedDexcomG7Contract.TIMESTAMP, position),
                producerTrendArrow = text(fields, ObservedDexcomG7Contract.TREND_ARROW, position),
                transmitterId = text(fields, ObservedDexcomG7Contract.TRANSMITTER_ID, position),
                egvTxTime = int64(fields, ObservedDexcomG7Contract.EGV_TX_TIME, position),
            )
            val earlier = kept.firstOrNull { it.producerTimestamp == reading.producerTimestamp }
            when {
                earlier == null -> kept += reading
                earlier.sameContent(reading) -> collapsed++
                else -> reject(DexcomG7RejectionCode.CONFLICTING_DUPLICATE, ObservedDexcomG7Contract.TIMESTAMP, position)
            }
        }

        return ObservedDexcomG7Message(
            authenticatedPackage = authenticatedPackage,
            readings = kept,
            collapsedDuplicateCount = collapsed,
            unknownTopLevelFields = unknownNames.topLevel.sorted(),
            unknownReadingFields = unknownNames.reading.sorted(),
            unreportableUnknownFieldCount = unknownNames.unreportable,
        )
    }

    private fun field(fields: Map<String, PayloadValue>, name: String, index: Int?): PayloadValue =
        when (val value = fields[name]) {
            null -> reject(DexcomG7RejectionCode.MISSING_FIELD, name, index)
            PayloadValue.Null -> reject(DexcomG7RejectionCode.NULL_FIELD, name, index)
            else -> value
        }

    private fun text(fields: Map<String, PayloadValue>, name: String, index: Int?): String {
        val value = field(fields, name, index) as? PayloadValue.Text
            ?: reject(DexcomG7RejectionCode.WRONG_TYPE, name, index)
        if (value.value.isBlank()) reject(DexcomG7RejectionCode.BLANK_TEXT, name, index)
        return value.value
    }

    private fun int32(fields: Map<String, PayloadValue>, name: String, index: Int?): Int =
        (field(fields, name, index) as? PayloadValue.Int32)?.value
            ?: reject(DexcomG7RejectionCode.WRONG_TYPE, name, index)

    private fun int64(fields: Map<String, PayloadValue>, name: String, index: Int?): Long =
        (field(fields, name, index) as? PayloadValue.Int64)?.value
            ?: reject(DexcomG7RejectionCode.WRONG_TYPE, name, index)

    private fun group(fields: Map<String, PayloadValue>, name: String, index: Int?): Map<String, PayloadValue> =
        (field(fields, name, index) as? PayloadValue.Group)?.entries
            ?: reject(DexcomG7RejectionCode.WRONG_TYPE, name, index)
}

private class UnknownNames(private val reportableName: Regex) {
    val topLevel = LinkedHashSet<String>()
    val reading = LinkedHashSet<String>()
    private val unreportableNames = HashSet<String>()
    val unreportable: Int get() = unreportableNames.size

    fun collect(names: Set<String>, topLevel: Boolean) {
        for (name in names) {
            when {
                !reportableName.matches(name) -> unreportableNames += name
                topLevel -> this.topLevel += name
                else -> reading += name
            }
        }
    }
}
