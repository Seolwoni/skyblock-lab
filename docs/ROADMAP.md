# Riftwalk — status and roadmap

Updated 2026-11-04 (build 1.1.113).

## Priorities

1. **Fishing** — make it better than the paid clients.
2. **Mining** — once the private server has the mining plugins.
3. Farming, slayer, foraging — later, when their plugins exist.

## Fishing (Fishing category)

Done:
- Start anywhere: walks to the nearest water / lava pool, or to a hotspot (name tags, Hotspot Radar
  triangulation, positions shared in chat), follows a hotspot that moves.
- Throw solver, human timing model, frame-synced smooth turns, block-centre standing, lava safety.
- Bite: "!!!" on armor stands or text displays, hook dive; waiting marker; splash particles; drift; re-link.
- Own creatures: catch-line table, time / place score, who it goes for, companions and splits.
- Combat: Hyperion (lava-safe) or Soul Whip / Flaming Flay with in-air tracking, Quick Kill, tag health.
- Cap, rare alert, despawn age, immunity wait, Golden Fish alert / pause, bait alerts.
- Auto sell, long breaks, session goals, reboot comeback, session report, Discord webhook.
- Fishing Check button, fishing.log journal, unit tests in CI.

- Teleport travel (in code, not tried on a server yet): AOTE / AOTV blinks and etherwarps planned into the route
  (TravelPlanner on a worker thread, 8 ms budget, mana reserve, soulflow, last 2–3 blocks walked), carried out by
  TravelBlink (stop, item swap, aim with a fresh error, hold, use, settle, swap back), failures in TravelFail
  (miss → one retry at half the error → walk; rubberband / two fails → edge off for the session). Off by default.

Next:
- Real-server runs → fix what fishing.log / session reports show (repeat 2–3 times).
- Teleport travel on the server: landing accuracy, rubberbands, mana use; then decide the defaults.

On hold:
- Discord bot (remote /status /stop /start /screenshot /report). Webhook alerts are enough for now.

## Mining (waiting for plugins)

Plan: mining engine → ore / mithril → powder (treasure chests) → commissions → gemstone routes → nucleus.
Main reference: VeinForge (MightyMiner on Mojang mappings).

## Everything else

- Dungeons: 53 features (Auto Routes / Dungeon / Puzzles / Boss / Chests, F7). Needs real-server testing.
- UI: SkyHanni-style menu, glass HUD cards, violet scoreboard and Tab list, ESP styles.
- Code: big files split, server texts in SkyblockText, Safely error logging, docs/.

## Not doing

- Leaked / cracked clients.
