# Auto Fishing vs other fishing macros

Source read on 2026-11-04 (shallow clones, read only):

| Mod | Licence | Size of the fishing code | Shape |
|---|---|---|---|
| **Marceline Auto Fishing** (ours) | — | ~2,300 lines in 7 files | state machine FIND/TRAVEL/CAST/WAIT_BITE/REEL/FIGHT/SELL/BREAK |
| Poseidon (noname-mods) | LGPL-3 | ~1,400 (FishingManager) + catch registry, particles, HUD | IDLE → WAITING → BITING → REELING |
| HypixelClient (chase-irql) | CC0 | ~830 (AutoFisher + FishingCombat) | IDLE → DELAY_BEFORE_CAST → FISHING → DELAY_BEFORE_REEL → COMBAT (SCANNING/REACTING/KILLING/RETURNING) |
| skyblock-autofisher (lyn1dev) | CC0 | ~370 | one tick handler + scheduled clicks |
| Charmonium FishingMacro (1.8.9) | Apache-2.0 | ~600 | THROWING → IN_WATER → FISH_BITE → KILL, anti-AFK AWAY/BACK |

## Topic by topic

### Bite detection
| | How |
|---|---|
| Ours | "!!!" on an armor stand **or text display** within 1.5 blocks of the hook, plus a vanilla hook dive (y-speed < -0.05 after 10 ticks in fluid). |
| Poseidon | "!!!" on any entity (armor stand or text display) in a box around the bobber (configurable radius). |
| HypixelClient | "!!!" on armor stands within 2 blocks, **only after a 2 s grace from the cast** (ignores the splash). Notes the marker reads "?" while waiting. |
| autofisher | any "!!!" armor stand in the world, 1 s cooldown. |
| Charmonium | bobber dips (< -0.04) **and** fishing particles right at the bobber, or the hook caught an entity; only after 2.5 s in water and once the bobber stopped drifting. |

Take: the **"?" / countdown text over the bobber** is a free "hook is in the water" signal (Poseidon reads it for its countdown display).

### Knowing which sea creatures are ours
| | How |
|---|---|
| Ours | 1) catch message that **contains the creature's name**, 2) appeared right after our reel near our hook, 3) comes at us / faces us. |
| Poseidon | **catch line → creature table** (88 lines), then bind the name plate that appears near the bobber; retries the scan 6× every 5 ticks; DOUBLE HOOK! = two. |
| HypixelClient | snapshot every entity loaded **before** the reel; the first new one within 20 blocks in 2.5 s is the target. |

**Gap in ours:** many Hypixel catch lines do not contain the creature's name ("This can't be! The manifestation of death himself!" = Grim Reaper, "What is this creature!?" = Yeti, "Smells of burning. Must be a Fried Chicken." contains it, but plenty don't). A line → creature table fixes this. The CC0 autofisher mod ships 50 exact lines.

### Hook / cast recovery
| | How |
|---|---|
| Ours | recast if the hook never appears (20 ticks), lands on the ground (grounded check, 3 tries), lands beside the hotspot (re-aim up to 8×), or times out. |
| Poseidon | **re-links a desynced bobber** (Hypixel sometimes spawns the hook without linking `player.fishing`; it searches 40 blocks for the FishingHook it owns); 6-tick grace for a flickering bobber; **"not in water" = burst of ≥ 8 `minecraft:dust` particles within 1.6 blocks in 1 s** (after 30 settle ticks, 3 s cooldown); **hook drift check** (bobber moved away from where it settled → it hooked a mob) → recast. |
| HypixelClient | recast if the hook doesn't appear within 3 s. |

**Gaps in ours:** desync re-link, particle "not in water", drift check.

### Timing
| | Before reel | Recast | Creature reaction |
|---|---|---|---|
| Ours | log-normal / Reel-Assist / Human model (drift, fatigue, slips) | 6–34 ticks + hesitation | notice 8–26 ticks, glance, look down, Hyperion |
| Poseidon | configurable min–max ms | watchdog | — |
| HypixelClient | 50–300 ms | 50–300 ms | pre-react 150–270–500 ms then 500–850–1400 ms (triangular) |
| autofisher | 56–129 ms (sum of two uniforms) | 390–980 ms | — |

Ours is the most detailed. Poseidon adds a **GUI-close lock**: after any screen closes, ignore bites for a random 0.75–2.5 s.

### Combat
| | How |
|---|---|
| Ours | Hyperion looking down (lava-safe check, block centre), or Soul Whip / Flaming Flay tracking every frame; immunity wait; cap; rare alert. |
| HypixelClient | walks to the creature, melee with smooth per-frame aim, then **walks back to the exact cast block and angles**. |
| Charmonium | looks straight down (pitch 90) and uses the weapon — same idea as our Hyperion. |
| Mraitilities | fish until N Stridersurfers, then kill them all. |

### Only Poseidon has
Slugfish mode (ignore bites before 20 s / 10 s with the Slug pet), server reboot alert, Golden Fish alert (stops the bot), area from the tab list, live-editable catch table.

### Only we have
Hotspot travel (name tags + radar triangulation + chat shares), lava-safe A* + etherwarp/AOTV hops, throw solver aiming at the hotspot, block-centre standing, whips with frame tracking, auto sell, long breaks, journal, ESP/route visuals, failsafe stops.

## Actions

1. Catch line → creature table (from the CC0 list, names added by us) for the ownership check.
2. Re-link a desynced bobber.
3. "?" / countdown text over the bobber = in the water (stops false ground recasts).
4. "Not in water" particle burst → recast.
5. Hook drift after settling → it hooked a mob → recast.
6. GUI-close lock before reacting to a bite.
7. Slugfish mode, reboot alert (smaller).
