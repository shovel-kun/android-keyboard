# AGENTS.md

Guidance for coding agents working in this repository.

## Project Overview

FUTO Keyboard is a privacy-focused Android keyboard forked from Android's LatinIME (AOSP keyboard). It stays offline, supports 29 languages, includes voice input via Whisper/GGML, and has an on-device language model (XLM) for next-word prediction. Licensed under FUTO Source First License 1.1.

## Build Commands

```bash
# Debug (development)
./gradlew assembleUnstableDebug

# Release builds
./gradlew assembleUnstableRelease
./gradlew assembleStableRelease

# Play Store AAB bundle
./gradlew bundlePlaystoreRelease

# Run tests (instrumentation tests, requires device/emulator)
./gradlew connectedUnstableDebugAndroidTest

# Unit tests only
./gradlew testUnstableDebugUnitTest
```

Requires Android SDK 35, NDK 28.2.13676358, CMake 3.22+. Repository must be cloned recursively (`git clone --recursive`) for submodules.

## Build Variants

- **unstable**: Default dev flavor - adds `translations/devbuild` strings and the `.unstable` app ID suffix
- **stable**: Production release builds (arm64-v8a only)
- **playstore**: Google Play variant with its own manifest and `java/res-bundle` resources, no update checking, `.playstore` suffix

Release signing requires `keystore.properties` at project root; without it, builds use the debug keystore.

## Architecture

### Source Layout

```
java/src/          Main app source (org.futo.inputmethod.latin)
common/src/        Shared utilities used across flavors
native/jni/        C++ native code (suggestion engine, dictionary, GGML)
voiceinput-shared/ Voice input module (org.futo.voiceinput.shared)
tests/             Android instrumentation tests (androidTest source set)
src/test/          JVM unit tests (run by testUnstableDebugUnitTest)
dictionaries/      Compressed word lists per language (.dict files built from these via makedict/)
libs/              Prebuilt binaries including mozc-release.aar (Japanese IME support) - git submodule
translations/      Localization (core, core-ign, devbuild directories)
tools/             Build scripts (Python keyboard text generation, contributors, swipe model checks) and dicttool
```

### Key Packages

- **`latin`**: Core keyboard split between `LatinIME.kt` (main `InputMethodService` wiring, lifecycle) and `LatinIMELegacy.java` (AOSP-era input processing logic). The `inputlogic/` subdir contains `InputLogic.java` (very large) which handles the core text input state machine.
- **`latin.inputlogic`**: Text input state machine - handles key events, word composition, cursor movement, and coordinates with suggestions
- **`latin.suggestions`**: Suggestion strip interfaces (`SuggestionStrip.kt`); the suggestion bar UI itself is `latin/uix/ActionBar.kt`
- **`latin.xlm`**: On-device language model - adapter training, inference, training data from user dictionaries
- **`latin.uix`**: Compose UI layer - theming (`theme/`, `DynamicThemeProvider`), settings pages (`settings/`), actions (`actions/`)
- **`latin.uix.settings`**: Settings screens built with Compose, organized by feature page
- **`v2keyboard`**: Keyboard layout engine and model - `LayoutEngine.kt` parses keyboard XML layouts, `KeyboardSizingCalculator.kt` computes dimensions, `LayoutManager.kt` loads layouts, `Keyboard.kt` / `BaseKey.kt` define keys and key templates
- **`engine`**: IME abstraction layer - `IMEManager.kt` coordinates the input method, `IMEHelper.kt` provides the interface
- **`voiceinput-shared`**: Voice input via Whisper GGML models - `AudioRecognizer`, `ggml/` bindings, `whisper/` decoder
- **`makedict`**: Dictionary building tool - converts `.combined.gz` wordlists into binary `.dict` files
- **`keyboard`**: AOSP-era keyboard rendering and touch handling - `KeyboardView.java`, `MainKeyboardView.java`, `PointerTracker.java`, `KeyboardSwitcher.java`

### Native Layer (C++)

The `native/jni/` directory contains performance-critical code bridged via JNI:
- **`src/dictionary/`**: Binary dictionary format and lookup
- **`src/suggest/`**: Suggestion algorithm core
- **`src/ggml/`**: GGML inference for voice input
- JNI bindings follow the naming convention `org_futo_inputmethod_<package>_<ClassName>.cpp` (e.g. `org_futo_inputmethod_latin_BinaryDictionary.cpp`)

### Key Patterns

- The app is a standard Android `InputMethodService` (`LatinIME.kt`) with a hybrid Java/Kotlin codebase (legacy AOSP code in Java, new features in Kotlin)
- UI uses Jetpack Compose with Material3, managed through `UixManager` and `DynamicThemeProvider`
- Settings use DataStore preferences (not SharedPreferences)
- Translations are filtered at build time via `translationsWithoutEngValues` in `build.gradle` - source strings live in `translations/` directories
- `preBuild` depends on `updateLocales` (generates keyboard text strings from translations), `prepareImageTaggerModel`, and `prepareOcrModels`; `updateBundleResources` (downloads Play Store resource bundles into `java/res-bundle`) must be run manually
- Dictionary files are built from `.combined.gz` wordlists in `dictionaries/` and loaded as binary `.dict` files at runtime
- Keyboard layouts are loaded from `java/assets/layouts/` (git submodule with `futo-keyboard-layouts` repo) - `LayoutEngine.kt` parses XML layout specs

## Versioning

Version code comes from `git rev-list --first-parent --count master`, version name from `git describe --tags`. Both can be overridden via `VERSION_CODE`/`VERSION_NAME` environment variables.
