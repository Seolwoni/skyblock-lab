# Reference mods

Other Hypixel SkyBlock mods worth reading, sorted by what we use them for. Researched 2026-11-04.

Rule of thumb: **read code only from repositories whose source is actually public**, and copy code only
when the licence allows it (CC0 / MIT / Apache-2.0). From everything else we take ideas, never code.
Never download or run a jar from a "free / cracked" page — see *Avoid* at the bottom.

## Priority 1 — Fishing

| Mod | Where | Licence | Notes |
|---|---|---|---|
| HypixelClient | github.com/chase-irql/HypixelClient | CC0-1.0 | Fabric 1.21.11. Fishing automation, macros, entity boxes. Same licence as our base: code may be reused. |
| Poseidon | github.com/noname-mods/Poseidon | see repo | Fabric auto-fishing. "!!!" as armor stand **or text display**, bite position snapshot, chat-confirmed creature ownership (SkyHanni model), cap-of-10 alert, despawn alert, not-in-water particle burst, GUI-close lock. |
| skyblock-autofisher | github.com/lyn1dev/skyblock-autofisher | see repo | Fabric 1.21.11. Reel, recast, kill sea creatures. Small and readable. |
| Mraitilities Fishing Macro | github.com/mrailouis/mraitilitiesFishingMacro | see repo | Stridersurfer lava macro: fish up to a set count, then kill them all at once. Archived 2026-04. |
| HypixelQol | github.com/SteinGaming/HypixelQol | MIT | Auto reel + throw. |
| Charmonium | github.com/daddyDinosaur/Charmonium | Apache-2.0 | 1.8.9 multi-macro (fishing, gemstone, rev, sven, foraging). Its credits list is a map of open macro sources. |
| Feesh / Nautilus / SkyOcean | Modrinth / GitHub | see repos | Fishing QoL: hotspot highlight, bobber timer, bait alerts, rare creature alerts, trackers. |
| SkyHanni | github.com/hannibal002/SkyHanni | LGPL | Sea creature detection, Hotspot Radar guesser, trackers. |
| RiccioFishingUtils, MixendMod | GitHub (ChatTriggers, 1.8.9) | see repos | Old fishing helpers, logic only. |

## Priority 2 — Mining

| Mod | Where | Licence | Notes |
|---|---|---|---|
| VeinForge | github.com/GrishMahat/VeinForge | see repo | MightyMiner ported to 1.21.11 with Mojang mappings — the best mining reference for us. |
| MightyMiner | github.com/JellyLabScripts/MightyMiner | custom | Commission, mithril/ore, glacial, route mining, powder (1.8.9: treasure chest solving). Archived 2026-02-25. |
| Charmonium | see above | Apache-2.0 | Gemstone macro. |

Ideas from Polar's public feature list (polarclient.com): route editor (UI + 3D), Discord alerts / remote
control, randomised break scheduler, drill auto-refuel, bazaar/NPC auto-sell, structure finder.
Not taken: its staff "response bot" (made to fool staff checks).

## Later — Farming, slayer, foraging, dungeons

| Mod | Field | Notes |
|---|---|---|
| FarmHelper (JellyLabScripts) | Farming | Custom licence, ideas only. Archived 2026-02-25; its devs moved to Nebula. |
| GardenEase | Farming | Open source farming macro. |
| AIOMacro (JackTYM) | All-in-one | Old all-in-one macro. |
| Odin / OdinClient | Dungeons, Kuudra | Fabric 1.21.9–1.21.11, open source; parts of our mod already come from Odin. |
| Skyblocker | Everything | Fabric **26.2** — same version as us; the best reference for current API usage. |
| Firmament, Skytils, DulkirMod, SkyMelloo, BlackAddons | QoL | UI / info ideas. |
| Neo-Scripts | Scripting | Lua scripting layer for macros. |
| Swofty HypixelSkyBlock | Server | Minestom recreation of SkyBlock — possible source for the private server's missing islands. |

## Closed paid clients (feature lists only)

Nebula (general; FarmHelper/MightyMiner devs), Polar (mining), Taunahi (farming, also combat/foraging/fishing),
Oringo (dungeons + macros). We read their public descriptions and videos only.

## Avoid

These spread malware dressed as free clients (session / Discord token stealers):

- gitea.com/ZAIdev, gitea.com/aaaxxxccc and similar: PolarClient / NebulaClient / OringoClient / Taunahi
  "repos". The source is a renamed bazaar mod; the Download button points at
  `github.com/akkcoc521-blip/honeyrobloxx/releases/...` jars. Checked 2026-11-04.
- "Free / cracked / undetectable" sites: nebulaclient.co, polinexclient.org, beamtechmacros.com, itch.io macro packs.
- Repos that are only a jar with no source (e.g. foraging / gemstone "macro" jars).
- Copies with Plus / FIXED / crack / premium in the name; unofficial "Taunahi" / "Polar" GitHub orgs.
- discord.gg/jellylab (stolen, per the FarmHelper README).
