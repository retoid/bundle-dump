# Bundle Dump

A client-side NeoForge mod for Minecraft 1.21.1 that empties a bundle in one click.

## Usage

- **Shift + right-click** a bundle in any inventory screen to unload its contents.
- Right-click (vanilla) still pulls out the last item added, as normal.

Items are unloaded into the inventory the bundle is in:

| Bundle location | Unloads into |
| --- | --- |
| Player inventory | Player main inventory, then hotbar (never armor/offhand) |
| Sophisticated Backpacks storage slot | That backpack's storage slots only |
| Any other container (chest, etc.) | That container only |

Each item stack first tops up matching, non-full stacks, then fills empty slots.
Whatever doesn't fit stays in the bundle.

The mod only sends ordinary inventory clicks, so it works on servers without being installed there.

## Requirements

- Minecraft 1.21.1, NeoForge 21.1+
- Optional: Sophisticated Backpacks
- Bundles are experimental in 1.21.1: the world needs the "Bundle" experiment enabled.

## Building

Requires Java 21.

```
./gradlew build
```

The jar is written to `build/libs/`.

## License

Bundle Dump is licensed under the [GNU Lesser General Public License v3.0](COPYING.LESSER) (LGPL-3.0-only),
which builds on the [GNU General Public License v3.0](COPYING).
