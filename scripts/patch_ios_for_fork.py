#!/usr/bin/env python3
"""Throwaway patches applied to a CI checkout of tasks/tasks before building it from a fork.

Lives on the fork's `fork-tools` branch next to .github/workflows/fork-ios.yml. The result is
never committed and never part of a PR to tasks/tasks.

Every patch fails loudly when upstream changes the code it targets, so a stale patch breaks the
workflow visibly instead of silently producing a build that reports to the maintainer's services
or is signed the wrong way.

Usage (run from the repository root):
  patch_ios_for_fork.py --disable-crash-reporting
  patch_ios_for_fork.py --disable-crash-reporting --manual-signing "Profile Name" --export-compliance-exempt
"""
import argparse
import pathlib
import plistlib
import re
import sys

PBXPROJ = pathlib.Path("iosApp/iosApp.xcodeproj/project.pbxproj")
APP_DELEGATE = pathlib.Path("iosApp/iosApp/AppDelegate.swift")
ENTITLEMENTS = pathlib.Path("iosApp/iosApp/iosApp.entitlements")
INFO_PLIST = pathlib.Path("iosApp/iosApp/Info.plist")

# The app target has one Debug and one Release configuration. If upstream adds a target
# (widget, share extension), signing settings appear more often and need a profile each.
APP_TARGET_CONFIGS = 2


def fail(message: str) -> None:
    print(f"::error::{message}")
    sys.exit(1)


def replace(path: pathlib.Path, old: str, new: str, *, regex: bool = False, expect: int | None = None) -> None:
    if not path.exists():
        fail(f"{path} not found. Run from the repository root, or upstream moved the file.")
    text = path.read_text()
    if regex:
        text, count = re.subn(old, lambda _match: new, text)
    else:
        count = text.count(old)
        text = text.replace(old, new)
    if count == 0:
        fail(f"{path}: pattern not found: {old!r}. Upstream changed; update scripts/patch_ios_for_fork.py.")
    if expect is not None and count != expect:
        fail(f"{path}: expected {expect} matches for {old!r}, found {count}. Did upstream add a target?")
    path.write_text(text)
    print(f"{path}: replaced {count}x {old[:70]!r}")


def edit_plist(path: pathlib.Path, change) -> None:
    if not path.exists():
        fail(f"{path} not found.")
    data = plistlib.loads(path.read_bytes())
    change(data)
    path.write_bytes(plistlib.dumps(data))
    print(f"{path}: updated")


def disable_crash_reporting() -> None:
    """Fork builds must not send crashes or upload dSYMs to the maintainer's Firebase project."""
    replace(APP_DELEGATE, "CrashlyticsKt.installCrashlytics()", "// fork build: Crashlytics disabled")
    replace(
        PBXPROJ,
        r'shellScript = "[^\n]*firebase-ios-sdk/Crashlytics/run[^\n]*";',
        'shellScript = "echo Skipping Crashlytics dSYM upload in fork build\\n";',
        regex=True,
        expect=1,
    )

    def collection_off(info: dict) -> None:
        info["FirebaseCrashlyticsCollectionEnabled"] = False

    edit_plist(INFO_PLIST, collection_off)


def manual_signing(profile_name: str) -> None:
    """Sign only the app target with a distribution certificate and an App Store profile.

    Command-line overrides would also hit the Swift package targets (Firebase, PostHog) and fail,
    so the target's own settings are patched instead.
    """
    if '"' in profile_name or "\\" in profile_name:
        fail("Provisioning profile name must not contain quotes or backslashes.")
    replace(PBXPROJ, "CODE_SIGN_STYLE = Automatic;", "CODE_SIGN_STYLE = Manual;", expect=APP_TARGET_CONFIGS)
    replace(
        PBXPROJ,
        'CODE_SIGN_IDENTITY = "Apple Development";',
        'CODE_SIGN_IDENTITY = "Apple Distribution";',
        expect=APP_TARGET_CONFIGS,
    )
    replace(
        PBXPROJ,
        'PROVISIONING_PROFILE_SPECIFIER = "";',
        f'PROVISIONING_PROFILE_SPECIFIER = "{profile_name}";',
        expect=APP_TARGET_CONFIGS,
    )

    def production_push(entitlements: dict) -> None:
        # App Store profiles carry aps-environment=production; manual signing requires a match.
        if "aps-environment" in entitlements:
            entitlements["aps-environment"] = "production"

    edit_plist(ENTITLEMENTS, production_push)


def export_compliance_exempt() -> None:
    """Without this key every TestFlight build waits for a manual export-compliance answer."""

    def exempt(info: dict) -> None:
        info["ITSAppUsesNonExemptEncryption"] = False

    edit_plist(INFO_PLIST, exempt)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--disable-crash-reporting", action="store_true")
    parser.add_argument("--manual-signing", metavar="PROFILE_NAME")
    parser.add_argument("--export-compliance-exempt", action="store_true")
    args = parser.parse_args()

    if not (args.disable_crash_reporting or args.manual_signing or args.export_compliance_exempt):
        parser.error("nothing to do")

    if args.disable_crash_reporting:
        disable_crash_reporting()
    if args.manual_signing:
        manual_signing(args.manual_signing)
    if args.export_compliance_exempt:
        export_compliance_exempt()


if __name__ == "__main__":
    main()
