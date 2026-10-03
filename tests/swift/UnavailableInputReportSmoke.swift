import Foundation
import BolusEngine

// Exercise the exported Objective-C/Swift boundary, not a Kotlin test runner.
do {
    _ = try UnavailableInputReport(reports: [])
    fatalError("An empty report must fail")
} catch {
    precondition(
        (error as NSError).localizedDescription == "unavailable_input_report.empty",
        "The stable contract error must reach Swift"
    )
}

// The process must remain usable after catching the rejected constructor.
let missing = UnavailableInput(input: InputKind.glucose, reason: UnavailabilityReason.missing)
let report = try UnavailableInputReport(reports: [missing, missing])
precondition(report.contractVersion == 1)
precondition(report.entries.count == 1)
precondition(report.entries[0].code == "input.glucose.missing")
print("Swift binding: empty report caught; subsequent non-empty report succeeded")

// Contract v2 (ADR 0015): a rejected cause and a rejected report are caught with their stable identifiers.
do {
    _ = try InputUnavailability(input: InputKind.glucose, reason: UnavailabilityReasonV2.unconfirmed, details: [])
    fatalError("unconfirmed must be rejected for glucose")
} catch {
    precondition(
        (error as NSError).localizedDescription == "input_unavailability.reason_not_admitted",
        "The stable v2 cause error must reach Swift"
    )
}
do {
    _ = try InputUnavailability(input: InputKind.profile, reason: UnavailabilityReasonV2.unconfirmed, details: ["Profile.x"])
    fatalError("A malformed detail must be rejected")
} catch {
    precondition((error as NSError).localizedDescription == "input_unavailability.detail_malformed")
}
do {
    _ = try InputUnavailabilityReport(causes: [])
    fatalError("An empty v2 report must fail")
} catch {
    precondition(
        (error as NSError).localizedDescription == "input_unavailability_report.empty",
        "The stable v2 report error must reach Swift"
    )
}

// The process must remain usable after the rejections above.
let revoked = try InputUnavailability(
    input: InputKind.profile,
    reason: UnavailabilityReasonV2.unconfirmed,
    details: ["profile.confirmation.revoked", "profile.confirmation.revoked"]
)
let policy = try InputUnavailability(
    input: InputKind.profile,
    reason: UnavailabilityReasonV2.policyNotApproved,
    details: ["profile.not_approved_for_calculation"]
)
let reportV2 = try InputUnavailabilityReport(causes: [revoked, policy])
precondition(reportV2.contractVersion == 2)
precondition(reportV2.entries.map { $0.code } == ["input.profile.policy_not_approved", "input.profile.unconfirmed"])
precondition(reportV2.entries[1].details == ["profile.confirmation.revoked"])
let elevated = InputUnavailability.companion.from(cause: missing)
precondition(elevated.code == "input.glucose.missing" && elevated.details.isEmpty)
print("Swift binding: v2 cause and report rejections caught; subsequent v2 report succeeded")
