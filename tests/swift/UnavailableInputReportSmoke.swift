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
