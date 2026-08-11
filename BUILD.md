# Building OpenIR on macOS (Apple Silicon) without Android Studio

OpenIR is a standard Kotlin/Jetpack Compose Android app. You only need the command-line
Android SDK — no Android Studio.

## 1. Install the toolchain (one time)

```bash
brew install --cask temurin@17          # AGP needs JDK 17 (do not use JDK 21+)
brew install --cask android-commandlinetools
brew install gradle                      # only used once, to generate the wrapper
```

Set up environment (add to `~/.zshrc`):

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

> The `temurin@17` **cask** installs into `/Library/Java/JavaVirtualMachines/`, not
> `/opt/homebrew/opt/` — so use the path above. (If you used the formula `brew install
> openjdk@17` instead, set `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.)

Install SDK packages:

```bash
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

Accept licenses if prompted: `yes | sdkmanager --licenses`.

## 2. Generate the Gradle wrapper (one time)

The wrapper jar/scripts are gitignored (supply-chain hygiene). Generate them from the
official Gradle distribution:

```bash
cd /Users/ilyaas/workspace/github.com/IlyaasK/hibreak-pro-irblaster
gradle wrapper --gradle-version 8.9 --distribution-type bin
```

This creates `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar`.
From now on use `./gradlew …`.

## 3. (Optional) Enable the proprietary native codec

The app builds and runs **without** any proprietary binary (it uses the clean-room
`CleanIrCodec`). If you also want the drop-in native shim (full brand DB + 110B learn, using
binaries from **your own** copy of the official APK — not redistributed here):

```bash
mkdir -p app/src/main/jniLibs/armeabi-v7a
cp apk-raw/lib/armeabi/libet_jni_ir_tools.so app/src/main/jniLibs/armeabi-v7a/
cp apk-raw/lib/armeabi/libet_jni_io.so    app/src/main/jniLibs/armeabi-v7a/
```

`jniLibs/` is gitignored — the proprietary blobs never enter the open-source repo.

## 4. Build & install

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

(Enable USB debugging on the Hibreak Pro first: Settings → About → tap Build 7× →
Settings → System → Developer options → USB debugging.)

## SELinux / IR node access (important, Hibreak-specific)

The built-in IR is the kernel char device `/dev/ctrl` (label `u:object_r:ctrl_device:s0`,
mode `crw-rw-rw-`). On a stock Hibreak Pro, SELinux is **Enforcing** and denies the normal
`untrusted_app` domain access to `ctrl_device` — so a stock app gets `EACCES` on connect even
though the node is world-readable. The official app sidesteps this with
`sharedUserId="android.uid.system"` (needs Bigme's platform key) **and** `targetSdkVersion=27`.

The no-root workaround OpenIR uses: **`targetSdk = 27`** in `app/build.gradle.kts`. Apps
targeting ≤27 run in the `untrusted_app_27` SELinux domain, which the vendor policy allows to
access `ctrl_device`. Do **not** raise `targetSdk` above 27 or the IR connection will break
with a permission-denied error. (This is fine for a sideloaded open-source app; it only
matters if you ever target the Play Store, which requires SDK 33+.)

## Troubleshooting

- **JDK version**: AGP 8.5.2 requires JDK 17. `java -version` must show 17; if it shows 26,
  your `JAVA_HOME` is wrong.
- **32-bit libs on arm64**: the official app ships only `armeabi` (32-bit) libs and works on
  the Hibreak Pro, so the device supports 32-bit. We place them in `jniLibs/armeabi-v7a`
  (superset-compatible). If you ever hit "ABI not supported", the device lacks 32-bit support.
- **`/dev/hxd_irda` permission**: the built-in IR node is a character device; the app opens it
  `O_RDWR`. If reads return -1/EPERM, the device node needs to be world-readable on your
  firmware (the official app assumes it is).
