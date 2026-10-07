# PridePrism

A Prism-style action logger for Minecraft 1.12.2: records what happens in the world to MySQL or MariaDB, with an inspect mode, a lookup screen, a rollback planner with in-world preview, and undoable rollback batches.

## About

PridePrism writes down who did what, where and when: blocks placed and broken, explosions, lava and fire spreading from the player who started it, chest contents going in and out, deaths, combat, chat, and more. Admins can search that history from a command or a full-screen lookup UI, then roll changes back (or restore them) with a planner that shows exactly what will change before anything does.

The game thread only drops actions into a queue; a background thread writes them in batches every second, so logging never waits on the database. If the database is unreachable, actions stay queued and are retried.

PridePrism was built for the Pride modpack, a 1.12.2 pack of about 750 mods, and works in other Forge or Cleanroom packs. It has optional extras for Immersive Railroading, Traincraft, Mekanism, Immersive Engineering and the RealmCoin economy mod, all loaded by reflection only when those mods are present.

## Features

### What gets logged
Events are caught server-side at the lowest priority, so actions cancelled by other mods are not logged. Each category can be switched off in the config.

| Category | Actions |
|----------|---------|
| Blocks | `break`, `place` (including multi-place), `explode` (with the cause: player, entity, or the mod class that set it off), `fluid` (fluid turning a block into another), `bucket`, `trample` (farmland) |
| Grief tracking | `pour`, `flow`, `ignite`, `burn` (see below) |
| Use | `door`, `trapdoor`, `gate`, `button`, `lever`, `use` (any block with a tile entity) |
| Containers | `item-take`, `item-put`: exact item and count differences between opening and closing a chest or machine |
| Items | `drop`, `pickup`, `craft`, `smelt`, `brew`, `anvil` |
| Deaths | `death` (player deaths, with the full inventory and XP saved unless `keepInventory` is on), `kill` (kills by players, and deaths of "precious" mobs: named, tamed, villagers and other NPCs, golems, farm animals, saved with their full NBT) |
| Chat | `chat`, `command` |
| Combat | `attack`, `hurt` (damage a player takes), `hit` (damage a player deals), with source, target, held item and amount |
| Player | `punch`, `use-item`, `interact`, `consume`, `item-break`, `xp`, `level`, `advancement`, `sleep`, `wake`, `respawn`, `mount`, `dismount`, `tame`, `breed`, `teleport` (ender pearls and dimension changes), `join`, `leave` |
| Menus | `menu-open`, `menu-close`, `menu-button`, `menu-slot`, `key`: reported by the player's client, rate-limited to 40 per second per player. Only keybind names are sent, never typed text. |
| Movement | `move`: player position every few seconds while they move (and whether riding or flying) |
| Trains | `train-trip` plus detailed train statistics (see below) |
| Money | `money`, `web-order`, `web-reward` (when RealmCoin is installed) |
| Machines | `link`, `machine-on`, `machine-off` (Machine Tablet) |
| Rollbacks | `rollback`, `restore` |

Block entries store the block id, metadata and tile entity NBT before and after (gzip-compressed), so chest and machine contents come back with a rollback.

### Lava, water and fire grief tracking
- Emptying a lava or water bucket, or using flint and steel or a fire charge, stamps that spot with the player's name.
- Every block change in the world is watched. Fluid spreading from a stamped spot is logged as `flow` under the same player; fire appearing near their lava or fire is `ignite`; a flammable block next to their fire burning away is `burn`.
- Stamps fade 30 minutes after the last spread. On server start, the last 6 hours of `pour`/`flow`/`ignite` entries are reloaded in the background, so a flood that outlives a restart is still pinned on whoever started it.
- Rollback removes sources first, then flows and fire, then puts burned blocks back.

### Inspect and lookup
- `/pp i` toggles inspect mode: left-click a block to see its history, right-click to see the history of the space on that face. The click is cancelled, so nothing is broken or used.
- `/pp l <filters>` prints the 12 newest matches in chat.

### Search filters
Used by `/pp l`, `/pp rb`, `/pp rs` and the screen. All optional, any order.

| Filter | Meaning |
|--------|---------|
| `p:<name>` | player (or cause, e.g. `#explosion`) |
| `a:<action>[,<action>…]` | action names (see the table above) |
| `r:<blocks>` | radius around you (0–2000) |
| `t:<time>` | since this long ago: `30s`, `10m`, `2h`, `3d`, `1w`, combinations like `1d12h`; a bare number means hours |
| `b:<block>` | block id contains this text (before or after) |
| `i:<item>` | item id contains this text |
| `at:<x>,<y>,<z>` | search around this spot instead of you |
| `box:<x1>,<z1>,<x2>,<z2>` | a rectangle, all heights (up to 4000 blocks per side) |

A search with no player, time or box defaults to a radius of 20 around you. Searches are always within your current dimension. All values are bound as SQL parameters.

### Lookup screen
Opened with the Home key, the prism button in the player inventory, or `/pp gui`.
- Left column: player and block/item text boxes, category chips (Break, Place, Use, Doors, Chests, Items, Chat, Combat, Deaths, Money, Players, Menus), time range (all time, 1 h, 1 d, 1 w), radius (20, 100, 500, everywhere), Search, Log Galaxy, and Settings.
- Right: a timeline of results, paged. Tick rows (shift-click selects a range, "All" ticks the page) and use Teleport, Roll back…, or Restore….
- `/pp gui` accepts `box:`, `at:`, `r:`, `t:` and `p:` words to open the screen on a specific area, which lets a map mod open it on a selection.
- Follows the pack-wide menu theme chosen in PrideCanvas (Themes) when PrideCanvas is installed; otherwise the default Pride colours are used.

### Log Galaxy
A 3D view of the log: categories float on a sphere around a centre. Drag to spin, scroll to zoom, click a dot to fly into it (categories → actions → players → their latest 90 entries), click an entry to read everything about it, right-click or Backspace to go back. Dot size follows the number of entries.

### Rollback planner
- Opened from the lookup screen with the ticked rows, or with the current search when nothing is ticked.
- Options: **Blocks** (including chest/machine contents), **Chests** (items taken or put), **Death items** (give a dead player back their inventory and XP; they must be online), **Pets & mobs** (respawn killed pets, named mobs, villagers and animals as they were, with a new UUID), **Take back from thieves** (stolen items go back in the chest and out of the thief's inventory), **Force over changes**.
- Before running, each spot is checked against the world. A spot someone has changed since is a **conflict** and is skipped unless Force is on.
- The plan shows how many changes of each kind, who made them, the most common blocks/items, conflicts, and entries that can't be rolled back.
- **In-world preview**: see-through boxes on every spot that will change (green comes back, red goes away, yellow is a conflict), visible through walls within 160 blocks.
- Runs as a **batch**: 300 changes per tick with a progress bar above the hotbar. Every batch is saved and can be reversed later with `/pp batch undo <#>`.
- Rollback undoes newest first; restore re-applies oldest first. Entries already rolled back (or already in place) are skipped.

### Machine Tablet and Wireless Transmitter
Two craftable items for remote machine monitoring, with recipes gated to mid-game (they use `circuitAdvanced`):
- **Wireless Transmitter** (2 per craft, stacks to 16): right-click any machine (a tile entity with Forge energy, item or fluid capability, Mekanism redstone control, an Immersive Engineering multiblock, or an "active" state). One transmitter is used up per machine. Breaking the machine removes the link.
- **Machine Tablet**: right-click to open a list of your wireless machines as cards with energy, fluid tank, slots used, running/idle state and whether the chunk is loaded. Mekanism machines (via redstone control mode) and Immersive Engineering multiblocks (via the computer-control switch) can be switched off and on from the tablet. Holders of `prideprism.seeall` see every player's machines.

Recipes:
- Wireless Transmitter: ender pearl / redstone dust, advanced circuit, redstone dust / gold ingot, quartz, gold ingot.
- Machine Tablet: gold ingot, glass pane, gold ingot / gold ingot, diamond, gold ingot / advanced circuit, eye of ender, advanced circuit.

### Train statistics (Immersive Railroading, Traincraft)
Once a second, every loaded car is read: distance (odometer), speed, grade, deceleration, tractive effort, boiler and engine temperature, fuel burned, overheats, freight ton-km, passenger-km and consist length. A moving stretch between stops of 20 s or more is a trip (logged when at least 100 m). Drivers get their own totals and favourite stock. Written every 30 s to `pp_trains`, `pp_train_drivers` and `pp_train_trips`.

### Website hooks
PridePrism maintains a few extra tables that a website can read and write. The site and the game never talk directly; they share the database.
- `pp_players`: per-player stats (play time, blocks broken/placed, deaths, kills, crafted, chats, things used, items taken/put, position, RealmCoin balance), updated every 30 s.
- `pp_links`: the site writes a code for an account; the player types `/link <code>` in game within 15 minutes to tie the two together.
- `pp_inbox`: the site writes orders or rewards (item id, meta, amount, NBT, price, coin reward, title). The game delivers them when the player is online, checking every 5 seconds. Each row is claimed once. Orders are paid with in-game RealmCoin, never real money; without RealmCoin, paid orders fail. Full inventory drops items at the player's feet.
- With RealmCoin installed: `pp_money` (every transaction), `pp_accounts` (every account, every minute) and `pp_prices` (the item price list, every 10 minutes). Every coin movement is also logged as a `money` action.

### In-game settings
The ⚙ Settings button opens a panel with every config option, editable live (requires `prideprism.config`). Values whose names contain "password", "secret" or "token" are never sent to the client.

### Self-test
If a file named `prideprism-selftest` exists in the server folder, the first player to join triggers a test of the rollback engine against the real world (block rollback, conflict detection, chest theft and more), logged as `PRIDEPRISM ROLLBACK PASS` or `FAIL`. Use a throwaway test world.

## Commands

| Command | Who | Effect |
|---------|-----|--------|
| `/pp gui [filters]` | op | Open the lookup screen, optionally preset with `box:`, `at:`, `r:`, `t:`, `p:`. |
| `/pp i` | op | Toggle inspect mode. |
| `/pp l [filters]` | op | Look up the 12 newest matching actions in chat. |
| `/pp rb [filters]` | op | Roll back matching changes (blocks, chest items, death inventories, pets). Conflicts are skipped. |
| `/pp rs [filters]` | op | Restore matching changes that were rolled back. |
| `/pp batch` | op | Show the last 15 rollback batches. |
| `/pp batch undo <#>` | op | Reverse a whole batch. |
| `/pp status` | op | Database connection status and number of actions waiting to be written. |
| `/link <code>` | everyone | Link your website account using a code from the site. |

`/prideprism` is an alias of `/pp`. The `/pp` command requires op level 2; the screen and its network actions check `prideprism.admin`.

### Key bindings

| Key | Action |
|-----|--------|
| Home | Open PridePrism (category "PridePrism") |

## Permissions

Registered with the Forge permission API. Without a permissions mod, all default to op level 2.

| Node | Grants |
|------|--------|
| `prideprism.admin` | lookup screen, row teleport/undo/redo, rollback planner |
| `prideprism.seeall` | see and switch every player's wireless machines, not only your own |
| `prideprism.config` | change PridePrism's settings in game |

## Config

File: `config/prideprism.cfg`

### `database`

| Key | Default | Effect |
|-----|---------|--------|
| `url` | `jdbc:mariadb://127.0.0.1:3306/prideprism` | MySQL or MariaDB address (`jdbc:mariadb://host:port/database`). |
| `user` | `prideprism` | Database user. |
| `password` | *(empty)* | Database password. |

### `log`

| Key | Default | Effect |
|-----|---------|--------|
| `blocks` | `true` | Placing, breaking, explosions, fluids, buckets, trampling, lava/fire grief tracking. |
| `use` | `true` | Doors, trapdoors, gates, buttons, levers, blocks that open a screen. |
| `containers` | `true` | Items going in and out of chests and machines. |
| `items` | `true` | Drops, pickups, crafting, smelting, brewing, anvils. |
| `deaths` | `true` | Player deaths and kills. |
| `chat` | `true` | Chat and commands. |
| `combat` | `true` | Every hit a player makes or takes. |
| `player` | `true` | Eating, item use, punching blocks, clicking mobs, XP, levels, advancements, sleep, respawn, mounts, taming, breeding, ender pearls, tools breaking. |
| `menus` | `true` | Screens opened/closed, buttons and slots clicked, keybinds pressed (names only). |
| `movement` | `true` | Player positions while they move. |
| `movementSeconds` | `10` | How often movement is logged (1–600). |
| `trains` | `true` | Immersive Railroading and Traincraft statistics. |

## Database tables

All tables are created automatically on server start (InnoDB, utf8mb4).

| Table | Contents |
|-------|----------|
| `prideprism_actions` | every logged action |
| `prideprism_batches` | rollback/restore history |
| `pp_players`, `pp_links`, `pp_inbox` | website hooks |
| `pp_trains`, `pp_train_drivers`, `pp_train_trips` | train statistics |
| `pp_money`, `pp_accounts`, `pp_prices` | RealmCoin data (only with RealmCoin) |

PrideGuard, if installed, also writes its `pg_flags` table through PridePrism's connection.

## Requirements

- Minecraft 1.12.2
- Forge 14.23.5.2860 or newer, or Cleanroom
- A MySQL or MariaDB server. The MariaDB JDBC driver is bundled in the jar.
- Install on the server and on clients (the mod adds items; the screen and key binding are client-side).
- Optional: RealmCoin, Immersive Railroading, Traincraft, Mekanism, Immersive Engineering, a mod providing `circuitAdvanced` for the recipes.

## Install

1. Create a database and user, for example:
   ```sql
   CREATE DATABASE prideprism CHARACTER SET utf8mb4;
   CREATE USER 'prideprism'@'localhost' IDENTIFIED BY '<your password>';
   GRANT ALL ON prideprism.* TO 'prideprism'@'localhost';
   ```
2. Put `PridePrism-1.12.2-<version>.jar` in the `mods` folder of the server and every client.
3. Start the server once, then set `url`, `user` and `password` in `config/prideprism.cfg` and restart.
4. Check the connection with `/pp status`.

## Building

```
./gradlew build
```

The jar is written to `build/libs/`. The project targets Java 8 (ForgeGradle 3, MCP snapshot `20171003-1.12`). Place `libs/mariadb-java-client.jar` (bundled into the jar) and `libs/realmcoin-dev.jar` (compile-only) before building.

## License

MIT License. © 2026 crunkazcanbe.

The bundled MariaDB Connector/J is licensed under LGPL-2.1.

## Compile-only jars

The build compiles against these jars in `libs/` (other authors' mods / APIs). They are not included in this repo — get them from their official pages and drop them in `libs/` before building:

- `mariadb-java-client.jar`
- `realmcoin-dev.jar`

## Credits

Made with [Claude Code](https://claude.com/claude-code) and [Blockbench](https://www.blockbench.net).

The bundled MariaDB Connector/J is licensed under LGPL-2.1.
