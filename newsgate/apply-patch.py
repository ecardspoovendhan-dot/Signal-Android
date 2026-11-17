#!/usr/bin/env python3
"""
Applies the News Gate changes to Signal's files at build time.

Signal's own files are left untouched in the fork, so "Sync fork" with
upstream Signal never hits conflicts. This script runs in the build and:
  1. registers the gate screen and its start-up hook in the app manifest;
  2. in test mode (TEST_COPY=true), gives the app a different ID so it
     installs next to the real Signal instead of over it.
"""
import os
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
GRADLE = ROOT / "app/build.gradle.kts"

GATE_BLOCK = """
        <!-- News Gate: "Today's Brief" cover screen -->
        <activity
            android:name="org.thoughtcrime.securesms.newsgate.NewsGateActivity"
            android:exported="false"
            android:launchMode="singleTop"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode"
            android:theme="@android:style/Theme.Material.NoActionBar" />

        <provider
            android:name="org.thoughtcrime.securesms.newsgate.NewsGateInitProvider"
            android:authorities="${applicationId}.newsgate"
            android:exported="false" />

    </application>"""


def patch_manifest() -> None:
    text = MANIFEST.read_text(encoding="utf-8")
    if "NewsGateActivity" in text:
        print("manifest: already patched")
        return
    if text.count("</application>") != 1:
        sys.exit("manifest: expected exactly one </application> tag")
    text = text.replace("    </application>", GATE_BLOCK, 1) if "    </application>" in text \
        else text.replace("</application>", GATE_BLOCK, 1)
    MANIFEST.write_text(text, encoding="utf-8")
    print("manifest: gate registered")


def patch_test_copy() -> None:
    if os.environ.get("TEST_COPY", "").lower() != "true":
        print("gradle: real build (same app ID as Signal)")
        return
    text = GRADLE.read_text(encoding="utf-8")
    marker = '    getByName("debug") {\n'
    if ".newsgate\"" in text:
        print("gradle: already patched")
        return
    if marker not in text:
        sys.exit("gradle: could not find the debug build type")
    text = text.replace(marker, marker + '      applicationIdSuffix = ".newsgate"\n', 1)
    GRADLE.write_text(text, encoding="utf-8")
    print("gradle: test copy -> app ID org.thoughtcrime.securesms.newsgate")


if __name__ == "__main__":
    patch_manifest()
    patch_test_copy()
