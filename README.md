<p align="center"><img src="src/main/resources/assets/marceline/textures/gui/logo.png" width="128" alt="Marceline emblem"></p>

<p align="center"><img src="docs/assets/wordmark.png" alt="MARCELINE"></p>

<p align="center"><i>Slow dance with you</i></p>

<p align="center">A private Hypixel SkyBlock companion for Fabric · Minecraft 26.2<br>
<sub>Kept for one player and a private test server. Not released, not distributed.</sub></p>

<p align="center">❦</p>

---

### Ⅰ · At a Glance

| | |
|---|---|
| **Open** | `/mar` or `/marceline`, or the **MARCELINE** button on the title screen and in the ESC menu |
| **Chapters** | General · Dungeons (Core, Floor 7, Routes) · Fishing · Mining · Visual · Misc · Dev |
| **Install** | the one Marceline jar, with Fabric API and Fabric Language Kotlin. Assist Core and Assist Path travel inside it |
| **Journal** | one log per game run, in `config/Marceline/journal/` and, live, in `Downloads/Marceline/logs/` |
| **Settings** | `config/Marceline/` |

Every feature, tab by tab, with what it does: **[docs/FEATURES.md](docs/FEATURES.md)**.

---

### Ⅱ · Fishing

Auto Fishing finds water, lava or a hotspot, settles by the edge, casts, reads the bite and reels in. It fights
its own sea creatures (the Hyperion from a safe angle, or a weapon of your choice), sells the junk on your list,
takes its breaks, and writes a report at the end of each session. Low mana or low health makes it wait rather
than stop; a death brings it back to the spot; and when nothing changes for too long, it notes why and begins again.

### Ⅲ · Mining

Auto Mining works the ground you stand on: Mithril and Titanium, or the gemstones you choose, picking the next
block the way a person would. In **Commissions** it reads your tasks from the tab list, warps, walks a route you
recorded once per area, mines what is asked, claims with the Royal Pigeon, and returns for the next.

### Ⅳ · Dungeons

The dungeon map, puzzle solvers, terminals, Floor 7 tools, splits, chest profit and routes, with Secret Routes and
the Assist mods built in.

---

### Ⅴ · The Hand

Every automatic glance passes through one place. A turn speeds up, cruises and eases in along a slight arc, with
each axis keeping its own pace; small slips, pauses and reaction times are drawn from a single profile, the Human
Behaviour settings. One broker decides who holds the view when two features want it at once. The arithmetic is
kept pure and tested, distributions included: a long tail, firm bounds, the same seed giving the same sequence.

Details in **[docs/HUMANIZATION.md](docs/HUMANIZATION.md)**; the house rules in **[AGENTS.md](AGENTS.md)**.

### Ⅵ · The Look

Warm charcoal, wine deepening to rose, a thread of antique gold. Titles are set in Cormorant Garamond, the rest in
Pretendard. The emblem is an original: a red V-bodied guitar and a bat inside a gold ring.

The game itself wears the same colours: the loading screen, the title screen with its drifting bats, the ESC menu,
the settings window, notifications, scoreboard, tab list, chat, tooltips, and the buttons of the game's own
menus. *Visual → Menu Screens* returns the title and ESC menus to the game's.

---

### Ⅶ · Papers

| | |
|---|---|
| [docs/FEATURES.md](docs/FEATURES.md) | every feature and what it does, generated from the code |
| [docs/ROADMAP.md](docs/ROADMAP.md) | what is done, what comes next |
| [docs/HUMANIZATION.md](docs/HUMANIZATION.md) | how automatic input is shaped, and the numbers behind it |
| [docs/FISHING-COMPARISON.md](docs/FISHING-COMPARISON.md) | fishing, set beside other mods |
| [docs/REFERENCES.md](docs/REFERENCES.md) | sources and references |
| [AGENTS.md](AGENTS.md) | rules for working on the code |

### Ⅷ · The Workshop

Each push to `main` is built and tested; the jar, the test results and in-game screenshots are kept on the
`ci-log` branch, and a release is cut for every version. `[gametest]` in a commit message runs the game itself;
`[assist]` rebuilds the bundled Assist mods.

### Ⅸ · With Thanks

- **NoammAddons** by Noamm, the ground this was built on (CC0 1.0).
- **Secret Routes** by yourboykyle, included under GPL-3.0 (`LICENSE-SECRETROUTES.txt`).
- **Cormorant Garamond** and **Pretendard**, under the SIL Open Font License (`LICENSE-CORMORANT-OFL.txt`, `LICENSE-PRETENDARD-OFL.txt`).
- Parts adapted from Odin (`LICENSE-ODIN.txt`) and others, credited where they appear.

The original parts are CC0 1.0 (`LICENSE`); the Secret Routes code is GPL-3.0.

<p align="center">❦</p>
