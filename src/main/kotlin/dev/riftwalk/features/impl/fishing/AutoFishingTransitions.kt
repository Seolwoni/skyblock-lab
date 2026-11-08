package dev.riftwalk.features.impl.fishing

/**
 * Every state change Auto Fishing can make, as data (pure, no Minecraft types). `to = null` is stopping.
 * Read by the tests: every state is reachable, every state can get to a stop, none only loops on itself.
 * When code gains a new `state = ...`, add its edge here; FishingTransitionsTest checks the code against this table.
 */
object FishingTransitions {
    enum class Kind {
        /** the state's own handler (or a function only it calls) */
        STATE,
        /** the per-tick checks every state shares: pushed away, sea creatures, lava */
        LAYER,
        /** failsafes, goal reached, low health, errors: any state can stop */
        FAILSAFE,
        /** outside the tick: start, a reboot moving us */
        EVENT,
    }

    data class Edge(val from: FishingState?, val to: FishingState?, val kind: Kind, val why: String)

    val START: FishingState = FishingState.FIND

    private fun e(from: FishingState?, to: FishingState?, kind: Kind, why: String) = Edge(from, to, kind, why)
    private fun many(froms: List<FishingState>, to: FishingState?, kind: Kind, why: String) = froms.map { e(it, to, kind, why) }

    private val NOT_RESUME = FishingState.entries.filter { it != FishingState.RESUME }
    private val FISHING = listOf(FishingState.CAST, FishingState.WAIT_BITE, FishingState.REEL, FishingState.BREAK)

    val edges: List<Edge> = buildList {
        add(e(null, FishingState.FIND, Kind.EVENT, "start"))
        addAll(many(NOT_RESUME, FishingState.RESUME, Kind.EVENT, "moved by a server reboot (beginResume)"))
        add(e(FishingState.RESUME, FishingState.RESUME, Kind.EVENT, "moved again while coming back (beginResume)"))
        addAll(many(FishingState.entries, null, Kind.FAILSAFE, "failsafe / goal / health / no rod / error"))

        addAll(many(NOT_RESUME, FishingState.TRAVEL, Kind.LAYER, "out of the lava (escapeLava)"))
        addAll(many(NOT_RESUME, null, Kind.LAYER, "stuck in lava (escapeLava)"))
        addAll(many(FISHING, FishingState.TRAVEL, Kind.LAYER, "pushed away from the spot"))
        addAll(many(FISHING, FishingState.FIGHT, Kind.LAYER, "a sea creature to fight"))
        addAll(many(NOT_RESUME, FishingState.FIND, Kind.LAYER, "stall watchdog: nothing happened for too long, start over"))

        add(e(FishingState.FIND, FishingState.TRAVEL, Kind.STATE, "hotspot / water found (findHotspot, goToFluid)"))
        add(e(FishingState.FIND, FishingState.CAST, Kind.STATE, "already at the water (goToFluid)"))
        add(e(FishingState.TRAVEL, FishingState.FIND, Kind.STATE, "lost the spot / arrived somewhere wrong (travel, arrive)"))
        add(e(FishingState.TRAVEL, FishingState.CAST, Kind.STATE, "arrived (arrive)"))
        add(e(FishingState.CAST, FishingState.SELL, Kind.STATE, "inventory nearly full"))
        add(e(FishingState.CAST, FishingState.BREAK, Kind.STATE, "time for a long break"))
        add(e(FishingState.CAST, FishingState.WAIT_BITE, Kind.STATE, "cast"))
        add(e(FishingState.CAST, FishingState.CAST, Kind.STATE, "water right here (goToFluid)"))
        add(e(FishingState.CAST, FishingState.TRAVEL, Kind.STATE, "cast would land on the ground, walk to water (goToFluid)"))
        add(e(FishingState.CAST, null, Kind.STATE, "inventory full"))
        add(e(FishingState.WAIT_BITE, FishingState.CAST, Kind.STATE, "recast (no hook, drift, splash, ground, timeout)"))
        add(e(FishingState.WAIT_BITE, FishingState.FIND, Kind.STATE, "the hotspot moved / ended / a new one appeared"))
        add(e(FishingState.WAIT_BITE, FishingState.REEL, Kind.STATE, "bite"))
        add(e(FishingState.WAIT_BITE, null, Kind.STATE, "the hook keeps landing on the ground"))
        add(e(FishingState.REEL, FishingState.CAST, Kind.STATE, "reeled in"))
        add(e(FishingState.FIGHT, FishingState.FIGHT, Kind.STATE, "still fighting"))
        add(e(FishingState.FIGHT, FishingState.CAST, Kind.STATE, "nothing left to hit"))
        add(e(FishingState.SELL, FishingState.CAST, Kind.STATE, "sold / gave up selling (sellDone)"))
        add(e(FishingState.BREAK, FishingState.CAST, Kind.STATE, "break over (onBreak)"))
        add(e(FishingState.RESUME, FishingState.FIND, Kind.STATE, "back, hotspot fishing (resumeTick)"))
        add(e(FishingState.RESUME, FishingState.TRAVEL, Kind.STATE, "back, walk to the old spot (resumeTick)"))
        add(e(FishingState.RESUME, FishingState.CAST, Kind.STATE, "back, water right here (goToFluid)"))
        add(e(FishingState.RESUME, null, Kind.STATE, "couldn't get back (resumeTick)"))
    }

    /** Where [from] can go using only edges of [kinds] (null in the result = stop). */
    fun next(from: FishingState, kinds: Set<Kind> = Kind.entries.toSet()): Set<FishingState?> =
        edges.filter { it.from == from && it.kind in kinds }.map { it.to }.toSet()

    /** States that can't be reached from [START] (plus anything an event can drop us into). */
    fun unreachable(kinds: Set<Kind> = Kind.entries.toSet()): Set<FishingState> {
        val roots = edges.filter { it.from == null && it.kind in kinds }.mapNotNull { it.to } + START
        val seen = roots.toMutableSet()
        val todo = ArrayDeque(roots)
        while (todo.isNotEmpty()) {
            val s = todo.removeFirst()
            for (n in next(s, kinds)) if (n != null && seen.add(n)) todo += n
        }
        return FishingState.entries.toSet() - seen
    }

    /** States with no path to a stop using only edges of [kinds] (deadlocks). */
    fun deadlocked(kinds: Set<Kind>): Set<FishingState> {
        val canStop = FishingState.entries.filter { null in next(it, kinds) }.toMutableSet()
        var grew = true
        while (grew) {
            grew = false
            for (s in FishingState.entries) if (s !in canStop && next(s, kinds).any { it != null && it in canStop }) { canStop += s; grew = true }
        }
        return FishingState.entries.toSet() - canStop
    }

    /** States whose only way out (with [kinds]) is back to themselves. */
    fun selfOnly(kinds: Set<Kind>): Set<FishingState> =
        FishingState.entries.filter { s -> next(s, kinds).let { it.isNotEmpty() && it.all { n -> n == s } } }.toSet()

    /** States with no way out at all (with [kinds]). */
    fun stuck(kinds: Set<Kind>): Set<FishingState> = FishingState.entries.filter { next(it, kinds).isEmpty() }.toSet()
}
