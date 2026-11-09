package fishing

import dev.marceline.features.impl.fishing.FishingState
import dev.marceline.features.impl.fishing.FishingTransitions
import dev.marceline.features.impl.fishing.FishingTransitions.Kind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** The state machine as a graph: everything reachable, nothing stuck, and the table matches the code. */
class FishingTransitionsTest {
    private val all = Kind.entries.toSet()
    private val own = setOf(Kind.STATE, Kind.LAYER) // without failsafes / events

    @Test fun namesAndOrderUnchanged() {
        assertEquals(listOf("FIND", "TRAVEL", "CAST", "WAIT_BITE", "REEL", "FIGHT", "SELL", "BREAK", "RESUME"), FishingState.entries.map { it.toString() })
        assertEquals((0 until 9).toList(), FishingState.entries.map { it.ordinal })
    }

    @Test fun everyStateIsReachable() {
        assertEquals(emptySet(), FishingTransitions.unreachable(all), "unreachable with every edge")
        // RESUME only comes from a reboot; everything else from a plain start
        assertEquals(setOf(FishingState.RESUME), FishingTransitions.unreachable(own))
    }

    @Test fun everyStateCanStopWithoutFailsafes() {
        assertEquals(emptySet(), FishingTransitions.deadlocked(own), "no path to a stop without the failsafes")
        assertEquals(emptySet(), FishingTransitions.deadlocked(all))
    }

    @Test fun noStateOnlyLoopsOnItself() {
        assertEquals(emptySet(), FishingTransitions.selfOnly(own))
        assertEquals(emptySet(), FishingTransitions.stuck(own), "a state with no way out")
    }

    @Test fun pureFunctionsAreStable() {
        // same input, same answer (no hidden state)
        repeat(3) {
            assertEquals(FishingTransitions.next(FishingState.CAST, own), FishingTransitions.next(FishingState.CAST, own))
        }
        assertTrue(null in FishingTransitions.next(FishingState.CAST, all))
        assertTrue(FishingState.WAIT_BITE in FishingTransitions.next(FishingState.CAST, setOf(Kind.STATE)))
    }

    // ---- the table against the code

    private val dir: File = run {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        val rel = "src/main/kotlin/dev/marceline/features/impl/fishing"
        while (d != null && ! File(d, rel).isDirectory) d = d.parentFile
        File(d ?: fail("fishing sources not found from ${System.getProperty("user.dir")}"), rel)
    }

    private val NOT_RESUME = FishingState.entries.filter { it != FishingState.RESUME }
    private val FISHING = listOf(FishingState.CAST, FishingState.WAIT_BITE, FishingState.REEL, FishingState.BREAK)

    /** Which states a function that changes the state runs in. A new function changing the state must be added. */
    private val runsIn: Map<String, List<FishingState>> = mapOf(
        "FindState" to listOf(FishingState.FIND),
        "TravelState" to listOf(FishingState.TRAVEL),
        "CastState" to listOf(FishingState.CAST),
        "WaitBiteState" to listOf(FishingState.WAIT_BITE),
        "ReelState" to listOf(FishingState.REEL),
        "FightState" to listOf(FishingState.FIGHT),
        "SellState" to listOf(FishingState.SELL),
        "BreakState" to listOf(FishingState.BREAK),
        "ResumeState" to listOf(FishingState.RESUME),
        "tick" to FISHING,                                   // the shared layer: pushed away
        "findHotspot" to listOf(FishingState.FIND),
        "goToFluid" to listOf(FishingState.FIND, FishingState.CAST, FishingState.RESUME),
        "travel" to listOf(FishingState.TRAVEL),
        "arrive" to listOf(FishingState.TRAVEL),
        "sellDone" to listOf(FishingState.SELL),
        "onBreak" to listOf(FishingState.BREAK),
        "resumeTick" to listOf(FishingState.RESUME),
        "escapeLava" to NOT_RESUME,
        "beginResume" to FishingState.entries,
        "start" to emptyList(),                              // the EVENT edge from nowhere
    )

    private val assign = Regex("""\bstate = FishingState\.([A-Z_]+)""")

    /** (unit name, its text) for every top-level function / handler object (and AutoFishing's own functions). */
    private fun units(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val top = Regex("""^(?:internal |private )?(?:fun (?:AutoFishing\.)?([A-Za-z]+)|object ([A-Za-z]+State)\b)""")
        val member = Regex("""^    (?:internal |private |override )*fun ([A-Za-z]+)\(""")
        for (f in dir.listFiles { x -> x.name.endsWith(".kt") }!!.sortedBy { it.name }) {
            var name: String? = null
            val buf = StringBuilder()
            fun flush() { name?.let { out += it to buf.toString() }; buf.setLength(0) }
            for (line in f.readLines()) {
                val t = top.find(line)
                val m = if (f.name == "AutoFishing.kt") member.find(line) else null
                if (t != null || m != null) {
                    flush()
                    name = t?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: m!!.groupValues[1]
                }
                buf.append(line).append('\n')
            }
            flush()
        }
        return out
    }

    @Test fun everyStateChangeInTheCodeIsInTheTable() {
        val missing = ArrayList<String>()
        for ((name, text) in units()) {
            val targets = assign.findAll(text).map { it.groupValues[1] }.toSet()
            if (targets.isEmpty()) continue
            val froms = runsIn[name] ?: run { missing += "$name changes the state but isn't in runsIn"; continue }
            for (t in targets) {
                val to = FishingState.entries.first { it.toString() == t }
                if (froms.isEmpty()) {
                    if (FishingTransitions.edges.none { it.from == null && it.to == to }) missing += "$name: start -> $t"
                    continue
                }
                for (from in froms) if (FishingTransitions.edges.none { it.from == from && it.to == to }) missing += "$name: $from -> $t"
            }
        }
        assertTrue(missing.isEmpty(), "edges in the code but not in FishingTransitions:\n" + missing.joinToString("\n"))
    }

    @Test fun theScanSeesTheHandlers() {
        val names = units().map { it.first }.toSet()
        for (h in listOf("FindState", "CastState", "WaitBiteState", "ReelState", "FightState", "tick", "findHotspot", "goToFluid", "resumeTick"))
            assertTrue(h in names, "scan didn't find $h")
    }
}
