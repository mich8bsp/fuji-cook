# Product specification

Fuji Cook has three top-level destinations: Recipes, JPEG Tagger, and RAW Compare.

## Recipes

Recipes are named X-T5 rendering configurations. Names are case-insensitively unique. All recipe values applicable to the selected film simulation are required. Editing a recipe updates its values in place. Recipes may be archived and later permanently deleted. A new installation has an empty library.

Recipes can be imported or exported as JSON, individually or as a collection. A conflicting import requires an explicit choice to overwrite the existing recipe, rename, or skip.

## JPEG Tagger

One original Fujifilm JPEG is selected with Android's document picker. Fuji MakerNotes are decoded, then all active recipes are ranked. The app displays the matching recipe, confidence, and field differences and permits manual override. Tagging writes `recipe:<name>` to IPTC Keywords and XMP `dc:subject`, preserving unrelated metadata and compressed image bytes. A tagged copy is always available. In-place writing is exposed only for storage where safe verified replacement is possible.

## RAW Compare

One phone-resident RAF and several recipes are selected. With the X-T5 in USB RAW CONVERSION mode, the app sends the RAF and applies recipes sequentially. The app stays foreground and shows progress and cancellation. Results are reviewed in a grid and full-screen view. Only selected results are persisted and each is tagged with its recipe name.
