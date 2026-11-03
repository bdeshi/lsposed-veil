# Veil

An LSPosed module that hides accessibility, debugging, and root/hook state from the apps
in its scope, without changing the actual state of the device.

> [!IMPORTANT]
> Built entirely by AI in one evening to fix a personal problem. YMMV — it works for me
> and hasn't been audited beyond that.

## Requirements

- Rooted device with a working Zygisk implementation and LSPosed (or an LSPosed-compatible
  framework such as Vector).
- Android 8.1+.

## Setup

1. Build (below) or grab a release APK from the repo's Releases page.
2. Enable the module in LSPosed Manager and scope it to the target apps.
3. Force-stop each target app; Veil applies from its next cold start.

If the target app still won't launch, check whether it's on your root manager's DenyList
(e.g. KernelSU: app profile → "Umount modules"). An app on that list never gets Zygisk
modules injected at all, Veil included. The settings screen's help panel covers this and
a few other gotchas.

## Features

Every app in scope gets all of these. Veil's settings screen is a read-only summary.

| Feature                  | Hides                                                  |
| ------------------------ | ------------------------------------------------------ |
| Accessibility services   | Any accessibility service running                      |
| USB / wireless debugging | `adb_enabled` / `adb_wifi_enabled`                     |
| Developer options        | Developer Options enabled state                        |
| Mock location            | Legacy pre-Marshmallow flag only                       |
| Root / manager apps      | Fixed list of ~30 packages (see `Features.kt`)         |
| Build tags/type          | Real build swapped for `release-keys` / `user`         |

The package list covers root managers (Magisk and forks, KernelSU and forks, APatch), module
managers, hook frameworks and their managers, and older su apps, plus Veil itself. Xposed modules in general have no common package name, so they
aren't covered by name.

Doesn't cover native-level root/hook detection or remote attestation (Play Integrity,
SafetyNet) — that's your Zygisk implementation's job, not Veil's.

### Why there's no per-app configuration

A hooked app can't read anything Veil's own app saves: Veil's data dir is private to it,
and the framework's shared-prefs redirect for legacy modules only works if the framework
hooks Veil's own process, which Vector doesn't reliably do. Settings would be silently
ignored, so there are none. To change what's hidden, edit `VeilHook.kt` /
`Features.HIDDEN_PACKAGES` and rebuild. For per-app package lists, use Hide My Applist.

## Building

Requires JDK 17 and Android SDK (platform 35, build-tools 35.0.0).

```
./gradlew assembleDebug       # debug-signed APK
./gradlew assembleRelease     # minified release build, debug-signed unless keystore.properties exists
scripts/generate-keystore.sh  # create a personal release-signing keystore
scripts/release.sh            # build + copy signed release APK into dist/
```

## CI releases

Pushing a tag matching `*.*.*` (e.g. `1.2.0`) triggers `.github/workflows/release.yml`.
For a properly signed CI build, set repo secrets: `KEYSTORE_BASE64` (base64 of your
`release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
