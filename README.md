# Serial App Cloner (Android — Kotlin + Jetpack Compose)

A standalone Android app cloner that clones installed apps **without creating a Work Profile**. Every clone gets an automatic serial number (**1, 2, 3...**) and installs directly onto your normal Home Screen as `<App Name> 1`, `<App Name> 2`, `<App Name> 3`.

## Features
- **No Work Profile:** Clones are normal standalone Android apps (`<package>.clone1`, `<package>.clone2`, `<package>.clone3`).
- **Auto Serial Numbering (1, 2, 3...):** Automatically detects existing clones and suggests the next serial number (`Clone #1`, `Clone #2`, `Clone #3`).
- **Direct Home Screen Install:** Uses Android's standard installer so every clone appears right on your Home Screen and App Drawer.
- **Smart Binary Manifest Patcher (`AxmlPatcher.kt`):** Renames the package, updates the home screen label, expands relative component class names, and remaps ContentProvider authorities and custom permissions to prevent install conflicts.
- **On-Device Signing (`ApkClonerEngine.kt` + `KeystoreManager.kt`):** Signs cloned APKs with APK Signature Scheme v1, v2, and v3 using Google's `apksig` library.
- **My Clones Tab:** View all installed serial clones in one place with quick Open and Uninstall buttons.

## How to Build the `.apk`

### Option 1: Build with Android Studio (PC)
1. Unzip `SerialAppCloner-Android.zip`.
2. Open **Android Studio** -> **Open** -> select the `SerialAppCloner` folder.
3. Wait for Gradle Sync to finish.
4. Click **Build** -> **Build Bundle(s) / APK(s)** -> **Build APK(s)**.
5. Copy `app/build/outputs/apk/debug/app-debug.apk` to your phone and install it.

### Option 2: Build Online via GitHub Actions (No PC Required!)
1. Create a free repository on GitHub and upload all files from the unzipped `SerialAppCloner` folder.
2. Open the **Actions** tab in your GitHub repository.
3. Click **Build Android APK** -> **Run workflow**.
4. Once finished (~2 minutes), download **SerialAppCloner-APK** from the Artifacts section and install `app-debug.apk` on your phone.

## Important Android Notes
- On first clone, Android will ask you to allow **"Install unknown apps"** for Serial App Cloner.
- **Compatibility:** Standard single-APK apps clone cleanly. Apps distributed as **Split APKs (App Bundles)** or apps with strict **Play Integrity / signature checks** (such as WhatsApp, banking apps, or protected games) may refuse to run after re-signing due to Android security protections.
