#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")/.."
repository_root="$PWD"
framework_dir="$repository_root/shared/bolus-engine/build/bin/iosSimulatorArm64/debugFramework"
output_dir="$repository_root/build/swift-smoke"
mkdir -p "$output_dir"

sdk_path="$(xcrun --sdk iphonesimulator --show-sdk-path)"
minimum_os="$(/usr/libexec/PlistBuddy -c 'Print :MinimumOSVersion' "$framework_dir/BolusEngine.framework/Info.plist")"
xcrun --sdk iphonesimulator swiftc \
    -sdk "$sdk_path" -target "arm64-apple-ios${minimum_os}-simulator" \
    -F "$framework_dir" -framework BolusEngine \
    "$repository_root/tests/swift/UnavailableInputReportSmoke.swift" \
    -o "$output_dir/report-smoke"

# Use an available iPhone simulator from this CI host; no fixed device UUID.
device_id="$(xcrun simctl list devices available --json | python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"]
for runtime, candidates in devices.items():
    if ".iOS-" in runtime:
        for device in candidates:
            if device["isAvailable"] and device["name"].startswith("iPhone"):
                print(device["udid"])
                sys.exit(0)
sys.exit("No available iPhone simulator")
')"
xcrun simctl bootstatus "$device_id" -b
xcrun simctl spawn "$device_id" "$output_dir/report-smoke"
