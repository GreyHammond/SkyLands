# SkyRealms

A Paper 26.3 plugin: players **buy a floating island**, pick a biome, and **grow it outward**
by attaching more land: more of a biome they own, or whole new biome regions. Jumping off
the edge drops them into the overworld, landing roughly under their island.

![A Large island grown on a test server](docs/test-render-large.png)

## Build

Requires JDK 25.

```bash
./gradlew build
```

The jar is `build/libs/SkyRealms-0.1.0.jar`. Drop it in `plugins/`.

Optional: **Vault** plus an economy plugin. Without one, everything is free, which is handy for testing.
Set `economy.require-vault: true` to block purchases until an economy exists.

## How it works

### 1. Admins capture biome pieces

Islands are built from **pieces**: spheres of real terrain you grab from your own worlds.

1. Go to a nice spot in a biome (say a cherry grove) and stand in the middle.
2. `/realmadmin preview 16` shows the sphere outline.
3. `/realmadmin capture cherry_grove 16` saves it as `plugins/SkyRealms/pieces/cherry_grove/cherry_grove_1.skyp`.
4. Do this 4–5 times per biome in different spots. Each one becomes a random variant.

Every folder with at least one piece is a biome players can pick.

Tips:
- Only non-air blocks are saved, so pieces overlap and merge cleanly.
- Water and lava on the outer 2-block shell are skipped so they don't pour off the island forever.
- The real biome is saved per block, so grass colour, sky, fog and music match.
- Radius 12–20 works well. A Small island is 32 wide, so its first piece needs radius ≤ 16.
- Pieces are pasted with a random rotation and a little height jitter so repeats don't look copied.

### 2. Players buy and grow

| Command | What it does |
|---|---|
| `/realm buy <tier> <biome>` | Buy an island: small, medium, large, extra_large, continental |
| `/realm home` | Teleport up to your island (has a cooldown) |
| `/realm grow [biome]` | Attach one more piece of a biome you own |
| `/realm biome list` | Show biomes, owned ✔ and prices |
| `/realm biome buy <biome>` | Unlock a new biome; its region attaches to the **outer edge** |
| `/realm upgrade` | Move up a tier: bigger border, room for more pieces, plus bonus land |
| `/realm info` / `prices` | Island details / shop prices |
| `/realm trust` / `untrust <player>` | Let friends build |
| `/realm visit <player>` | Visit an island |
| `/realm sethome` | Set your `/realm home` spot |

Growth rules: each new piece overlaps existing land so the island stays one continuous mass.
Pieces of the same biome cluster together, new biome regions go on the rim, and nothing
grows past the tier's border. When an island is full, players must upgrade.

### 3. Falling to the overworld

- Falling below `transit.depart-y` in the sky world teleports you to `arrive-y` above the overworld.
- You land at the **same X/Z you jumped from**, plus a random offset of up to `max-landing-offset`
  (200) blocks in any direction. `landing-closeness-bias` makes nearby landings more common
  than far ones.
- Your sideways momentum is kept, so you keep falling instead of hovering.
- The landing that ends the fall takes **no fall damage**. Protection ends once you're on the
  ground (or after `protection-seconds`), so it can't be exploited.
- Landing spots on lava, fire, cactus, magma and similar blocks are re-rolled.
- **Elytra works naturally**: deploy it during the fall and glide wherever you like.

Island centres sit `islands.spacing` (2048) blocks apart, so each player's landing zone on
the ground is their own area too.

### Admin

`/realmadmin capture | preview | pieces | give <player> <tier> <biome> | tp <player> | rebuild <player> | reload`

Permissions: `skyrealms.admin` (op), `skyrealms.admin.bypass` (build anywhere in the sky world).

### Multiverse

Not required: the plugin creates the void sky world itself. If you'd rather have Multiverse
manage it, create it with generator `SkyRealms`:

```
/mv create skyrealms_sky normal -g SkyRealms
```

## Config

See `config.yml`: tier footprints, prices and piece counts, biome unlock prices, paste speed,
and transit settings.

## Not built yet (next steps)

- Chest/GUI shop menus (currently commands only)
- A launch pad or updraft in the overworld for going back up (currently `/realm home`)
- Deleting or resetting an island and clearing its blocks
- Saving chest or sign contents in captured pieces (terrain blocks only)
- An island-wide biome map or per-region claim flags
