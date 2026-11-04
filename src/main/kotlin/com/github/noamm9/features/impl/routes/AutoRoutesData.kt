package com.github.noamm9.features.impl.routes

//#if CHEAT
import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.KeybindSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ChatUtils.modMessage
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.items.EtherwarpHelper
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.render.Render2D.drawString
import com.github.noamm9.utils.render.world.Render3D.renderBlock
import com.github.noamm9.utils.render.world.Render3D.renderLine
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.awt.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import xyz.yourboykyle.secretroutes.Main
import xyz.yourboykyle.secretroutes.dungeons.detection.DungeonScanner
import xyz.yourboykyle.secretroutes.utils.RoomDirectionUtils
import xyz.yourboykyle.secretroutes.utils.RoomRotationUtils
import xyz.yourboykyle.secretroutes.utils.RotationUtils

// Route data: reading a step from the route JSON, small geometry helpers.

// ------------------------------------------------------------------ route data

internal fun AutoRoutes.standOn(b: BlockPos) = Vec3(b.x + 0.5, b.y + 1.0, b.z + 0.5)

internal fun AutoRoutes.horizontal(a: Vec3, b: Vec3): Double {
    val dx = a.x - b.x
    val dz = a.z - b.z
    return sqrt(dx * dx + dz * dz)
}

internal fun AutoRoutes.parse(o: JsonObject, roomName: String?): AutoRoutes.Step {
    val boss = roomName == "f7boss"
    val dir = RoomDirectionUtils.roomDirection()
    val corner = RoomDirectionUtils.roomCorner()
    fun block(a: JsonArray): BlockPos {
        val rel = BlockPos(a[0].asInt, a[1].asInt, a[2].asInt)
        return if (boss) rel else RoomRotationUtils.relativeToActual(rel, dir, corner)
    }
    fun blocks(key: String) = o.getAsJsonArray(key)?.map { block(it.asJsonArray) } ?: emptyList()
    fun point(a: JsonArray): Vec3 {
        val x = a[0].asDouble
        val y = a[1].asDouble
        val z = a[2].asDouble
        if (boss) return Vec3(x, y, z)
        val cx = corner?.getX() ?: 0.0
        val cz = corner?.getY() ?: 0.0
        return when (dir) {
            "W" -> Vec3(cx - z, y, cz + x)
            "N" -> Vec3(cx - x, y, cz - z)
            "E" -> Vec3(cx + z, y, cz - x)
            else -> Vec3(cx + x, y, cz + z)
        }
    }

    val pearls = ArrayList<Pair<Vec3, MathUtils.Rotation>>()
    val pearlPos = o.getAsJsonArray("enderpearls")
    val pearlAngles = o.getAsJsonArray("enderpearlangles")
    if (pearlPos != null && pearlAngles != null) for (i in 0 until minOf(pearlPos.size(), pearlAngles.size())) {
        val angle = pearlAngles[i].asJsonArray
        val pitch = angle[0].asFloat.coerceIn(- 90f, 90f)
        val yaw = if (boss) angle[1].asFloat else RotationUtils.relativeToActualYaw(angle[1].asFloat, dir)
        pearls += point(pearlPos[i].asJsonArray) to MathUtils.Rotation(yaw, pitch)
    }

    val secret = o.getAsJsonObject("secret")
    return AutoRoutes.Step(
        path = blocks("locations"),
        ethers = blocks("etherwarps").toMutableSet(),
        mines = blocks("mines").toMutableList(),
        tnts = blocks("tnts").toMutableList(),
        levers = blocks("interacts").toMutableList(),
        pearls = pearls,
        secretType = secret?.get("type")?.takeUnless { it.isJsonNull }?.asString,
        secretPos = secret?.getAsJsonArray("location")?.let(::block),
    )
}

//#endif
