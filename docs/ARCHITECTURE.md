# Architecture

Fuji Cook is a native Kotlin/Compose application with unidirectional state flow. Compose screens call ViewModels; ViewModels call repository and protocol interfaces; Android storage, Room, JPEG parsing, and USB are isolated implementations.

## Packages

- `model`: stable recipe, matching, and conversion domain types.
- `data`: Room entities/DAO, transactional repository, and JSON interchange.
- `metadata`: streaming JPEG segment handling, TIFF/Fujifilm MakerNote decoding, matching, and IPTC/XMP rewriting.
- `camera`: USB-host PTP transport and Fujifilm d185 profile codec.
- `ui`: three navigation destinations and lifecycle-aware screen state.

Large RAF and JPEG payloads are streamed in bounded chunks. Database edits and file outputs are transactional where the underlying Android document provider permits. The app requests no network access.

The Python references are not executed or bundled. The camera protocol is a Kotlin port with GPL attribution; JPEG behavior is implemented in Kotlin using public tag specifications.
