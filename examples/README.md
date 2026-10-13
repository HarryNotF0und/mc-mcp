# Examples

## Scripts (`run_script`)

| File | What it builds | `origin` |
|---|---|---|
| `scripts/tower.js` | Round stone tower, diameter 15, 30 tall, spiral staircase to a crenellated deck | centre of the ground floor |
| `scripts/arch-bridge.js` | 31-block stone arch bridge along x with railings, lamps and approach stairs | centre of the span at the arch's feet |
| `scripts/cottage.js` | 9 x 7 timber cottage with a gable roof, door and windows | north-west corner of the floor |

Run one through the bridge (paths are relative to where the bridge runs):

```json
{"sourceFile": "examples/scripts/tower.js", "origin": [100, 64, 200], "dryRun": true}
```

The API is documented in [`docs/script-api.md`](../docs/script-api.md) (also served as the MCP
resource `mc://docs/script-api`).

## Blueprints (`place_blueprint`)

`blueprints/cottage.json` is the cottage above, exported with `save_blueprint`. It has no
`origin`, so pass one, and optionally rotate it:

```json
{"blueprint": <contents of cottage.json>, "origin": [100, 64, 200], "rotation": 90}
```
