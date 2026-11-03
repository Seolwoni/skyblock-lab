package com.github.noamm9.features.impl.routes

//#if CHEAT
import com.github.noamm9.config.types.KeybindSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ChatUtils.modMessage
import com.github.noamm9.utils.WorldUtils
import com.github.noamm9.utils.dungeons.map.core.DoorTile
import com.github.noamm9.utils.dungeons.map.core.DoorType
import com.github.noamm9.utils.dungeons.map.core.RoomState
import com.github.noamm9.utils.dungeons.map.core.RoomType
import com.github.noamm9.utils.dungeons.map.core.UniqueRoom
import com.github.noamm9.utils.dungeons.map.handlers.DungeonScanner
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.Render2D.drawString
import com.github.noamm9.utils.render.world.Render3D.renderBlock
import com.github.noamm9.utils.render.world.Render3D.renderLine
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.awt.Color

/**
 * Runs the whole clear: picks the nearest room that still needs doing, travels there through the doors,
 * lets Auto Routes clear it (mobs + secrets), picks up wither / blood keys, opens doors and ends at Blood.
 * Uses Noamm's dungeon scan for the layout, so the map has to be scanned (Dungeon Map / scanner running).
 */
object AutoDungeon: Feature("Clears the dungeon room by room on its own: goes to the next room, clears it with Auto Routes, grabs keys, opens doors and finishes at Blood.") {
    private val toggleKey by KeybindSetting("Start / Stop Key").withDescription("Press in a dungeon to start the run from the room you are in, press again to stop")
    private val clearRooms by ToggleSetting("Clear Rooms", true).section("Rooms").withDescription("Kills the mobs of every room it visits (Auto Routes, Whole room mode)")
    private val doSecrets by ToggleSetting("Do Secrets", true).withDescription("Follows the room's Secret Routes route when there is one")
    private val solvePuzzles by ToggleSetting("Solve Puzzles", true).withDescription("Goes into puzzle rooms that Auto Puzzles can solve (turn Auto Puzzles and the matching Puzzle Solver on). Other puzzles are skipped")
    private val order by com.github.noamm9.config.types.DropdownSetting("Order", 0, listOf("Clear first", "Blood rush first")).withDescription(
        "Clear first: every room, then Blood. Blood rush first: rooms on the way to Blood (and the keys) first, open Blood, camp, then the rest"
    )
    private val skipTraps by ToggleSetting("Skip Trap Room", false)
    private val skipFairy by ToggleSetting("Skip Fairy Room", true)
    private val roomTimeout by SliderSetting("Time Per Room", 90, 20, 300, 5, " s").withDescription("Moves on if a room takes longer than this")
    private val openDoors by ToggleSetting("Open Wither Doors", true).section("Doors & Keys").withDescription("Picks up wither keys it sees and opens wither doors on the way")
    private val fetchKeys by SliderSetting("Key Search Range", 40, 0, 96, 4, " blocks").withDescription("How far it goes out of its way to pick up a key it can see (0 = only keys on the way)")
    private val endAtBlood by ToggleSetting("Open Blood", true).withDescription("When every other room is done, gets the blood key, opens the blood door and stops inside Blood")
    private val campBlood by ToggleSetting("Camp Blood", true).section("Blood").withDescription("Stays in Blood and kills the blood mobs (Hyperion if you have one) until the Watcher is done")
    private val campTimeout by SliderSetting("Camp Time Limit", 180, 30, 400, 10, " s")
    private val showPath by ToggleSetting("Show Path", true).section("Debug").withDescription("Draws the doors and room it is heading to")
    private val chatInfo by ToggleSetting("Chat Messages", true)

    private enum class State { IDLE, PICK, TRAVEL, DOOR, KEY, ROOM, CAMP, DONE }

    private var state = State.IDLE
    private var target: UniqueRoom? = null
    private var waypoints = ArrayDeque<Waypoint>()
    private val finished = HashSet<String>()
    private var roomTicks = 0
    private var stateTicks = 0
    private var stuckCount = 0
    private var keyStand: ArmorStand? = null
    private var status = "idle"

    /** A point on the way: a door (maybe closed) or a room to walk into. */
    private class Waypoint(val pos: Vec3, val door: DoorTile?, val room: UniqueRoom?)

    override fun init() {
        register<TickEvent.Start> {
            if (toggleKey.isPressed() && mc.gui.screen() == null) {
                if (state != State.IDLE) stop("stopped") else start()
            }
            if (state != State.IDLE) runCatching(::tick).onFailure { stop("error: ${it.message}") }
        }
        register<WorldChangeEvent> { if (state != State.IDLE) stop("world changed") }

        register<RenderWorldEvent> {
            if (! showPath.value || state == State.IDLE) return@register
            val p = mc.player ?: return@register
            var from = p.position().add(0.0, 0.1, 0.0)
            for (w in waypoints) {
                event.ctx.renderLine(from, w.pos.add(0.0, 0.1, 0.0), Color(236, 72, 153), 2, phase = true)
                if (w.door != null) event.ctx.renderBlock(BlockPos(w.door.x, 69, w.door.z), Color(167, 107, 250), fill = false, phase = true, lineWidth = 3)
                from = w.pos.add(0.0, 0.1, 0.0)
            }
            keyStand?.takeIf { it.isAlive }?.let { event.ctx.renderLine(p.eyePosition, it.position().add(0.0, 1.6, 0.0), Color(255, 220, 0), 2, phase = true) }
        }

        hudElement("Auto Dungeon Status", { true }, { state != State.IDLE }) { ctx, example ->
            val lines = if (example) listOf("§dAuto Dungeon §aTRAVEL", "§7→ §fBalcony-4", "§7done §f6  §7keys §8W§f1 §cB§f0")
            else listOf(
                "§dAuto Dungeon §a$state",
                "§7→ §f${target?.name ?: "-"}  §8($status)",
                "§7done §f${finished.size}  §7keys §8W§f${DoorType.WITHER.keys} §cB§f${DoorType.BLOOD.keys}",
            )
            lines.forEachIndexed { i, l -> ctx.drawString(l, 0, i * 10) }
            lines.maxOf { with(com.github.noamm9.utils.render.RenderHelper) { it.width() } } to lines.size * 10
        }
    }

    override fun onDisable() {
        super.onDisable()
        if (state != State.IDLE) stop(null)
    }

    private fun info(msg: String) {
        if (chatInfo.value) modMessage(msg)
    }

    private fun start() {
        if (! LocationUtils.inDungeon || LocationUtils.inBoss) return info("&cStart this in a dungeon (not in the boss room).")
        if (DungeonScanner.uniqueRooms.isEmpty()) return info("&cThe dungeon hasn't been scanned yet. Turn on Dungeon Map and look around for a moment.")
        finished.clear()
        ScanUtils.currentRoom?.takeIf { it.data.type == RoomType.ENTRANCE }?.let { finished += it.name }
        state = State.PICK
        info("&aAuto Dungeon started. Press the key again to stop.")
        AssistBridge.logStart("Auto Dungeon")
    }

    private fun stop(reason: String?) {
        AutoRoutes.forceClear = false
        AutoRoutes.campMode = false
        AutoRoutes.stopRun(null)
        AutoRoutes.releaseAll()
        AssistBridge.logEnd(reason ?: "stopped", "rooms done ${finished.size}")
        state = State.IDLE
        waypoints.clear()
        target = null
        keyStand = null
        if (reason != null) info("&eAuto Dungeon $reason.")
    }

    private fun setState(s: State, why: String) {
        state = s
        stateTicks = 0
        status = why
    }

    // ------------------------------------------------------------------ main loop

    private fun tick() {
        if (mc.gui.screen() != null) return AutoRoutes.releaseAll()
        if (LocationUtils.inBoss) return stop("reached the boss")
        stateTicks++

        // a key in sight is worth a detour (Auto Routes is not using the controls right now)
        if (state == State.PICK || state == State.TRAVEL) findKey()?.let {
            keyStand = it
            setState(State.KEY, "getting ${it.customName?.string}")
        }

        when (state) {
            State.PICK -> pick()
            State.TRAVEL -> travel()
            State.DOOR -> door()
            State.KEY -> key()
            State.ROOM -> room()
            State.CAMP -> camp()
            State.DONE, State.IDLE -> {}
        }
    }

    private fun pick() {
        val here = ScanUtils.currentRoom
        val graph = graph()
        val start = here ?: return setState(State.PICK, "not in a room")
        val (prev, doorOf) = bfs(graph, start)

        val blood = DungeonScanner.uniqueRooms.values.firstOrNull { it.data.type == RoomType.BLOOD }
        val bloodDone = blood == null || blood.name in finished
        if (here?.data?.type == RoomType.BLOOD && ! bloodDone) return startCamp(here)

        if (order.value == 1 && endAtBlood.value && ! bloodDone) {
            // blood rush: straight to Blood when the doors allow it, else the rooms on the way there first
            if (blood != null && blood in prev) return goTo(blood, prev, doorOf)
            val rush = com.github.noamm9.utils.dungeons.map.handlers.DungeonTree.getBloodRush().toSet()
            prev.keys.filter { it in rush && needsDoing(it) }.minByOrNull { distance(prev, it) }?.let { return goTo(it, prev, doorOf) }
        }

        val candidates = prev.keys.filter { needsDoing(it) }
        val next = candidates.minByOrNull { distance(prev, it) }
        if (next != null) return goTo(next, prev, doorOf)

        // everything else is done: Blood
        if (endAtBlood.value && ! bloodDone && blood != null) {
            if (blood in prev && here != blood) return goTo(blood, prev, doorOf)
        }
        setState(State.DONE, "nothing left")
        stop("finished - no rooms left to do")
    }

    private fun needsDoing(r: UniqueRoom): Boolean {
        if (r.name in finished) return false
        when (r.data.type) {
            RoomType.ENTRANCE, RoomType.BLOOD -> return false
            RoomType.PUZZLE -> if (! solvePuzzles.value || ! AutoPuzzles.supports(r.name)) return false
            RoomType.TRAP -> if (skipTraps.value) return false
            RoomType.FAIRY -> if (skipFairy.value) return false
            else -> {}
        }
        val st = r.mainRoom.state
        return st != RoomState.GREEN && st != RoomState.CLEARED
    }

    private fun goTo(room: UniqueRoom, prev: Map<UniqueRoom, UniqueRoom?>, doorOf: Map<Pair<UniqueRoom, UniqueRoom>, DoorTile>) {
        val chain = ArrayList<UniqueRoom>()
        var r: UniqueRoom? = room
        while (r != null) {
            chain.add(r)
            r = prev[r]
        }
        chain.reverse()
        waypoints.clear()
        for (i in 0 until chain.size - 1) {
            val door = doorOf[chain[i] to chain[i + 1]] ?: continue
            waypoints += Waypoint(Vec3(door.x + 0.5, 69.0, door.z + 0.5), door, null)
            waypoints += Waypoint(roomEntry(door, chain[i + 1]), null, chain[i + 1])
        }
        target = room
        stuckCount = 0
        if (waypoints.isEmpty()) setState(State.ROOM, "working").also { beginRoom() }
        else setState(State.TRAVEL, "heading to ${room.name}")
        info("&7Next: &d${room.name} &8(${chain.size - 1} door${if (chain.size == 2) "" else "s"})")
    }

    /** A few blocks past the door, into [room], so the room counts as entered. */
    private fun roomEntry(door: DoorTile, room: UniqueRoom): Vec3 {
        val tile = room.tiles.minByOrNull { (it.x - door.x) * (it.x - door.x) + (it.z - door.z) * (it.z - door.z) } ?: room.mainRoom
        val dx = (tile.x - door.x).coerceIn(- 1, 1)
        val dz = (tile.z - door.z).coerceIn(- 1, 1)
        return Vec3(door.x + 0.5 + dx * 5, 69.0, door.z + 0.5 + dz * 5)
    }

    private fun travel() {
        val w = waypoints.firstOrNull() ?: return setState(State.ROOM, "working").also { beginRoom() }
        if (w.door != null && doorClosed(w.door) && mc.player!!.position().distanceTo(w.pos) < 5.0) {
            return setState(State.DOOR, "opening door")
        }
        when (AutoRoutes.travelTick(w.pos, if (w.door != null) 1.2 else 2.5)) {
            AutoRoutes.Travel.ARRIVED -> {
                waypoints.removeFirst()
                stuckCount = 0
                if (waypoints.isEmpty()) {
                    setState(State.ROOM, "working")
                    beginRoom()
                }
            }
            AutoRoutes.Travel.STUCK -> {
                if (++stuckCount > 5) {
                    // can't get there: leave this room for now
                    target?.let { finished += it.name }
                    info("&eCouldn't get to &f${target?.name}&e, skipping it.")
                    setState(State.PICK, "skipped")
                }
            }
            AutoRoutes.Travel.MOVING -> status = "to ${if (w.door != null) "door" else w.room?.name}"
        }
        if (stateTicks > 20 * 60) {
            target?.let { finished += it.name }
            setState(State.PICK, "travel took too long")
        }
    }

    private fun doorClosed(d: DoorTile): Boolean {
        val b = WorldUtils.getBlockAt(BlockPos(d.x, 69, d.z))
        return b == Blocks.COAL_BLOCK || b == DoorType.BLOOD.source
    }

    private fun door() {
        val w = waypoints.firstOrNull() ?: return setState(State.PICK, "no door")
        val d = w.door ?: return setState(State.TRAVEL, "")
        if (! doorClosed(d)) return setState(State.TRAVEL, "door open")
        val blood = d.type == DoorType.BLOOD || WorldUtils.getBlockAt(BlockPos(d.x, 69, d.z)) == DoorType.BLOOD.source
        val keys = if (blood) DoorType.BLOOD.keys else DoorType.WITHER.keys
        if (keys <= 0 || ! (openDoors.value || blood)) {
            // no key: do other rooms first, the key usually drops from one of them
            target?.let { finished.remove(it.name) }
            waypoints.clear()
            info("&7Need a ${if (blood) "blood" else "wither"} key for that door, doing other rooms first.")
            blockedUntilKey = true
            return setState(State.PICK, "waiting for a key")
        }
        if (stateTicks % 4 == 1) AutoRoutes.lookAndUse(Vec3(d.x + 0.5, 70.0, d.z + 0.5))
        if (stateTicks > 100) {
            target?.let { finished += it.name }
            setState(State.PICK, "door didn't open")
        }
    }

    private var blockedUntilKey = false

    /** A wither / blood key stand within Key Search Range. */
    private fun findKey(): ArmorStand? {
        val p = mc.player ?: return null
        if (fetchKeys.value <= 0) return null
        val r = fetchKeys.value.toDouble()
        return p.level().getEntitiesOfClass(ArmorStand::class.java, p.boundingBox.inflate(r, 30.0, r)) {
            it.isAlive && it.customName?.string.let { n -> n == "Wither Key" || n == "Blood Key" }
        }.minByOrNull { it.distanceToSqr(p) }
    }

    private fun key() {
        val stand = keyStand
        if (stand == null || ! stand.isAlive) {
            keyStand = null
            if (blockedUntilKey) blockedUntilKey = false
            return setState(State.PICK, "key picked up")
        }
        val p = mc.player!!
        val head = stand.position().add(0.0, 1.6, 0.0)
        if (p.eyePosition.distanceTo(head) < 3.5) {
            if (stateTicks % 5 == 1) AutoRoutes.lookAndUse(head)
        }
        else if (AutoRoutes.travelTick(stand.position(), 1.5) == AutoRoutes.Travel.STUCK && ++stuckCount > 5) {
            keyStand = null
            setState(State.PICK, "can't reach the key")
        }
        if (stateTicks > 20 * 30) {
            keyStand = null
            setState(State.PICK, "key took too long")
        }
    }

    private fun beginRoom() {
        roomTicks = 0
        val room = target ?: return
        if (room.data.type == RoomType.BLOOD) return startCamp(room)
        if (room.data.type == RoomType.PUZZLE) return // Auto Puzzles works while Auto Routes is idle; room() waits for the tick
        AutoRoutes.forceClear = clearRooms.value
        if (! doSecrets.value && ! clearRooms.value) {
            finished += room.name
            return setState(State.PICK, "nothing to do")
        }
        if (! AutoRoutes.startRun(quiet = true)) {
            finished += room.name
            setState(State.PICK, "nothing to do in ${room.name}")
        }
    }

    private fun room() {
        val room = target ?: return setState(State.PICK, "")
        if (room.data.type == RoomType.BLOOD) return startCamp(room)
        roomTicks++
        if (room.data.type == RoomType.PUZZLE) {
            val st = room.mainRoom.state
            val solved = st == RoomState.CLEARED || st == RoomState.GREEN
            if (! solved && roomTicks <= roomTimeout.value * 20) {
                status = "solving ${room.name}"
                return
            }
            AutoRoutes.releaseAll()
            finished += room.name
            if (! solved) info("&e${room.name} wasn't solved in time, moving on.")
            return setState(State.PICK, "${room.name} ${if (solved) "solved" else "skipped"}")
        }
        val timeUp = roomTicks > roomTimeout.value * 20
        if (AutoRoutes.isRunning && ! timeUp) {
            status = "clearing ${room.name}"
            return
        }
        if (timeUp) AutoRoutes.stopRun("time per room is up")
        AutoRoutes.forceClear = false
        finished += room.name
        AssistBridge.logEvent("room ${room.name} done (${AutoRoutes.lastStopReason ?: "ok"})")
        setState(State.PICK, "${room.name} done")
    }

    private fun startCamp(blood: UniqueRoom) {
        target = blood
        waypoints.clear()
        if (! campBlood.value) {
            finished += blood.name
            if (order.value == 1) return setState(State.PICK, "blood open")
            setState(State.DONE, "in Blood")
            return stop("finished - you are in Blood")
        }
        AutoRoutes.campMode = true
        AutoRoutes.forceClear = true
        roomTicks = 0
        setState(State.CAMP, "camping Blood")
        info("&dCamping Blood.")
    }

    private fun camp() {
        val blood = target ?: return setState(State.PICK, "")
        roomTicks++
        val st = blood.mainRoom.state
        val done = st == RoomState.CLEARED || st == RoomState.GREEN
        if (! done && roomTicks <= campTimeout.value * 20) {
            status = if (AutoRoutes.campTick()) "killing blood mobs" else "waiting for blood mobs"
            if (! AutoRoutes.isRunning && status.startsWith("waiting")) AutoRoutes.releaseAll()
            return
        }
        AutoRoutes.campMode = false
        AutoRoutes.forceClear = false
        AutoRoutes.releaseAll()
        finished += blood.name
        info(if (done) "&aBlood is done." else "&eBlood camp time is up.")
        if (order.value == 1) setState(State.PICK, "blood done, clearing the rest")
        else {
            setState(State.DONE, "blood done")
            stop("finished - Blood is done")
        }
    }

    // ------------------------------------------------------------------ layout

    /** Rooms connected by doors (two tiles of the same big room are one node). */
    private fun graph(): Map<UniqueRoom, List<Pair<UniqueRoom, DoorTile>>> {
        val g = HashMap<UniqueRoom, MutableList<Pair<UniqueRoom, DoorTile>>>()
        for (tile in DungeonScanner.dungeonList) {
            val door = tile as? DoorTile ?: continue
            val rooms = door.roomTiles.mapNotNull { it.uniqueRoom }
            if (rooms.size != 2 || rooms[0] == rooms[1]) continue
            g.getOrPut(rooms[0]) { ArrayList() } += rooms[1] to door
            g.getOrPut(rooms[1]) { ArrayList() } += rooms[0] to door
        }
        return g
    }

    /**
     * Breadth-first from [start]. Closed wither / blood doors without a key are not crossed,
     * so the result only holds rooms that can be reached right now.
     */
    private fun bfs(g: Map<UniqueRoom, List<Pair<UniqueRoom, DoorTile>>>, start: UniqueRoom): Pair<Map<UniqueRoom, UniqueRoom?>, Map<Pair<UniqueRoom, UniqueRoom>, DoorTile>> {
        val prev = LinkedHashMap<UniqueRoom, UniqueRoom?>()
        val doorOf = HashMap<Pair<UniqueRoom, UniqueRoom>, DoorTile>()
        val q = ArrayDeque<UniqueRoom>()
        prev[start] = null
        q += start
        while (q.isNotEmpty()) {
            val r = q.removeFirst()
            for ((n, d) in g[r].orEmpty()) {
                if (n in prev) continue
                if (doorClosed(d)) {
                    val blood = WorldUtils.getBlockAt(BlockPos(d.x, 69, d.z)) == DoorType.BLOOD.source
                    val have = if (blood) DoorType.BLOOD.keys else DoorType.WITHER.keys
                    if (have <= 0 || (! blood && ! openDoors.value)) continue
                }
                prev[n] = r
                doorOf[r to n] = d
                q += n
            }
        }
        return prev to doorOf
    }

    private fun distance(prev: Map<UniqueRoom, UniqueRoom?>, r: UniqueRoom): Int {
        var n = 0
        var c: UniqueRoom? = r
        while (c != null) {
            c = prev[c]
            n++
        }
        return n
    }
}
//#endif
