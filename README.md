# SkyLands

**Buy your own floating island. Grow it biome by biome. Jump off the edge to fall back to Earth.**

SkyLands (plugin name `SkyRealms`) is a Paper plugin for Minecraft **26.3**. Players buy a
personal sky island in one of five sizes, pick its biome, and expand it outward by attaching
new land, including whole new biome regions. The island sits above the normal overworld:
jump off the edge and you drop out of the sky, landing somewhere below your island.

Everything a player does happens in one chest menu, `/skyland`, so it works the same for
Java players and Bedrock players joining through **Geyser + Floodgate**.

> **Download:** [`docs/skylands.jar`](docs/skylands.jar) (v0.3.0) · Landing page: `docs/index.html` (see [GitHub Pages](#github-pages) below).

---

## Features

- **Five island sizes**: Small 32×32 up to Continental 512×512, each with its own price.
- **Real terrain**: islands are built from spheres of terrain you capture from your own
  worlds. Grass colour, sky, fog and music match the biome they came from.
- **Grows outward**: new land attaches to the edge of the island so it stays one connected
  landmass. New biomes form their own region on the rim.
- **Fall to the overworld**: drop off the edge and land up to 200 blocks from directly below
  your island, in a random direction. The landing that ends the fall does no damage, and
  you can open an elytra mid-fall.
- **Return to Island**: a button in the menu launches you upward on a gust of wind and
  carries you home.
- **Owner-controlled protection**: separate settings for Visitors and Friends: doors,
  buttons, chests, furnaces, workstations, harvesting, farmland trampling, animals and more.
- **Bedrock-friendly GUI**: single-tap chest menus, no typing, and a confirm screen before
  anything costs money.
- **Vault economy**: works with any Vault economy plugin. Without one, everything is free,
  which is handy for testing.
- **Safe deletion**: wipes an island chunk by chunk in the background, without a lag spike.

---

## Quick start

1. Install **Paper 26.3** (Java 25).
2. Download [`docs/skylands.jar`](docs/skylands.jar) (or grab it from the Releases page) and put it in `plugins/`. Optional: **Vault** plus an economy plugin
   (EssentialsX, etc.).
3. Start the server. The plugin creates an empty void world called `skyrealms_sky`.
4. Capture some biome pieces (next section).
5. Players type **`/skyland`**.

### Capturing biome pieces (admins)

Islands are built from *pieces*: spheres of real terrain.

1. Stand in the middle of a nice spot in a biome, for example a cherry grove.
2. `/skylandadmin preview 16` shows the sphere outline with particles.
3. `/skylandadmin capture cherry_grove 16` saves it to
   `plugins/SkyRealms/pieces/cherry_grove/cherry_grove_1.skyp`.
4. Capture 4–5 spots per biome. Each one becomes a variant, picked at random and rotated
   so repeats don't look copied.

Every folder with at least one piece becomes a biome players can choose.

Tips: radius 12–16 works well. A Small island is 32 wide, so its first piece needs
radius ≤ 16. Air isn't saved, so pieces merge cleanly when they overlap. Water and lava on
the outer edge of the sphere are skipped so they don't pour off the island forever.

---

## The `/skyland` menu

```
/skyland                       (aliases: /sl, /realm)
├── Return to Island           shows "Buy an Island" if you don't have one
│   ├── Return to My Island    gust of wind → home (cooldown)
│   ├── Friends' Islands       islands where you're a friend
│   └── Visit Islands          every island open to visitors
├── Manage Island
│   ├── Grow Land              add more of a biome you own
│   ├── Unlock a Biome         add a new biome region at the edge
│   ├── Upgrade                move up to the next size
│   ├── Protection             Visitors · Friends · Island settings
│   ├── Friends                add from online players, tap a head to remove
│   ├── Set Home Here
│   └── Island info
└── Delete Island              two confirmations, then the island is wiped
```

Buying: **Buy an Island → size → biome → confirm**.

### Island sizes (defaults)

| Size | Footprint | Price | Starting pieces | Max pieces |
|---|---|---|---|---|
| Small | 32 × 32 | 5,000 | 1 | 3 |
| Medium | 64 × 64 | 20,000 | 3 | 10 |
| Large | 128 × 128 | 75,000 | 8 | 30 |
| Extra Large | 256 × 256 | 250,000 | 20 | 90 |
| Continental | 512 × 512 | 1,000,000 | 50 | 300 |

Upgrading costs the difference between the two tier prices and adds bonus land. Growing
costs 1,000 per piece. Unlocking a new biome costs 10,000 by default; you can price each
biome separately in the config.

---

## Protection

Owners can always do everything. For everyone else, the owner picks what **Visitors**
(anyone) and **Friends** may do, in *Manage Island → Protection*.

| Setting | Visitors | Friends |
|---|:-:|:-:|
| Doors, trapdoors, fence gates | ✅ | ✅ |
| Break blocks | ❌ | ✅ |
| Place blocks | ❌ | ✅ |
| Harvest grown crops (and replant) | ❌ | ✅ |
| Buttons, levers, pressure plates | ❌ | ✅ |
| Chests, barrels, shulkers, hoppers | ❌ | ✅ |
| Furnaces, smokers, blast furnaces | ❌ | ✅ |
| Workstations (crafting, anvil, enchanting, brewing…) | ❌ | ✅ |
| Redstone tweaks, beds, buckets | ❌ | ✅ |
| Animals, villager trading, riding | ❌ | ✅ |
| Item frames, armor stands, paintings | ❌ | ✅ |
| Other blocks (cake, pots, signs, composters…) | ❌ | ✅ |
| Trample farmland | ❌ | ❌ |

Each screen has **Allow everything**, **Block everything** and **Reset to default** buttons.
Harvesting is separate from breaking, so friends can farm for you without being able to
damage your build.

**Island settings** (apply to the whole island): Open to visitors (on) · PvP (off) ·
Explosion damage (off) · Fire spread (off) · Mob griefing (off).

---

## Falling and returning

- Falling below `transit.depart-y` in the sky world puts you high above the overworld at
  the **same X/Z you jumped from**, plus a random offset of up to 200 blocks.
- Your sideways momentum is kept, so you keep falling instead of hovering.
- Landing spots on lava, fire, cactus or magma are re-rolled.
- The landing that ends the fall does no damage. Normal falls are still dangerous.
- *Return to Island* in the menu launches you upward and carries you home.

Island centres are 2,048 blocks apart, so each player's landing area on the ground is
their own neighbourhood too.

---

## Admin commands

`/skylandadmin` (alias `/sla`), permission `skyrealms.admin` (ops by default)

| Command | What it does |
|---|---|
| `capture <biome> <radius> [name]` | Save the sphere of terrain around you as a piece |
| `preview <radius>` | Show the capture sphere outline |
| `pieces` | List biomes and their variants |
| `give <player> <size> <biome>` | Create an island for free |
| `tp <player>` | Teleport to someone's island |
| `rebuild <player>` | Re-paste every piece of an island |
| `reload` | Reload piece files |

`skyrealms.admin.bypass` lets a player build anywhere in the sky world.

---

## Configuration

`plugins/SkyRealms/config.yml`:

| Section | What it controls |
|---|---|
| `worlds` | Sky world name and the overworld players fall into |
| `islands` | Island spacing, paste height, paste speed (blocks per tick) |
| `tiers` | Footprint, price, starting and max pieces for each size |
| `prices` | Grow price, biome unlock prices (per biome), refund on delete |
| `biome-icons` | Menu icon for each biome |
| `transit` | Fall height, landing spread, landing protection, return cooldown |
| `return` | Strength and duration of the Return to Island launch |
| `economy.require-vault` | Block purchases until a Vault economy exists |

### Multiverse

Not required: the plugin creates the sky world itself. To let Multiverse manage it instead:

```
/mv create skyrealms_sky normal -g SkyRealms
```

---

## Building from source

Requires JDK 25.

```bash
./gradlew build
```

The jar ends up at `build/libs/skylands.jar`.

---

## GitHub Pages

The landing site is a single file at `docs/index.html`. To publish it:
**Settings → Pages → Build and deployment → Deploy from a branch → `main` / `/docs`**.

Note: GitHub Pages on a **private** repository needs a paid GitHub plan (Pro, Team or
Enterprise). On a free plan, the repository has to be public for Pages to work.

---

## Roadmap

- Native Bedrock forms through the Floodgate API (chest menus already work)
- Saving chest and sign contents in captured pieces (terrain only for now)
- Island leaderboards and island levels
