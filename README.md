<p align="center"><img src="src/main/resources/assets/marceline/textures/gui/logo.png" width="112" alt="Marceline emblem"></p>

<h1 align="center">Marceline</h1>

<p align="center">A personal Hypixel SkyBlock mod for Fabric (Minecraft 26.2) — fishing, mining, dungeons and routes in one quiet, dark-red menu.<br>
Built for one player and a private test server. Not released, not distributed.</p>

---

## At a glance

| | |
|---|---|
| **Open** | `/mar` (or `/marceline`) — the settings window; the title screen ("Slow dance with you") and ESC menu have a MARCELINE button too |
| **Tabs** | General · Dungeons (Core / Floor 7 / Routes) · Fishing · Mining · Visual · Misc · Dev |
| **Logs** | one file per game run in `config/Marceline/journal/run_<start>.log` (the newest 10 are kept) |
| **Settings** | `config/Marceline/` (moved over from `config/Riftwalk/` on the first start) |
| **Builds** | every push to `main` is built and tested by CI; the jar, test results and screenshots land on the `ci-log` branch |

The full list of features, tab by tab, with what each one does: **[docs/FEATURES.md](docs/FEATURES.md)**.

## Fishing

Auto Fishing walks to water or lava (or a hotspot), finds a spot by the edge, casts, reads the bite, reels,
fights its own sea creatures (Hyperion from a safe angle, or a weapon), sells junk, takes breaks and writes a
session report. It waits instead of stopping for low mana or low health, comes back after dying, and a stall
watchdog writes down why whenever nothing has changed for a while.

## Mining

Auto Mining mines where you stand — Mithril and Titanium, or the gemstones you tick — choosing the next block the
way a person would and turning with Human Behaviour. In **Commissions** mode it reads your commissions from the tab
list, warps, walks a route you recorded once per area, mines the right blocks, claims with the Royal Pigeon and
repeats. It skips blocks that won't break, leaves blocks to players already on them, and stops on a full inventory.

## Dungeons

Dungeon helpers, Floor 7 tools and routes with Secret Routes built in: map, puzzle solvers, terminals, waypoints,
splits, chest profit and more.

## How the automation behaves

Every automatic look goes through one place (`utils/Humanize.kt`): eased turns, small aim errors, a human reaction
before acting. Failure rate, delay spread and slip chance come from one profile (`HumanProfile`, the Human
Behaviour settings). The maths is kept pure and unit-tested — including distribution tests (long tail, bounds,
same seed → same sequence). Details: [docs/HUMANIZATION.md](docs/HUMANIZATION.md), rules for contributors:
[AGENTS.md](AGENTS.md).

## Look

Warm charcoal panels, wine-red to rose accents and a little antique gold. The emblem is an original red electric
guitar and a bat inside a gold ring. Marceline has its own title screen and ESC menu (Visual → Menu Screens turns
them back to the game's).

## Documents

| File | What's in it |
|---|---|
| [docs/FEATURES.md](docs/FEATURES.md) | every feature and what it does, generated from the code (`python3 tools/gen_features.py`) |
| [docs/ROADMAP.md](docs/ROADMAP.md) | what's done, what's next |
| [docs/HUMANIZATION.md](docs/HUMANIZATION.md) | how automatic input is shaped, and the numbers behind it |
| [docs/FISHING-COMPARISON.md](docs/FISHING-COMPARISON.md) | fishing compared with other mods |
| [docs/REFERENCES.md](docs/REFERENCES.md) | sources and references |
| [AGENTS.md](AGENTS.md) | rules for working on the code |

## Built on

- **NoammAddons** by Noamm — the base this started from (CC0 1.0).
- **Secret Routes** by yourboykyle — included under GPL-3.0 (`LICENSE-SECRETROUTES.txt`).
- **Pretendard** font — SIL Open Font License (`LICENSE-PRETENDARD-OFL.txt`).
- Parts adapted from Odin (`LICENSE-ODIN.txt`) and others, credited in the files they came from.

## License

The original parts are CC0 1.0 (`LICENSE`). Secret Routes code is GPL-3.0.
