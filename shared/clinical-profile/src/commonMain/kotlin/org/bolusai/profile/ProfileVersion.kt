package org.bolusai.profile

enum class ProfileOrigin(val code: String) {
    /** Typed or edited by the user; may name the restored version the edit started from. */
    MANUAL("manual"),
    /** Exact copy of an earlier version; the fingerprints must match. */
    RESTORED("restored"),
    /** Reserved so the stored format does not change later. Rejected everywhere in this phase (ADR 0012). */
    SYSTEM_PROPOSAL_ACCEPTED("system_proposal_accepted");

    companion object {
        fun fromCode(code: String): ProfileOrigin? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One immutable saved version. Construction re-derives the fingerprint and rejects any provenance the current phase
 * cannot produce. [createdAtEpochMs] is informative device time; [version] is the authoritative order.
 */
data class ProfileVersion(
    val version: Long,
    val content: ProfileContent,
    val contentSha256: String,
    val origin: ProfileOrigin,
    val restoredFrom: Long?,
    val createdAtEpochMs: Long,
    val writer: String,
) {
    init {
        require(version > 0) { "profile.version.number" }
        require(contentSha256 == ProfileCodec.sha256(content)) { "profile.version.fingerprint" }
        require(origin != ProfileOrigin.SYSTEM_PROPOSAL_ACCEPTED) { ProfileFailure.ORIGIN_NOT_ENABLED.code }
        require(origin != ProfileOrigin.RESTORED || restoredFrom != null) { "profile.version.restored_source" }
        require(restoredFrom == null || restoredFrom in 1 until version - 1) { "profile.version.restored_order" }
        require(isValidWriter(writer)) { "profile.version.writer" }
    }

    val allowsCalculation: Boolean get() = false
    val allowsTreatment: Boolean get() = false
    val blockCode: String get() = NOT_APPROVED_CODE

    companion object {
        const val NOT_APPROVED_CODE = "profile.not_approved_for_calculation"
        fun isValidWriter(writer: String): Boolean =
            writer.length in 1..128 && writer.all { it.code in 0x20..0x7e }
    }
}

/** What a caller asks to append. [origin] is derived by [ProfileEditor]; the write policy re-checks it. */
data class ProfileWrite(
    val baseVersion: Long,
    val content: ProfileContent,
    val origin: ProfileOrigin,
    val restoredFrom: Long?,
)

/**
 * Transition rules shared by the write policy and by every history read (ADR 0012, sections 4.4, 6 and 8).
 * [previous] is the version this one replaced and [source] the version named by restoredFrom, both as stored.
 */
object ProfileRules {
    fun transitionProblem(
        content: ProfileContent,
        contentSha256: String,
        origin: ProfileOrigin,
        restoredFrom: Long?,
        previous: ProfileVersion?,
        source: ProfileVersion?,
    ): ProfileFailure? {
        if (origin == ProfileOrigin.SYSTEM_PROPOSAL_ACCEPTED) return ProfileFailure.ORIGIN_NOT_ENABLED
        if ((restoredFrom == null) != (source == null) || (restoredFrom != null && source?.version != restoredFrom)) {
            return ProfileFailure.INVALID_RECORD
        }
        when (origin) {
            ProfileOrigin.RESTORED -> {
                // An exact copy carries its own unit together with its values; nothing is reinterpreted.
                if (source == null || source.contentSha256 != contentSha256) return ProfileFailure.INVALID_ORIGIN
                return null
            }
            ProfileOrigin.MANUAL -> {
                if (source != null && source.contentSha256 == contentSha256) return ProfileFailure.INVALID_ORIGIN
            }
            ProfileOrigin.SYSTEM_PROPOSAL_ACCEPTED -> return ProfileFailure.ORIGIN_NOT_ENABLED
        }
        val start = source ?: previous
        val startUnit = start?.content?.glucoseUnit
        if (startUnit is Setting.Declared && content.glucoseUnit != startUnit && content.hasGlucoseDependentValues) {
            return ProfileFailure.UNIT_CHANGE_WITH_VALUES
        }
        return null
    }
}

/** Decision of the shared write policy, evaluated by the storage adapter inside its write transaction. */
sealed interface ProfileWriteDecision {
    data class Insert(val version: ProfileVersion) : ProfileWriteDecision
    data class Existing(val version: ProfileVersion) : ProfileWriteDecision
    data class Reject(val reason: ProfileFailure) : ProfileWriteDecision
}

object ProfileWritePolicy {
    /**
     * [latest] is the newest stored version, [atBasePlusOne] the stored version numbered baseVersion + 1 (if any) and
     * [source] the stored version named by restoredFrom (if any). All three must come from the same transaction.
     */
    fun evaluate(
        write: ProfileWrite,
        createdAtEpochMs: Long,
        writer: String,
        latest: ProfileVersion?,
        atBasePlusOne: ProfileVersion?,
        source: ProfileVersion?,
    ): ProfileWriteDecision {
        if (write.origin == ProfileOrigin.SYSTEM_PROPOSAL_ACCEPTED) return ProfileWriteDecision.Reject(ProfileFailure.ORIGIN_NOT_ENABLED)
        if (write.baseVersion < 0 || write.baseVersion == Long.MAX_VALUE || !ProfileVersion.isValidWriter(writer)) {
            return ProfileWriteDecision.Reject(ProfileFailure.INVALID_RECORD)
        }
        val sha = ProfileCodec.sha256(write.content)
        // An identical retry returns the committed version even if later versions exist; metadata is ignored.
        if (atBasePlusOne != null && atBasePlusOne.contentSha256 == sha && atBasePlusOne.origin == write.origin &&
            atBasePlusOne.restoredFrom == write.restoredFrom) {
            return ProfileWriteDecision.Existing(atBasePlusOne)
        }
        if ((latest?.version ?: 0L) != write.baseVersion) return ProfileWriteDecision.Reject(ProfileFailure.CONFLICT)
        val next = write.baseVersion + 1
        if (write.restoredFrom != null && (write.restoredFrom !in 1 until next - 1)) {
            return ProfileWriteDecision.Reject(ProfileFailure.INVALID_RECORD)
        }
        if (latest != null && latest.contentSha256 == sha) return ProfileWriteDecision.Reject(ProfileFailure.UNCHANGED)
        ProfileRules.transitionProblem(write.content, sha, write.origin, write.restoredFrom, latest, source)?.let {
            return ProfileWriteDecision.Reject(it)
        }
        return ProfileWriteDecision.Insert(ProfileVersion(next, write.content, sha, write.origin, write.restoredFrom,
            createdAtEpochMs, writer))
    }
}
