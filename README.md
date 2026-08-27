# Fuji Cook

Fuji Cook is an offline Android app for managing Fujifilm film-simulation recipes, identifying recipes from original JPEG MakerNotes, tagging JPEGs, and rendering one RAF with multiple recipes through the camera's USB RAW converter.

<div>
  <img src="screenshots/screenshot.jpg" alt="Screenshot" width="300">
  <img src="screenshots/screenshot1.jpg" alt="Screenshot" width="300">
  <img src="screenshots/screenshot2.jpg" alt="Screenshot" width="300">
</div>

## Build

https://github.com/mich8bsp/fuji-cook/releases/download/v0.0.1/fuji-cook.apk

Open the repository in Android Studio or run `./gradlew testDebugUnitTest assembleDebug`. No network permission or backend is used.

See [product specification](docs/PRODUCT_SPEC.md), [architecture](docs/ARCHITECTURE.md), [JSON format](docs/JSON_FORMAT.md), and [implementation plan](docs/IMPLEMENTATION_PLAN.md).
