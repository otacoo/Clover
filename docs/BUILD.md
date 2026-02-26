# Building Clover

This document explains how to build the Clover Android app from source.

## Prerequisites

1. **Latest Android Studio** – download and install from https://developer.android.com/studio.

2. Git (to clone the repository).

## Steps

1. Clone the repository:
   ```sh
   git clone https://github.com/otacoo/Clover.git
   ```

2. Open Android Studio and select **Open an existing project**.\
   Browse to the cloned `Clover` directory and open it. Android Studio will import the
   Gradle project and sync dependencies automatically.

3. Allow Android Studio to download any required SDK components. Use the SDK Manager if you need to
   install any missing platforms or build tools.

4. Once the project sync completes, you can build or run the app:
   - **Build > Make Project** (⌘F9 / Ctrl+F9) to compile.
   - **Run > Run 'app'** to launch on a connected device or emulator.

6. To generate a release APK or bundle, configure signing in `app/build.gradle` or via
   **Build > Generate Signed Bundle / APK...**.
