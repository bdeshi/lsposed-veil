# Veil

An LSPosed module that hides accessibility, debugging, and root/hook state from specific
apps, per app, without changing the actual state of the device.

> [!IMPORTANT]
> Built entirely by AI in one evening to fix a personal problem. YMMV — it works for me
> and hasn't been audited beyond that.

## Requirements

- Rooted device with a working Zygisk implementation and LSPosed (or an LSPosed-compatible
  framework such as Vector).
- Android 8.1+.

## Setup

1. Build (below) or grab a release APK from the repo's Releases page.
2. Enable the module in LSPosed Manager and scope it to the target app.
3. Open the target app once — it appears in Veil's own settings screen automatically.
4. Uncheck anything you don't want hidden for that app.

If the target app still won't launch, check whether it's on your root manager's DenyList
(e.g. KernelSU: app profile → "Umount modules"). An app on that list never gets Zygisk
modules injected at all, Veil included. The settings screen's help panel covers this and
a few other gotchas.

## Features

Per app, per feature — anything not listed gets everything hidden by default.

| Feature                  | Hides                                                  |
| ------------------------ | ------------------------------------------------------ |
| Accessibility services   | Any accessibility service running                      |
| USB / wireless debugging | `adb_enabled` / `adb_wifi_enabled`                     |
| Developer options        | Developer Options enabled state                        |
| Mock location            | Legacy pre-Marshmallow flag only                       |
| Root / manager apps      | Configurable package list (default: KernelSU, Magisk)  |
| Build tags/type          | Real build swapped for `release-keys` / `user`         |

Doesn't cover native-level root/hook detection or remote attestation (Play Integrity,
SafetyNet) — that's your Zygisk implementation's job, not Veil's.

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
