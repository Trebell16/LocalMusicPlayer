# Trebell Player 🎧

Trebell Player is an elegant, immersive offline audiophile music player. Built completely from the ground up utilizing modern **Jetpack Compose** and **Material Design 3**, it delivers high-performance device folder scanning, gapless playback, custom visualizers, custom playlist management, audio equalizer controls, and an breathtaking "Apple-style" **Liquid Glass** UI.

## Features ✨

- **Automatic Device Scan**: Starts up and seamlessly scans your device folders for audio files, cataloging albums and folders in high-performance local database structures.
- **Continuous Gapless Playback**: Employs an high-performance `PlaybackManager` to control music streams with seamless transitions. Includes custom notification controls.
- **Luxurious Liquid Glass UI**: Captures Apple's pristine translucent depth separation, complete with soft atmospheric specular highlights, drop ambient shadows, and physics-driven liquid touch ripple interactions.
- **Audio Equalizer & Visualizer**: Configure multi-band equalizers, choose presets (Bass Booster, Vocal, Treble, Classical), and watch real-time fluid visualizers reacting to current frequencies.
- **Sleep Timer**: Setup countdown timers to gently fade out music playback.
- **Smart Directory Management**: Organize and view tracks grouped instantly by device folders. Create custom playlists and tag tracks effortlessly.
- **Multi-Theme Experience**: Switch natively between dynamic light and depth-focused dark themes.

---

## Android Studio Instructions (How to run locally) 💻

To import and build Trebell Player directly on your desktop machine or within Android Studio:

### Prerequisites
- **Android Studio** (Hedgehog 2023.1.1 or higher is highly recommended).
- **JDK 17** configured in your workspace environment.
- Android SDK with platform level **34** installed.

### Importing the Project
1. Clone or download this project workspace as a ZIP.
2. Launch Android Studio and click **File > Open...** (or **Open an Existing Project**).
3. Navigate to the folder containing this project and select the root `build.gradle.kts`.
4. Allow Gradle to download necessary SDK dependencies, build configurations, and sync index structures.

### Running the App
- Connect your physical Android device via USB debugging or spin up a virtual Android Virtual Device (AVD).
- Click the **Run** green arrow button (`Shift + F10`) in the toolbar to automatically compile the debug APK and install it onto your terminal.

---

## Technical Stack & Modern Architecture 🛠️

- **Language**: Kotlin 1.9+ with Coroutines and StateFlow.
- **UI Framework**: 100% Jetpack Compose with custom graphics rendering.
- **Persistence Engine**: Room Database with KSP support mapping scanning states.
- **Playback Controls**: Exoplayer backend tailored to gapless playback.
- **Theming**: Centralized Material 3 styling.

Enjoy the ultimate pocket soundscape! 🎶
