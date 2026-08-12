# Recipe JSON format

Version 1 is UTF-8 JSON. Export contains current revisions only.

```json
{
  "schemaVersion": 1,
  "cameraModel": "Fujifilm X-T5",
  "recipes": [{
    "name": "Example",
    "settings": { "filmSimulation": "CLASSIC_CHROME", "color": 2 }
  }]
}
```

`name` and `settings.filmSimulation` are required. Other setting keys are optional. Unknown schema versions, unknown keys, invalid enum values, and out-of-range numbers are rejected rather than silently ignored.
