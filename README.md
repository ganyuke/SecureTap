# SecureTap

Send a password, link, message or small photo from one Android phone to another by tapping
them together, encrypted end to end.

## How it works

Android Beam (phone-to-phone NFC) was removed in Android 10, so SecureTap uses
**Host Card Emulation**: the sending phone pretends to be an NFC smart card, and the receiving
phone reads it in **reader mode** using ISO-DEP APDUs.

| Step | Receiver → Sender | Sender → Receiver |
|---|---|---|
| SELECT | proprietary AID `F053454354415001` | version, ephemeral P-256 public key, session id, length |
| KEY | receiver's ephemeral P-256 public key | ciphertext length (sender has now encrypted the payload) |
| READ ×N | offset + length | ciphertext chunk |
| FINISH | — | OK (sender wipes the payload) |

**Crypto:** ECDH P-256 (fresh keys per transfer, so forward secrecy) → HKDF-SHA256 (salted
with the session id, info bound to a SHA-256 transcript of both keys) → AES-256-GCM, with the
transcript as associated data. Peer points are checked to be on the curve before ECDH.

**Verification code:** after a transfer, both phones have a 6-digit code derived from the shared
secret under **Details**. If someone relayed the connection and swapped keys, the two codes won't
match. NFC's few-cm range makes that very unlikely, so the code is tucked away for anyone who wants
to check.

**Other protections**
- One receiver per send: each send uses fresh keys and ends after one transfer. The payload stays
  in memory for **Send again** until you tap Done, and is wiped after 2 min without contact.
- HCE only answers while the phone is unlocked (`requireDeviceUnlock`) and the Send screen is in
  front. Leaving the app pauses the payload.
- `FLAG_SECURE` blocks screenshots/recents thumbnails; backups are disabled.
- Copied passwords are flagged as sensitive so Android 13+ hides the clipboard preview.
- Received links are shown in full and only `http(s)` links can be opened.
- Photos are re-encoded as JPEG ≤ 120 KB, which also strips EXIF metadata such as GPS.
- If the phones slip apart, tapping again **resumes** from the last received byte.

## Limits

- NFC is slow, around 5–20 KB/s depending on hardware. A 120 KB photo means holding the phones
  together for roughly 10–20 s. The hard cap is 512 KB.
- Reader mode turns off card emulation, so the sender must be on **Send** and the receiver on
  **Receive**.
- Phone-to-phone HCE works on most devices but not all; a few NFC controllers are unreliable at it.
- Requires Android 10+ with NFC and HCE.

## Build

```sh
./gradlew testDebugUnitTest   # protocol & crypto tests (JVM)
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with the key from `keystore.properties` (gitignored: `storeFile`,
`storePassword`, `keyAlias`), or the debug key without one.

## Releasing

Pushing a tag like `v0.1.0` (`v` + `versionName`) runs the Release workflow. It runs the tests,
builds and signs the APK, attests it, and publishes a GitHub Release with
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` as the notes. The NextColor Apps
F-Droid repo picks up new releases, and the store listing in `fastlane/`, on its own.

One-time setup: make a key and add it as the `RELEASE_KEYSTORE_BASE64` (`base64 -w0` of the file)
and `RELEASE_KEYSTORE_PASSWORD` Actions secrets. Back it up, because updates only install if
they're signed with the same key.

```sh
keytool -genkeypair -alias nextcolor -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12 -keystore securetap-release.p12 -dname "CN=SecureTap"
```

Code layout: `crypto/` (key agreement, KDF, AEAD), `protocol/` (pure-Kotlin sender/receiver
state machines, unit-tested end to end), `nfc/` (HCE service, reader callback), `ui/` (Compose).
