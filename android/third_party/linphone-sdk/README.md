# linphone-sdk with MundoSMS patches

The app is built against a linphone-sdk **compiled from source with MundoSMS patches**, not against Belledonne's prebuilt
AAR. The version is the one in `gradle/libs.versions.toml` (`linphone = "5.5.38-mundosms.2"`). That Maven coordinate does
not exist in Belledonne's repository, so the two can never be mixed up.

| File | What it is |
|---|---|
| `parches/000N-*.patch` + `parches/README.md` | The patches, each one with its reason and how to check it |
| `linphone-sdk-5.5.38.submodulos.txt` | Exact commit of every submodule of the base (`linphone-sdk` 5.5.38, `dbff0da8f`) |
| `android-r30.Dockerfile` | Belledonne's build environment for 5.5 (NDK r30), pinned |
| `build-sdk-android.sh` | The build (preset `android-sdk`, Ninja, RelWithDebInfo, arm64 + armv7) |
| `SHA256SUMS` | Checksums of the packages the app uses |
| `obtener-sdk.sh` | Downloads the package from the `sdk-<version>` release, checks its checksum and leaves it ready for Gradle |

## How the app uses it

- **Prebuilt package:** `third_party/linphone-sdk/obtener-sdk.sh ~/.cache/mundophone-sdk`, then
  `./gradlew -PLinphoneSdkBuildDir=$HOME/.cache/mundophone-sdk assembleDebug`. The script downloads
  `linphone-sdk-android-<version>-maven.tar` from the `sdk-<version>` release of `mundosms/MundoPhone-app` (no token
  needed; set `GITHUB_REPOSITORY=<owner>/<repo>` to use another repository, and `GH_TOKEN` if it is private). If the
  checksum does not match or the download fails, it exits with an error, so the app is never built by mistake against
  the official SDK without the patches.
- **Your own build:** point `-PLinphoneSdkBuildDir` to the output folder of `build-sdk-android.sh`.
- **Native symbols:** the package only contains `maven_repository`. The unstripped libraries are in
  `linphone-sdk-android-<version>-libs-debug.zip` of the same release (`obtener-sdk.sh <dir> --con-simbolos`).

## Building the SDK

1. Get Belledonne's sources at the release tag, with submodules:
   `git clone --branch <tag> --recurse-submodules --shallow-submodules --depth 1 https://gitlab.linphone.org/BC/public/linphone-sdk.git`
   (the tags are also on the mirror `https://github.com/BelledonneCommunications/linphone-sdk.git`). Check the
   submodules against `linphone-sdk-5.5.38.submodulos.txt` with `git submodule status --recursive`.
2. Apply the patches from the root of the monorepo: `git apply third_party/linphone-sdk/parches/000N-*.patch`. Patch
   0012 (libopus 1.5.2) needs a few extra steps, described in `parches/README.md`.
3. Build the image: `docker build -f android-r30.Dockerfile -t mundophone/linphone-sdk-android:r30-20261022 <context>`.
   The context contains `dl/` with the NDK, the Android cmdline-tools and their `SHA256SUMS`.
4. Build the SDK with its own version:
   `SDK_VERSION=<base>-mundosms.<n> third_party/linphone-sdk/build-sdk-android.sh <sources> <output>`
   (about 10 minutes with 8 CPUs; `SDK_CPUS` and `SDK_CCACHE` change the number of cores and the ccache folder).
5. Check that the patches are in the binaries: each patch writes a log line, listed in `parches/README.md`.
6. Pack `maven_repository/org/linphone/linphone-sdk-android/` (metadata, AAR and pom of that version) into
   `linphone-sdk-android-<version>-maven.tar` and publish it as the `sdk-<version>` release, together with the
   `-libs-debug.zip`.
7. Update `SHA256SUMS` and `linphone = "<version>"` in `gradle/libs.versions.toml`.

## License

linphone-sdk is © Belledonne Communications and is distributed under the GNU GPLv3 (some components under the GNU
AGPLv3). The patches in `parches/` are distributed under the same licenses.
