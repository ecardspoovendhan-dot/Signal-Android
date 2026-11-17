#!/usr/bin/env python3
"""
Applies the News Gate changes to Signal's files at build time.

Signal's own files are never committed changed in the fork, so "Sync fork"
with upstream Signal never hits conflicts. Every edit checks that it found
exactly what it expects; if Signal changes these files, the build stops
with a clear message instead of silently doing the wrong thing.

What it does:
  1. Registers the gate screen and its start-up hook in the manifest.
  2. Names: "News" + newspaper icon on the home screen; "Browser" + globe
     icon everywhere else Android names the app (Settings, Battery,
     Digital Wellbeing, notifications, Accounts).
  3. Notification icon: plain globe.
  4. Blocks sharing from other apps, and stops publishing chat-name
     shortcuts (share sheet, long-press on the app icon).
  5. Stops adding a visible account and "Message/Call via Signal" rows to
     the phone's Contacts, and removes any that exist.
  6. In test mode (TEST_COPY=true), gives the app a different ID so it
     installs next to the real Signal.
"""
import os
import pathlib
import re
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
APP = ROOT / "app"
MAIN = APP / "src/main"
RES = MAIN / "res"
MANIFEST = MAIN / "AndroidManifest.xml"
GRADLE = APP / "build.gradle.kts"
OUR_RES = ROOT / "newsgate/res"
CONVERSATION_UTIL = MAIN / "java/org/thoughtcrime/securesms/util/ConversationUtil.java"
CONTACT_LINKS_JOB = MAIN / "java/org/thoughtcrime/securesms/jobs/SyncSystemContactLinksJob.kt"
MARK = "NEWSGATE"


def fail(msg: str) -> None:
    sys.exit(f"apply-patch: {msg} (Signal's code may have changed; the patch needs updating)")


def replace_once(text: str, old: str, new: str, what: str) -> str:
    count = text.count(old)
    if count != 1:
        fail(f"{what}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------- resources

def copy_resources() -> None:
    for src in OUR_RES.rglob("*"):
        if src.is_file():
            dst = RES / src.relative_to(OUR_RES)
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, dst)
    # Our vector ic_notification replaces Signal's per-density PNGs.
    for png in RES.glob("drawable-*/ic_notification.png"):
        png.unlink()
    print("resources: icons, labels and notification icon added")


# ---------------------------------------------------------------- manifest

GATE_BLOCK = """
        <!-- NEWSGATE: "Today's Brief" cover screen -->
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

"""


def element_span(text: str, anchor: str, open_tag: str, close_tag: str):
    """Start/end of the element containing `anchor` (end is after close_tag)."""
    i = text.find(anchor)
    if i < 0 or text.find(anchor, i + 1) >= 0:
        fail(f"manifest: '{anchor}' not found exactly once")
    start = text.rfind(open_tag, 0, i)
    end = text.find(close_tag, i)
    if start < 0 or end < 0:
        fail(f"manifest: could not delimit element around '{anchor}'")
    return start, end + len(close_tag)


def patch_manifest() -> None:
    text = MANIFEST.read_text(encoding="utf-8")
    if MARK in text:
        print("manifest: already patched")
        return

    # 1. Gate activity + start-up hook.
    if text.count("</application>") != 1:
        fail("manifest: expected exactly one </application>")
    text = text.replace("</application>", GATE_BLOCK.lstrip("\n") + "    </application>", 1)

    # 2a. App-wide name and icon -> Browser / globe.
    start = text.index("<application")
    end = text.index(">", start)
    tag = text[start:end]
    tag = replace_once(tag, 'android:icon="@mipmap/ic_launcher"', 'android:icon="@mipmap/newsgate_browser"', "application icon")
    tag = replace_once(tag, 'android:label="@string/app_name"', 'android:label="@string/newsgate_app_label"', "application label")
    text = text[:start] + tag + text[end:]

    # 2b. Home-screen entry -> News / newspaper.
    start, end = element_span(text, 'android:name=".RoutingActivity"', "<activity-alias", "</activity-alias>")
    alias = text[start:end]
    alias = replace_once(
        alias,
        'android:name=".RoutingActivity"',
        'android:name=".RoutingActivity"\n'
        '            android:icon="@mipmap/newsgate_news"\n'
        '            android:label="@string/newsgate_launcher_label"',
        "launcher alias",
    )
    alias = alias.replace("@mipmap/ic_launcher", "@mipmap/newsgate_news")
    text = text[:start] + alias + text[end:]

    # 4a. Not a share target: drop ShareActivity's SEND filters and direct-share hook.
    start, end = element_span(text, 'android:name=".sharing.v2.ShareActivity"', "<activity", "</activity>")
    share = text[start:end]
    share, n = re.subn(r"\s*<intent-filter>.*?</intent-filter>", "", share, flags=re.S)
    if n != 2:
        fail(f"manifest: expected 2 share intent-filters, found {n}")
    share, n = re.subn(r"\s*<meta-data\s+android:name=\"android\.service\.chooser\.chooser_target_service\".*?/>", "", share, flags=re.S)
    if n != 1:
        fail(f"manifest: expected 1 chooser meta-data, found {n}")
    share = replace_once(share, 'android:exported="true"', 'android:exported="false"', "ShareActivity exported")
    text = text[:start] + share + text[end:]

    MANIFEST.write_text(text, encoding="utf-8")
    print("manifest: gate, names/icons and share blocking applied")


# ---------------------------------------------------------------- xml resources

def patch_xml_resources() -> None:
    # 4b. No share-target declaration.
    (RES / "xml/shortcuts.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<shortcuts xmlns:android="http://schemas.android.com/apk/res/android" />\n',
        encoding="utf-8",
    )
    # 5a. Accounts/Contacts branding (in case the account is ever shown).
    auth = RES / "xml/authenticator.xml"
    text = auth.read_text(encoding="utf-8")
    if "newsgate_app_label" not in text:
        text = replace_once(text, 'android:label="@string/app_name"', 'android:label="@string/newsgate_app_label"', "authenticator label")
    text = text.replace("@mipmap/ic_launcher", "@mipmap/newsgate_browser")
    auth.write_text(text, encoding="utf-8")
    contacts = RES / "xml/contactsformat.xml"
    contacts.write_text(contacts.read_text(encoding="utf-8").replace("@mipmap/ic_launcher", "@mipmap/newsgate_browser"), encoding="utf-8")
    print("xml: share-target removed, account relabelled")


# ---------------------------------------------------------------- code

def patch_code() -> None:
    # 4c. Never publish chat-name shortcuts (Signal itself does this when its
    #     own Screen lock is on); clear any that already exist.
    text = CONVERSATION_UTIL.read_text(encoding="utf-8")
    if MARK not in text:
        sig = "  public static boolean setActiveShortcuts(@NonNull Context context, @NonNull List<Recipient> rankedRecipients) {\n"
        text = replace_once(text, sig, sig + "    if (true) { clearAllShortcuts(context); return true; } // NEWSGATE\n", "setActiveShortcuts")
        sig = "  private static boolean pushShortcutForRecipientInternal(@NonNull Context context, @NonNull Recipient recipient, int rank, @NonNull Direction direction) {\n"
        text = replace_once(text, sig, sig + "    if (true) return true; // NEWSGATE\n", "pushShortcutForRecipientInternal")
        CONVERSATION_UTIL.write_text(text, encoding="utf-8")
    # 5b. No account / "Message via Signal" rows in the phone's Contacts.
    text = CONTACT_LINKS_JOB.read_text(encoding="utf-8")
    if MARK not in text:
        sig = "  override fun onRun() {\n"
        text = replace_once(
            text,
            sig,
            sig
            + "    // NEWSGATE: keep the app out of the phone's Contacts and Accounts.\n"
            + "    try {\n"
            + "      val accountManager = android.accounts.AccountManager.get(context)\n"
            + "      accountManager.getAccountsByType(BuildConfig.APPLICATION_ID).forEach { accountManager.removeAccountExplicitly(it) }\n"
            + "    } catch (e: Exception) {\n"
            + "      Log.w(TAG, \"Could not remove contacts account\", e)\n"
            + "    }\n"
            + "    if (true) return\n",
            "SyncSystemContactLinksJob.onRun",
        )
        CONTACT_LINKS_JOB.write_text(text, encoding="utf-8")
    print("code: chat shortcuts and contacts links disabled")


# ---------------------------------------------------------------- test copy

def patch_test_copy() -> None:
    if os.environ.get("TEST_COPY", "").lower() != "true":
        print("gradle: real build (same app ID as Signal)")
        return
    text = GRADLE.read_text(encoding="utf-8")
    if '".newsgate"' in text:
        print("gradle: already patched")
        return
    marker = '    getByName("debug") {\n'
    text = replace_once(text, marker, marker + '      applicationIdSuffix = ".newsgate"\n', "debug build type")
    GRADLE.write_text(text, encoding="utf-8")
    print("gradle: test copy -> app ID org.thoughtcrime.securesms.newsgate")


if __name__ == "__main__":
    copy_resources()
    patch_manifest()
    patch_xml_resources()
    patch_code()
    patch_test_copy()
