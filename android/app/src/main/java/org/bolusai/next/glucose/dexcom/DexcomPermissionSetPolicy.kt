package org.bolusai.next.glucose.dexcom

import org.bolusai.dexcomg7.ApprovedPermissionSet
import java.security.MessageDigest

/**
 * Anchors approved by the owner on 2026-10-12 (ADR 0023, L2, I-1, I-2 option b, I-5 option a). They are not secrets
 * and are reviewed by pull request; verify.ps1 compares them with the ADR table. A change of any of them is a new
 * [POLICY_VERSION], which is also how a durable block is lifted: never automatically.
 */
internal object DexcomApprovedPermissionSet {
    const val PERMISSION = "com.dexcom.cgm.EXTERNAL_PERMISSION"
    const val POLICY_VERSION = "adr0023-i2b-2026-10-12"
    const val DEFINER_PACKAGE = "com.dexcom.g7"
    const val NEXT_PACKAGE = "org.bolusai.next"

    /** `PermissionInfo.PROTECTION_DANGEROUS`, the protection observed in L1. */
    const val PROTECTION_DANGEROUS = 1

    val members: List<ApprovedMember> = listOf(
        ApprovedMember("com.dexcom.g7", exactVersionCode = 4537L,
            signers = setOf("d30e0d3f08b70cedbc0804c96f2ec4599f243b5e149ef73a601757ad94c74145")),
        ApprovedMember("org.bolusai.companion", exactVersionCode = null,
            signers = setOf("1d1f25bf2aadfd82956610084f3acbf06bb69225b0c4fb031df8eae24f4f6a8d")),
        ApprovedMember("com.wtachtsugar", exactVersionCode = null,
            signers = setOf("1d1f25bf2aadfd82956610084f3acbf06bb69225b0c4fb031df8eae24f4f6a8d")),
    )
}

/** One approved holder. [exactVersionCode] null means new versions are accepted with the same package and signers. */
internal data class ApprovedMember(val packageName: String, val exactVersionCode: Long?, val signers: Set<String>)

/** The permission definition as Android reports it. */
internal data class PermissionDefinition(val declaringPackage: String, val protectionBase: Int)

/** One current holder of the permission, resolved in the same reading. */
internal data class PermissionHolder(
    val packageName: String,
    val uid: Int,
    val versionCode: Long,
    val signers: Set<String>,
    /** Every package Android reports for [uid]; more than one means a shared UID. */
    val packagesSharingUid: Set<String>,
)

/** What one reading of PackageManager produced. Nothing here comes from the broadcast. */
internal sealed interface HolderSnapshot {
    /**
     * [fullVisibility] is true only when package visibility filtering cannot hide a holder from Next: before Android 11,
     * or with `QUERY_ALL_PACKAGES` granted (ADR 0023, I-3). [definition] null means the permission is not defined.
     */
    data class Read(
        val fullVisibility: Boolean,
        val definition: PermissionDefinition?,
        val holders: List<PermissionHolder>,
        val ownPackage: String,
        val ownUid: Int,
    ) : HolderSnapshot

    /** The platform cannot provide signers and long version codes (before Android 9). */
    data object PlatformUnsupported : HolderSnapshot

    /** The query threw. Only the fact is kept, never the exception text. */
    data object QueryFailed : HolderSnapshot
}

/** Stable causes of a closed gate. [durable] causes write a block that only a new policy version lifts (point 5). */
internal enum class GateCause(val code: String, val durable: Boolean) {
    VISIBILITY_UNPROVEN("dexcom_reception.visibility_unproven", false),
    PLATFORM_UNSUPPORTED("dexcom_reception.platform_unsupported", false),
    NEXT_PERMISSION_MISSING("dexcom_reception.next_permission_missing", false),
    QUERY_FAILED("dexcom_reception.holders_query_failed", true),
    PERMISSION_UNDEFINED("dexcom_reception.permission_undefined", true),
    PERMISSION_DEFINITION_CHANGED("dexcom_reception.permission_definition_changed", true),
    HOLDER_SET_CHANGED("dexcom_reception.holder_set_changed", true),
    SHARED_UID("dexcom_reception.shared_uid", true),
    VERSION_CHANGED("dexcom_reception.producer_version_changed", true),
    SIGNERS_CHANGED("dexcom_reception.signers_changed", true),
    NEXT_IDENTITY_MISMATCH("dexcom_reception.next_identity_mismatch", true),
    /** A durable block of this policy version exists. */
    DURABLE_BLOCK_ACTIVE("dexcom_reception.durable_block_active", false),
    /** The block could not be read or written: reception stays closed (point 5). */
    BLOCK_STORAGE_FAILED("dexcom_reception.block_storage_failed", false),
    ;

    companion object {
        fun fromCode(code: String): GateCause? = entries.firstOrNull { it.code == code }
    }
}

internal sealed interface PermissionGate {
    data class Open(val provenance: ApprovedPermissionSet) : PermissionGate

    /** [blockedBy] is the cause recorded in a durable block, when [cause] is DURABLE_BLOCK_ACTIVE. */
    data class Closed(val cause: GateCause, val blockedBy: GateCause? = null) : PermissionGate
}

/**
 * Pure comparison of one holder reading with the approved set (ADR 0023, part I, points 2 to 4). Visibility comes
 * first: a partial enumeration is never compared, so a hidden approved member cannot cause a false durable block and
 * a hidden extra holder cannot pass as an exact match.
 */
internal object DexcomPermissionSetPolicy {
    fun evaluate(snapshot: HolderSnapshot): PermissionGate {
        val read = when (snapshot) {
            HolderSnapshot.PlatformUnsupported -> return PermissionGate.Closed(GateCause.PLATFORM_UNSUPPORTED)
            HolderSnapshot.QueryFailed -> return PermissionGate.Closed(GateCause.QUERY_FAILED)
            is HolderSnapshot.Read -> snapshot
        }
        if (!read.fullVisibility) return PermissionGate.Closed(GateCause.VISIBILITY_UNPROVEN)
        val definition = read.definition ?: return PermissionGate.Closed(GateCause.PERMISSION_UNDEFINED)
        if (definition.declaringPackage != DexcomApprovedPermissionSet.DEFINER_PACKAGE ||
            definition.protectionBase != DexcomApprovedPermissionSet.PROTECTION_DANGEROUS) {
            return PermissionGate.Closed(GateCause.PERMISSION_DEFINITION_CHANGED)
        }
        val byPackage = read.holders.groupBy { it.packageName }
        if (byPackage.values.any { it.size != 1 }) return PermissionGate.Closed(GateCause.HOLDER_SET_CHANGED)
        val others = byPackage.keys - read.ownPackage
        if (others != DexcomApprovedPermissionSet.members.map { it.packageName }.toSet()) {
            return PermissionGate.Closed(GateCause.HOLDER_SET_CHANGED)
        }
        if (read.holders.any { it.packagesSharingUid != setOf(it.packageName) } ||
            read.holders.map { it.uid }.toSet().size != read.holders.size) {
            return PermissionGate.Closed(GateCause.SHARED_UID)
        }
        for (member in DexcomApprovedPermissionSet.members) {
            val holder = byPackage.getValue(member.packageName).single()
            if (holder.signers != member.signers) return PermissionGate.Closed(GateCause.SIGNERS_CHANGED)
            if (member.exactVersionCode != null && holder.versionCode != member.exactVersionCode) {
                return PermissionGate.Closed(GateCause.VERSION_CHANGED)
            }
        }
        val own = byPackage[read.ownPackage]?.single()
            ?: return PermissionGate.Closed(GateCause.NEXT_PERMISSION_MISSING)
        if (read.ownPackage != DexcomApprovedPermissionSet.NEXT_PACKAGE || own.uid != read.ownUid) {
            return PermissionGate.Closed(GateCause.NEXT_IDENTITY_MISMATCH)
        }
        return PermissionGate.Open(ApprovedPermissionSet(DexcomApprovedPermissionSet.POLICY_VERSION, fingerprint(read.holders)))
    }

    /** SHA-256 of the sorted holders seen, with versions and signers, under the policy version. */
    fun fingerprint(holders: List<PermissionHolder>): String {
        val text = buildString {
            append("bolusai.dexcom.holders.v1\n").append(DexcomApprovedPermissionSet.POLICY_VERSION).append('\n')
            holders.sortedBy { it.packageName }.forEach {
                append(it.packageName).append(':').append(it.uid).append(':').append(it.versionCode).append(':')
                append(it.signers.sorted().joinToString(",")).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** Durable block of one policy version (ADR 0023, part I, point 5). */
internal interface DexcomReceptionBlocks {
    /** The recorded cause of the first block of [policyVersion], null when none; throws when it cannot be read. */
    fun activeBlock(policyVersion: String): GateCause?

    /** Appends a block. Throws when it cannot be persisted. */
    fun block(policyVersion: String, cause: GateCause, atEpochMs: Long)
}

/**
 * The barrier applied to every broadcast before its extras are read: an existing block closes it, then a fresh holder
 * reading is compared. A durable mismatch is persisted before rejecting; if it cannot be persisted, reception stays
 * closed as BLOCK_STORAGE_FAILED. Nothing here reads the payload.
 */
internal class DexcomPermissionGate(private val blocks: DexcomReceptionBlocks) {
    fun check(snapshot: () -> HolderSnapshot, nowEpochMs: () -> Long): PermissionGate {
        val existing = try {
            blocks.activeBlock(DexcomApprovedPermissionSet.POLICY_VERSION)
        } catch (_: Exception) {
            return PermissionGate.Closed(GateCause.BLOCK_STORAGE_FAILED)
        }
        if (existing != null) return PermissionGate.Closed(GateCause.DURABLE_BLOCK_ACTIVE, existing)
        val decision = DexcomPermissionSetPolicy.evaluate(try { snapshot() } catch (_: Exception) { HolderSnapshot.QueryFailed })
        if (decision is PermissionGate.Closed && decision.cause.durable) {
            try {
                blocks.block(DexcomApprovedPermissionSet.POLICY_VERSION, decision.cause, nowEpochMs())
            } catch (_: Exception) {
                return PermissionGate.Closed(GateCause.BLOCK_STORAGE_FAILED, decision.cause)
            }
        }
        return decision
    }
}
