# Fuji Cook

Fuji Cook is an offline Android app for managing Fujifilm X-T5 film-simulation recipes, identifying recipes from original JPEG MakerNotes, tagging JPEGs, and rendering one RAF with multiple recipes through the camera's USB RAW converter.

The initial hardware target is a Samsung S23 FE running Android 16 and a Fujifilm X-T5. The app is GPL-3.0-or-later because its camera protocol implementation is derived from the included GPL `rawji` reference.

## Build

Open the repository in Android Studio or run `./gradlew testDebugUnitTest assembleDebug`. No network permission or backend is used.

See [product specification](docs/PRODUCT_SPEC.md), [architecture](docs/ARCHITECTURE.md), [JSON format](docs/JSON_FORMAT.md), and [implementation plan](docs/IMPLEMENTATION_PLAN.md).
