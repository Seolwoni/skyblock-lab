"""Generates (1) a YACL-free SRMConfig.java and (2) a NoammAddons Feature exposing every Secret Routes setting.

Usage: python3 gen_sr.py <original SRMConfig.java> <out SRMConfig.java> <out SecretRoutes.kt>
"""
import re
import sys

src_path, out_java, out_kt = sys.argv[1:4]
src = open(src_path, encoding='utf-8').read()
src = re.sub(r'^\s*//#(if FABRIC|endif)\s*$\n', '', src, flags=re.M)

# ---------------------------------------------------------------- fields
start = src.index('    // General')
end = src.index('    public static SRMConfig get()')
field_block = src[start:end]
fields = []  # (type, name, default_expr)
for m in re.finditer(r'^\s*public\s+([\w<>, ]+?)\s+(\w+)\s*=\s*(.+?);\s*$', field_block, re.M):
    fields.append((m.group(1).strip(), m.group(2), m.group(3).strip()))

# ---------------------------------------------------------------- YACL labels
gs = src.index('    public static Screen getScreen(Screen parent)')
ge = src.index('    public enum LineType')
screen = src[gs:ge]
NAME = r'\.name\(Component\.literal\("([^"]*)"\)\)'
events = []
for m in re.finditer(r'ConfigCategory\.createBuilder\(\)\s*' + NAME, screen):
    events.append((m.start(), 'cat', m.group(1)))
for m in re.finditer(r'OptionGroup\.createBuilder\(\)\s*' + NAME, screen):
    events.append((m.start(), 'group', m.group(1)))
for m in re.finditer(r'Option\.<[\w.]+>createBuilder\(\)', screen):
    chunk = screen[m.start(): screen.index('.build()', m.start())]
    events.append((m.start(), 'opt', chunk))
for m in re.finditer(r'TextConfigurationScreen\.button\("([^"]+)",(.*?)\)\)\)', screen, re.S):
    events.append((m.start(), 'text', (m.group(1), m.group(2))))
events.sort()

meta = {}  # field -> dict(name, desc, section, rng, step)
cat = group = None
for _, kind, val in events:
    if kind == 'cat':
        cat, group = val, None
    elif kind == 'group':
        group = val
    elif kind == 'opt':
        f = re.search(r'config\.(\w+)\s*=\s*v', val)
        if not f:
            continue
        n = re.search(NAME, val)
        d = re.search(r'OptionDescription\.of\(Component\.literal\("([^"]*)"\)\)', val)
        r = re.search(r'\.range\(([^,]+),\s*([^)]+)\)', val)
        s = re.search(r'\.step\(([^)]+)\)', val)
        meta[f.group(1)] = dict(
            name=n.group(1) if n else None, desc=d.group(1) if d else None,
            section=group or cat, rng=(r.group(1), r.group(2)) if r else None, step=s.group(1) if s else None)
    elif kind == 'text':
        comp, body = val
        fs = re.findall(r'config\.(\w+)\s*=\s*v', body)
        for fname in fs:
            low = fname.lower()
            if 'numbering' in low or 'enumtoggle' in low:
                label = 'Text Numbering'
            elif low.endswith('texttoggle'):
                label = 'Show Text'
            elif 'color' in low:
                label = 'Text Color'
            elif 'size' in low:
                label = 'Text Size'
            else:
                label = fname
            meta[fname] = dict(name=label, desc=f'{comp} waypoint text', section=group or cat, rng=(
                '0.5f', '3.0f') if 'size' in low else None, step='0.1f' if 'size' in low else None)

# Options defined as variables before the categories (sounds)
for f in fields:
    n = f[1]
    if n.startswith('customSecretSound'):
        meta.setdefault(n, {}).setdefault('section', 'Secret Sound')
    if n.startswith('etherwarpAimSound'):
        meta.setdefault(n, {}).setdefault('section', 'Etherwarp Aim Sound')
for n, label, lo, hi, st in [('customSecretSoundType', 'Sound', None, None, None),
                             ('customSecretSoundVolume', 'Volume', '0.0f', '5.0f', '0.1f'),
                             ('customSecretSoundPitch', 'Pitch', '0.5f', '2.0f', '0.1f'),
                             ('etherwarpAimSoundType', 'Aim Sound', None, None, None),
                             ('etherwarpAimSoundVolume', 'Aim Sound Volume', '0.0f', '5.0f', '0.1f'),
                             ('etherwarpAimSoundPitch', 'Aim Sound Pitch', '0.5f', '2.0f', '0.1f')]:
    m = meta.setdefault(n, {})
    m.setdefault('name', label)
    if lo and not m.get('rng'):
        m['rng'] = (lo, hi)
        m['step'] = st

SKIP = {'modEnabled', 'disabledRooms', 'roomRouteTypes', 'forceUpdateDEBUG', 'sendData',
        'autoCheckUpdates', 'autoDownload', 'routeNumber'}

# ---------------------------------------------------------------- SRMConfig.java
header = src[:src.index('public class SRMConfig {')]
header = re.sub(r'^import dev\.isxander.*\n', '', header, flags=re.M)
header = header.replace('import net.minecraft.client.gui.screens.Screen;\n', 'import net.minecraft.client.gui.screens.Screen;\nimport com.google.gson.Gson;\n')
body_fields = re.sub(r'^\s*@SerialEntry\s*\n', '', field_block, flags=re.M)
helpers = src[end:gs]
enums = src[ge:]
enums = enums.replace(' implements NameableEnum', '')
enums = re.sub(r'\n\s*@Override\n(\s*public Component getDisplayName)', r'\n\1', enums)

java = header + '''public class SRMConfig {
    /**
     * Riftwalk: settings live in the NoammAddons config (ClickGUI, "Routes" panel).
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

''' + body_fields + helpers + '''    public static Screen getScreen(Screen parent) {
        return dev.riftwalk.features.impl.dev.ClickGui.createScreen();
    }

''' + enums
open(out_java, 'w', encoding='utf-8').write(java)

# ---------------------------------------------------------------- Kotlin feature
ENUMS = {'RouteType': 'SRMConfig.RouteType', 'LineType': 'SRMConfig.LineType', 'SoundType': 'SRMConfig.SoundType',
         'TextColor': 'SRMConfig.TextColor', 'ParticleType': 'SRMConfig.ParticleType', 'BossRouteMode': 'BossRouteMode'}


def space_caps(s):
    return re.sub(r'(?<!^)([A-Z])', r' \1', s).replace('Iteract', 'Interact').strip().title()


def num(v, t):
    v = v.strip().rstrip('fFdD')
    x = float(v)
    if t == 'Int':
        return str(int(round(x)))
    if t == 'Float':
        return repr(x) + 'f'
    return repr(x)


def kstr(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$') + '"'


decls, pushes, pulls = [], [], []
used_names = {}
for jtype, fname, default in fields:
    if fname in SKIP:
        continue
    m = meta.get(fname, {})
    label = m.get('name') or space_caps(fname)
    section = m.get('section')
    desc = (m.get('desc') or '').replace('\\n', ' ') or None
    if label in ('Enabled', 'Color', 'Second Step Color', 'Filled', 'Box Line Width', 'Line Width', 'Line Color',
                 'Type', 'Show Text', 'Text Color', 'Text Size', 'Text Numbering', 'Volume', 'Pitch') and section:
        label = f'{section} {label}'
    used_names[label] = used_names.get(label, 0) + 1
    if used_names[label] > 1:
        label = f'{label} {used_names[label]}'
    v = 's_' + fname
    d = f'D.{fname}'
    if jtype == 'boolean':
        ctor = f'ToggleSetting({kstr(label)}, {d})'
        push = f'C().{fname} = {v}.value'
        pull = f'{v}.value = C().{fname}'
    elif jtype in ('int', 'float', 'double', 'Float'):
        kt = {'int': 'Int', 'float': 'Float', 'Float': 'Float', 'double': 'Double'}[jtype]
        rng = m.get('rng') or {'Int': ('0', '1000'), 'Float': ('0', '10'), 'Double': ('0', '10')}[kt]
        step = m.get('step') or ('1' if kt == 'Int' else '0.1')
        dd = f'({d} ?: D.secretBoxLineWidth)' if jtype == 'Float' else d
        ctor = f'SliderSetting({kstr(label)}, {dd}, {num(rng[0], kt)}, {num(rng[1], kt)}, {num(step, kt)})'
        push = f'C().{fname} = {v}.value'
        pull = f'{v}.value = C().{fname}' + (' ?: C().secretBoxLineWidth' if jtype == 'Float' else '')
    elif jtype == 'Color':
        ctor = f'ColorSetting({kstr(label)}, {d}, true)'
        push = f'C().{fname} = {v}.value'
        pull = f'{v}.value = C().{fname} ?: {d}'
    elif jtype in ENUMS:
        e = ENUMS[jtype]
        ctor = f'DropdownSetting({kstr(label)}, {d}.ordinal, {e}.entries.map {{ it.displayName.string }})'
        push = f'C().{fname} = {e}.entries[{v}.value.coerceIn(0, {e}.entries.lastIndex)]'
        pull = f'{v}.value = (C().{fname} ?: {d}).ordinal'
    elif jtype == 'String':
        ctor = f'TextInputSetting({kstr(label)}, {d} ?: "")'
        push = f'C().{fname} = {v}.value'
        pull = f'{v}.value = C().{fname} ?: ""'
    else:
        continue
    chain = ''
    if section:
        chain += f'.section({kstr(section)})'
    if desc:
        chain += f'.withDescription({kstr(desc)})'
    chain += f'.jsonName({kstr(fname)}).onChange {{ {push.replace(v + ".value", "it")} }}'
    decls.append(f'    private val {v} by {ctor}{chain}')
    pushes.append(f'        {push}')
    pulls.append(f'        {pull}')

kt = '''// Generated by tools/gen_sr.py from Secret Routes' SRMConfig - edit the generator, not this file.
package dev.riftwalk.features.impl.routes

import dev.riftwalk.config.types.*
import dev.riftwalk.event.EventBus
import dev.riftwalk.event.impl.TickEvent
import dev.riftwalk.features.Feature
import xyz.yourboykyle.secretroutes.config.BossRouteMode
import xyz.yourboykyle.secretroutes.config.SRMConfig
import xyz.yourboykyle.secretroutes.utils.ConfigUtils
import xyz.yourboykyle.secretroutes.utils.RouteUtils
import xyz.yourboykyle.secretroutes.utils.SecretSounds

/** Secret Routes (by yourboykyle & co, GPL-3.0) inside NoammAddons' ClickGUI. The toggle is the mod's on/off switch. */
object SecretRoutes: Feature("Secret route waypoints for every dungeon room (Secret Routes Mod).", toggled = true) {
    private val D = SRMConfig()
    private fun C() = SRMConfig.get()
    private var synced = false
    private var lastEnabled: Boolean? = null

    private val updateRoutes by ButtonSetting("Download Latest Routes") { Thread { RouteUtils.checkRoutesFiles() }.start() }
        .section("General").withDescription("Downloads the newest route files from GitHub (overwrites routes.json and pearlroutes.json)")
    private val previewSecretSound by ButtonSetting("Preview Secret Sound") {
        SecretSounds.preview(C().customSecretSoundType, C().customSecretSoundVolume, C().customSecretSoundPitch)
    }.section("Secret Sound")
    private val saveProfile by ButtonSetting("Save Color Profile") { ConfigUtils.writeColorConfig(C().copyFileName) }
        .section("Color Profiles").withDescription("Saves the current colors under the profile name")
    private val handOffSetting by ToggleSetting("Hand Off to Noamm Waypoints", true).section("General")
        .withDescription("While a route has steps left in your room, Noamm's Dungeon Waypoints secret boxes are hidden so only the route shows. When the route is done, or a room has no route, Noamm shows every secret that is still left")
    val handOff get() = handOffSetting.value
    private val loadProfile by ButtonSetting("Load Color Profile") { ConfigUtils.loadColorConfig(C().copyFileName); pullFromSrm() }
        .section("Color Profiles").withDescription("Loads the profile with the name below")

''' + '\n'.join(decls) + '''

    override fun init() {
        // Noamm loads saved values without firing onChange, so copy everything once on the first tick,
        // then keep Secret Routes' own on/off keybind and this feature's toggle in step.
        EventBus.register<TickEvent.Start> {
            if (!synced) {
                synced = true
                pushToSrm()
            }
            val cfg = C()
            if (lastEnabled != null && cfg.modEnabled != lastEnabled && cfg.modEnabled != enabled) toggle()
            cfg.modEnabled = enabled
            lastEnabled = enabled
        }
    }

    fun pushToSrm() {
''' + '\n'.join(pushes) + '''
    }

    fun pullFromSrm() {
''' + '\n'.join(pulls) + '''
    }
}
'''
open(out_kt, 'w', encoding='utf-8').write(kt)
print(f'fields={len(fields)} settings={len(decls)} labelled={sum(1 for f in fields if f[1] in meta and meta[f[1]].get("name"))}')
