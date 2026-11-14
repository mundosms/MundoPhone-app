# MundoPhone

MundoPhone is the softphone app of [MundoSMS](https://www.mundosms.es) for Android and iOS. It places and receives calls
through the MundoSMS business telephony platform and includes team presence, call history and contacts.

MundoPhone is based on [Linphone](https://www.linphone.org), developed by Belledonne Communications, and is distributed
under the GNU General Public License version 3. See [LICENSE](LICENSE).

## Repository layout

| Path | Contents |
|---|---|
| [`android/`](android) | Android app (Kotlin), based on linphone-android |
| [`android/third_party/linphone-sdk/`](android/third_party/linphone-sdk) | Linphone SDK version used by the Android app, the patches applied to it and the scripts to build it |
| [`ios/`](ios) | iOS app (Swift), based on linphone-iphone |

Every published release of the apps is tagged here, and the tagged tree is the source code of that release:

- `android-<versionName>-<versionCode>` for the Android app (Google Play)
- `ios-<version>-<build>` for the iOS app (App Store)

## Building the Android app

Requirements: JDK 17 or newer and the Android SDK with API level 37. The Gradle wrapper is included.

1. **Linphone SDK.** The app is built against the Linphone SDK version set in `gradle/libs.versions.toml`
   (`linphone = "…"`), which is Linphone SDK 5.5.38 with the patches in `third_party/linphone-sdk/parches/`.
   - Prebuilt: `third_party/linphone-sdk/obtener-sdk.sh <dir>` downloads it from the matching `sdk-<version>` release
     of this repository and verifies it against `third_party/linphone-sdk/SHA256SUMS`.
   - From source: see [`third_party/linphone-sdk/README.md`](android/third_party/linphone-sdk/README.md).
2. **Firebase.** `app/google-services.json` contains placeholder values. Replace it with the configuration of your own
   Firebase project to receive push notifications.
3. **Signing.** Release builds read `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).
4. Build:

   ```sh
   ./gradlew assembleRelease -PLinphoneSdkBuildDir=<dir>
   ```

## Building the iOS app

Requirements: Xcode 26 or newer. The deployment target is iOS 15.

1. Open `LinphoneApp.xcodeproj`. Swift Package Manager fetches the dependencies listed in
   `LinphoneApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved`, including the Linphone SDK 5.5.38
   (`linphone-sdk-swift-ios`) published by Belledonne Communications. The iOS app uses that SDK without modifications;
   its source code is available at <https://gitlab.linphone.org/BC/public/linphone-sdk> (tag `5.5.38`).
2. **Firebase.** `GoogleService-Info.plist` (app) and `msgNotificationService/GoogleService-Info.plist` (notification
   service extension) contain placeholder values. Replace them with the configuration of your own Firebase project.
3. **Signing.** Set your own team, bundle identifiers and provisioning profiles. The app uses push notifications
   (PushKit), App Groups and CallKit.

## License and notices

- MundoPhone is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
  version 3 as published by the Free Software Foundation. It is distributed WITHOUT ANY WARRANTY; see [LICENSE](LICENSE).
- Linphone and its SDK are © Belledonne Communications. Some SDK components are licensed under the GNU Affero General
  Public License version 3. Third-party components keep their own licenses, listed in the corresponding directories.
- This is a modified version of Linphone. The modifications were made by Comunicaciones Electrónicas Mundo SMS, S.L.
  (MundoSMS) and are marked in the source code with comments starting with `MundoPhone`. Last modified: November 2026.
- Linphone is a trademark of Belledonne Communications. MundoPhone is not affiliated with or endorsed by Belledonne
  Communications.
