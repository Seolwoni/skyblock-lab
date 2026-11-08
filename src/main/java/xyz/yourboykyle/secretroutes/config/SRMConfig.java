package xyz.yourboykyle.secretroutes.config;
/*
 * Secret Routes Mod - Secret Route Waypoints for Hypixel Skyblock Dungeons
 * Copyright 2025 yourboykyle & R-aMcC & christechs
 *
 * <DO NOT REMOVE THIS COPYRIGHT NOTICE>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import com.google.gson.Gson;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import xyz.yourboykyle.secretroutes.Main;
import xyz.yourboykyle.secretroutes.utils.ConfigUtils;
import xyz.yourboykyle.secretroutes.utils.RoomToggleUtils;
import xyz.yourboykyle.secretroutes.utils.RouteUtils;
import xyz.yourboykyle.secretroutes.utils.SecretSounds;

import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SRMConfig {
    /**
     * Riftwalk: settings live in the Riftwalk config (ClickGUI, "Routes" panel).
     * This stand-in keeps the old HANDLER.load/save/instance calls working.
     */
    public static final Handler HANDLER = new Handler();

    public static final class Handler {
        private final SRMConfig instance = new SRMConfig();

        public SRMConfig instance() {
            return instance;
        }

        public void load() {
        }

        /** Something inside Secret Routes changed the config (color profile, command): show it in the ClickGUI. */
        public void save() {
            try {
                dev.riftwalk.features.impl.routes.SecretRoutes.INSTANCE.pullFromSrm();
            } catch (Throwable ignored) {
            }
        }
    }

    // General
    public boolean modEnabled = true;
    public RouteType routeType = RouteType.ROUTE_FOW;
    public boolean renderComplete = false;
    public boolean wholeRoute = false;
    public int visibleRouteSteps = 1;
    public boolean allSteps = false;
    public boolean allSecrets = false;
    public boolean trackPersonalBests = true;
    public boolean showSecretSplits = false;
    public boolean sendChatMessages = true;

    // Rooms - only disabled rooms are stored, so rooms are enabled by default
    public List<String> disabledRooms = new ArrayList<>();
    public Map<String, RoomRouteProvider> roomRouteTypes = new HashMap<>();

    // F7 Boss
    public boolean pdRoutesEnabled = false;
    public BossRouteMode pdRouteMode = BossRouteMode.ADG;
    public boolean pdHideAfterPhase2 = true;

    // Visual
    public LineType lineType = LineType.LINES;
    public int width = 5;
    public Color lineColor = new Color(236, 72, 153);
    public boolean renderLinesThroughWalls = true;
    public ParticleType particles = ParticleType.FLAME;
    public double particleDensity = 2.0;
    public float filledBoxAlpha = 0.5f;
    public int tickInterval = 1;
    public boolean playerWaypointLine = false;
    public int playerToSecretLineWidth = 4;
    public Color playerToSecretLineColor = new Color(236, 72, 153);
    public boolean playerToEtherwarp = false;
    public int playerToEtherwarpLineWidth = 4;
    public boolean autoSkipEtherwarps = true;
    public float etherwarpDetectionDistance = 1.5f;
    public boolean etherwarpAimSound = false;
    public SoundType etherwarpAimSoundType = SoundType.NOTE_PLING;
    public float etherwarpAimSoundVolume = 0.35f;
    public float etherwarpAimSoundPitch = 1.6f;
    public int etherwarpAimSoundRearmDelay = 400;
    public boolean useEtherwarpColorForLine = true;
    public Color playerToEtherwarpLineColor = new Color(139, 92, 246);

    // Colours and toggles
    public boolean renderEtherwarps = true;
    public boolean etherwarpFullBlock = false;
    public float etherwarpBoxLineWidth = 7f;
    public Color etherWarp = new Color(139, 92, 246);
    public Color secondStepEtherWarp = new Color(96, 64, 160);
    public boolean renderMines = true;
    public boolean mineFullBlock = false;
    public float mineBoxLineWidth = 5f;
    public Color mine = new Color(255, 236, 0, 82);
    public Color secondStepMine = new Color(177, 173, 97);
    public boolean renderSuperboom = true;
    public boolean superboomsFullBlock = false;
    public float superboomBoxLineWidth = 5f;
    public Color superbooms = new Color(255, 0, 0);
    public Color secondStepSuperbooms = new Color(168, 90, 90);

    // Lever settings retain their legacy "interacts" names for saved configs and color profiles.
    public boolean renderInteracts = true;
    public boolean interactsFullBlock = false;
    public float leverBoxLineWidth = 5f;
    public Color interacts = new Color(0, 0, 255);
    public Color secondStepInteracts = new Color(73, 82, 149);
    public boolean renderBonzoStaff = true;
    public boolean bonzoStaffFullBlock = false;
    public float bonzoStaffBoxLineWidth = 5f;
    public Color bonzoStaff = new Color(255, 165, 0);
    public Color secondStepBonzoStaff = new Color(200, 110, 0);

    // Secrets
    public boolean renderSecretsItem = true;
    public float secretBoxLineWidth = 5f;
    public Float itemBoxLineWidth = null;
    public Float batBoxLineWidth = null;
    public boolean secretsItemFullBlock = false;
    public Color secretsItem = new Color(0, 255, 255);
    public Color secondStepSecretsItem = new Color(95, 167, 167);
    public boolean renderSecretIteract = true;
    public boolean secretsInteractFullBlock = false;
    public float interactBoxLineWidth = 5f;
    public Color secretsInteract = new Color(0, 0, 255);
    public Color secondStepSecretsInteract = new Color(73, 82, 149);
    public boolean renderSecretBat = true;
    public boolean secretsBatFullBlock = false;
    public Color secretsBat = new Color(0, 255, 0);
    public Color secondStepSecretsBat = new Color(91, 154, 91);

    // Ender pearls
    public boolean renderEnderpearls = true;
    public boolean enderpearlFullBlock = false;
    public float enderpearlBoxLineWidth = 5f;
    public Color enderpearls = new Color(0, 255, 255);
    public Color secondStepEnderpearls = new Color(95, 167, 167);
    public int pearlLineWidth = 5;
    public Color pearlLineColor = new Color(0, 255, 255);

    // text
    public boolean startTextToggle = true;
    public TextColor startWaypointColor = TextColor.RED;
    public float startTextSize = 1.0f;
    public boolean exitTextToggle = true;
    public TextColor exitWaypointColor = TextColor.RED;
    public float exitTextSize = 1.0f;
    public boolean etherwarpsTextToggle = false;
    public boolean etherwarpNumberingToggle = false;
    public TextColor etherwarpsWaypointColor = TextColor.DARK_PURPLE;
    public float etherwarpsTextSize = 1.0f;
    public boolean minesTextToggle = false;
    public boolean minesEnumToggle = false;
    public TextColor minesWaypointColor = TextColor.YELLOW;
    public float minesTextSize = 1.0f;
    public boolean interactsTextToggle = true;
    public boolean interactsEnumToggle = false;
    public TextColor interactsWaypointColor = TextColor.BLUE;
    public float interactsTextSize = 1.0f;
    public boolean superboomsTextToggle = true;
    public boolean superboomsEnumToggle = false;
    public TextColor superboomsWaypointColor = TextColor.RED;
    public float superboomsTextSize = 1.0f;
    public boolean bonzoStaffTextToggle = true;
    public boolean bonzoStaffEnumToggle = false;
    public TextColor bonzoStaffWaypointColor = TextColor.RED;
    public float bonzoStaffTextSize = 1.0f;
    public boolean enderpearlTextToggle = true;
    public boolean enderpearlEnumToggle = false;
    public TextColor enderpearlWaypointColor = TextColor.AQUA;
    public float enderpearlTextSize = 1.0f;
    public boolean interactTextToggle = true;
    public TextColor interactWaypointColor = TextColor.BLUE;
    public float interactTextSize = 1.0f;
    public boolean itemTextToggle = true;
    public TextColor itemWaypointColor = TextColor.GREEN;
    public float itemTextSize = 1.0f;
    public boolean batTextToggle = true;
    public TextColor batWaypointColor = TextColor.GREEN;
    public float batTextSize = 1.0f;
    public boolean autoCheckUpdates = true;
    public boolean autoDownload = false;
    public boolean autoUpdateRoutes = false;
    public boolean customSecretSound = false;
    public SoundType customSecretSoundType = SoundType.NOTE_PLING;
    public float customSecretSoundVolume = 1.0f;
    public float customSecretSoundPitch = 1.0f;

    // Recording and Dev
    public int recordingHudX = 10;
    public int recordingHudY = 10;
    public Color recordingHudColor = new Color(255, 255, 255);

    // Dev
    public boolean verboseLogging = false;
    public boolean verboseRecording = true;
    public boolean verboseUpdating = true;
    public boolean verboseInfo = false;
    public boolean verboseRendering = false;
    public boolean bridge = false;
    public boolean disableServerChecking = false;
    public boolean forceUpdateDEBUG = false;
    public boolean sendData = true;
    public boolean actionbarInfo = false;
    public boolean verbosePersonalBests = false;

    public String route3ppopkaFileName = "3ppopkaroutes.json";
    public String routeFOWFileName = "fowroutes.json";
    public String copyFileName = "default";
    public int routeNumber = 0;

    public static SRMConfig get() {
        return HANDLER.instance();
    }

    // Unset per-type widths inherit the shared value from older configs and profiles.
    public float effectiveItemBoxLineWidth() {
        return itemBoxLineWidth != null ? itemBoxLineWidth : secretBoxLineWidth;
    }

    public float effectiveBatBoxLineWidth() {
        return batBoxLineWidth != null ? batBoxLineWidth : secretBoxLineWidth;
    }

    public RouteType effectiveRouteType(String roomName) {
        return RoomRouteOverrides.get(roomRouteTypes, roomName).resolve(routeType);
    }

    public BossRouteMode bossRouteMode() {
        return pdRouteMode != null ? pdRouteMode : BossRouteMode.ADG;
    }

    private RoomRouteSettings roomSettings(String roomName) {
        return new RoomRouteSettings(RoomToggleUtils.isRoomEnabled(roomName), RoomRouteOverrides.get(roomRouteTypes, roomName));
    }

    private void setRoomSettings(String roomName, RoomRouteSettings value) {
        RoomToggleUtils.setRoomEnabled(roomName, value.enabled());
        if (roomRouteTypes == null) roomRouteTypes = new HashMap<>();
        RoomRouteOverrides.set(roomRouteTypes, roomName, value.provider());
    }

    public static Screen getScreen(Screen parent) {
        return dev.riftwalk.features.impl.dev.ClickGui.createScreen();
    }

    public enum LineType {
        PARTICLES("Particles"), LINES("Lines"), NONE("None");
        private final String name;

        LineType(String name) {
            this.name = name;
        }
        public Component getDisplayName() {
            return Component.literal(name);
        }
    }

    public enum RouteType {
        ROUTE_3ppopka("3ppopka"), ROUTE_FOW("FlameOfWar");
        private final String name;

        RouteType(String name) {
            this.name = name;
        }
        public Component getDisplayName() {
            return Component.literal(name);
        }
    }

    public enum SoundType {
        MOB_BLAZE_HIT("Blaze Hit", "entity.blaze.hurt"),
        FIRE_IGNITE("Fire Ignite", "item.flintandsteel.use"),
        RANDOM_ORB("Experience Orb", "entity.experience_orb.pickup"),
        RANDOM_BREAK("Item Break", "entity.item.break"),
        MOB_GUARDIAN_LAND_HIT("Guardian Land Hit", "entity.guardian.hurt_land"),
        NOTE_PLING("Note Pling", "block.note_block.pling"),
        ZYRA_MEOW("Zyra Meow", "secretroutesmod:zyra.meow");
        private final String name;
        public final String soundId;

        SoundType(String name, String soundId) {
            this.name = name;
            this.soundId = soundId;
        }
        public Component getDisplayName() {
            return Component.literal(name);
        }
    }

    public enum TextColor {
        BLACK("Black", ChatFormatting.BLACK), DARK_BLUE("Dark Blue", ChatFormatting.DARK_BLUE),
        DARK_GREEN("Dark Green", ChatFormatting.DARK_GREEN), DARK_AQUA("Dark Aqua", ChatFormatting.DARK_AQUA),
        DARK_RED("Dark Red", ChatFormatting.DARK_RED), DARK_PURPLE("Dark Purple", ChatFormatting.DARK_PURPLE),
        GOLD("Gold", ChatFormatting.GOLD), GRAY("Gray", ChatFormatting.GRAY),
        DARK_GRAY("Dark Gray", ChatFormatting.DARK_GRAY), BLUE("Blue", ChatFormatting.BLUE),
        GREEN("Green", ChatFormatting.GREEN), AQUA("Aqua", ChatFormatting.AQUA),
        RED("Red", ChatFormatting.RED), LIGHT_PURPLE("Light Purple", ChatFormatting.LIGHT_PURPLE),
        YELLOW("Yellow", ChatFormatting.YELLOW), WHITE("White", ChatFormatting.WHITE);

        public final ChatFormatting formatting;
        private final String name;

        TextColor(String name, ChatFormatting formatting) {
            this.name = name;
            this.formatting = formatting;
        }
        public Component getDisplayName() {
            return Component.literal(name).withStyle(formatting);
        }
    }

    public enum ParticleType {
        EXPLOSION_NORMAL("Explosion Normal"), EXPLOSION_LARGE("Explosion Large"), EXPLOSION_HUGE("Explosion Huge"),
        FIREWORKS_SPARK("Fireworks Spark"), BUBBLE("Bubble"), WATER_SPLASH("Water Splash"), WATER_WAKE("Water Wake"),
        SUSPENDED("Suspended"), SUSPENDED_DEPTH("Suspended Depth"), CRIT("Crit"), MAGIC_CRIT("Magic Crit"),
        SMOKE_NORMAL("Smoke Normal"), SMOKE_LARGE("Smoke Large"), SPELL("Spell"), INSTANT_SPELL("Instant Spell"),
        MOB_SPELL("Mob Spell"), MOB_SPELL_AMBIENT("Mob Spell Ambient"), WITCH_MAGIC("Witch Magic"),
        DRIP_WATER("Drip Water"), DRIP_LAVA("Drip Lava"), VILLAGER_ANGRY("Villager Angry"),
        VILLAGER_HAPPY("Villager Happy"), TOWN_AURA("Town Aura"), NOTE("Note"), PORTAL("Portal"),
        ENCHANTMENT_TABLE("Enchantment Table"), FLAME("Flame"), LAVA("Lava"), FOOTSTEP("Footstep"),
        CLOUD("Cloud"), REDSTONE("Redstone"), SNOWBALL("Snowball"), SNOW_SHOVEL("Snow Shovel"),
        SLIME("Slime"), HEART("Heart"), BARRIER("Barrier"), WATER_DROP("Water Drop"),
        ITEM_TAKE("Item Take"), MOB_APPEARANCE("Mob Appearance");

        private final String name;

        ParticleType(String name) {
            this.name = name;
        }
        public Component getDisplayName() {
            return Component.literal(name);
        }
    }
}
