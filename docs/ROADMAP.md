# Marceline — status and roadmap

Updated 2026-11-09 (build 1.1.164).

## Priorities

1. **Fishing** — make it better than the paid clients.
2. **Mining** — engine, Mithril / gemstones and commissions are in; next real-server runs, then powder.
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
- Fishing Check button, one run log per game (config/Marceline/journal), unit tests in CI.
- Low mana / low health waits, death comeback, stall watchdog (10 s), sell waits for each click.

- Teleport travel (in code, not tried on a server yet): AOTE / AOTV blinks and etherwarps planned into the route
  (TravelPlanner on a worker thread, 8 ms budget, mana reserve, soulflow, last 2–3 blocks walked), carried out by
  TravelBlink (stop, item swap, aim with a fresh error, hold, use, settle, swap back), failures in TravelFail
  (miss → one retry at half the error → walk; rubberband / two fails → edge off for the session). Off by default.

Next:
- Two-hour runs → fix what the run logs show (STALL lines, held-item changes, deaths) until a run has no stop.
- Thunder / boss handling; mana from more than the action bar.
- Teleport travel on the server: landing accuracy, rubberbands, mana use; then decide the defaults.

On hold:
- Discord bot (remote /status /stop /start /screenshot /report). Webhook alerts are enough for now.

## Mining (Mining category)

Done (in code, waiting for the first real-server runs):
- Engine: picks the next block (wanted kind, short turn, short reach, HumanProfile mistakes), aims at the visible
  face, holds attack only on the target, human switch delay, pickaxe ability when it says it's ready.
- Modes: Mithril (titanium, blue, green, grey), Gemstone (12 colours), Commissions.
- Robustness: stall watchdog (STALL lines, 3 in 2 min stops), give-up time from each kind's median break time,
  blocks left to players already on them, full inventory stop, failsafes like Auto Fishing.
- Commissions: tab list reader, recorded routes per area (Record Route Key), warp → walk → mine → claim with the
  Royal Pigeon or a command → next.

Next:
- Real-server runs of Mithril and Commissions → fix what the run logs show.
- Several spots per area (walk to the next when one runs dry); death / lobby comeback.
- Powder: hard stone and treasure chests. Then slayer commissions, gemstone routes, nucleus.

## Everything else

- Dungeons: 53 features (Auto Routes / Dungeon / Puzzles / Boss / Chests, F7). Needs real-server testing.
- UI: SkyHanni-style menu with tab groups, own title screen and ESC menu, glass HUD cards, ESP styles.
- Identity: Marceline — emblem (red guitar and bat in a gold ring), warm charcoal / wine / gold palette.
- Code: big files split, server texts in SkyblockText, Safely error logging, docs/.

## Not doing

- Leaked / cracked clients.
