package org.bolusai.dexcomg7

/**
 * Typed, platform-neutral copy of one broadcast as Android delivered it (ADR 0018).
 *
 * The Android adapter builds it inside the authenticated ingress from the Intent action and extras. It never carries
 * an Intent or a Bundle, and it never decides what a value means: that is the parser's job.
 */
public class DexcomG7Payload(
    public val action: String?,
    extras: Map<String, PayloadValue>,
) {
    public val extras: Map<String, PayloadValue> = extras.toMap()

    override fun equals(other: Any?): Boolean =
        other is DexcomG7Payload && other.action == action && other.extras == extras

    override fun hashCode(): Int = 31 * (action?.hashCode() ?: 0) + extras.hashCode()

    /**
     * Nothing here is validated yet: diagnostics show whether the action matches the contract and only key names
     * inside the parser's reportable grammar, never values or arbitrary producer text.
     */
    override fun toString(): String =
        "DexcomG7Payload(actionMatches=${action == ObservedDexcomG7Contract.ACTION}, ${diagnosticKeys(extras.keys)})"
}

/** The runtime type Android reported for one extra, with no conversion between types. */
public sealed interface PayloadValue {
    public class Text(public val value: String) : PayloadValue {
        override fun equals(other: Any?): Boolean = other is Text && other.value == value
        override fun hashCode(): Int = value.hashCode()

        /** Text extras include the transmitter identifier: never print their content. */
        override fun toString(): String = "Text(length=${value.length})"
    }

    public data class Int32(public val value: Int) : PayloadValue

    public data class Int64(public val value: Long) : PayloadValue

    public class Group(entries: Map<String, PayloadValue>) : PayloadValue {
        public val entries: Map<String, PayloadValue> = entries.toMap()

        override fun equals(other: Any?): Boolean = other is Group && other.entries == entries
        override fun hashCode(): Int = entries.hashCode()
        override fun toString(): String = "Group(${diagnosticKeys(entries.keys)})"
    }

    /** Present with a null value; distinct from an absent key. */
    public data object Null : PayloadValue

    /** Any other runtime type. Only its technical type name is kept, never its content. */
    public class Unsupported(public val typeName: String) : PayloadValue {
        override fun equals(other: Any?): Boolean = other is Unsupported && other.typeName == typeName
        override fun hashCode(): Int = typeName.hashCode()

        /** A sender-defined class name is producer text too: it is printed only inside the reportable grammar. */
        override fun toString(): String =
            if (isReportableName(typeName)) "Unsupported(typeName=$typeName)" else "Unsupported(typeName=<unreportable>)"
    }
}

private val reportableName = Regex("[A-Za-z0-9_.]{1,64}")

/** The grammar of names that diagnostics may print. Anything else is only counted. */
internal fun isReportableName(name: String): Boolean = reportableName.matches(name)

internal fun diagnosticKeys(keys: Set<String>): String {
    val reportable = keys.filter(::isReportableName).sorted()
    return "keys=$reportable, unreportableKeys=${keys.size - reportable.size}"
}
