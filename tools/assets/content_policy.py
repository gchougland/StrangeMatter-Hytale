"""Small shared content rules; importing this module never writes resources."""
from pathlib import Path
import json
from discipline_palette import MINERAL_RGB, crystal_light

ROOT = Path(__file__).resolve().parents[2]
COMMON = ROOT / 'src/main/resources/Common'
FAMILIES = ('gravitic', 'chrono', 'energetic', 'spatial', 'shade', 'insight')
CRYSTAL_LIGHT = {family: crystal_light(family) for family in FAMILIES}
FIXTURE_MINERAL_RGB = MINERAL_RGB


def family_light(family, fixture=False):
    # Hytale ColorLight channels are intensities, not an sRGB texture swatch.
    # Crystal channels include neutral fill. Bright fixtures remove that shared
    # white component before scaling, preserving hue without an overbright wash.
    channels = [int(c, 16) for c in CRYSTAL_LIGHT[family][1:]]
    if fixture:
        channels = FIXTURE_MINERAL_RGB[family]
        low = min(channels); spread = max(channels) - low
        channels = [round((c - low) * 15 / spread) for c in channels]
    return '#' + ''.join(format(c, 'x') for c in channels)


def model_reference(path):
    manifest = ROOT / 'tools/assets/shared-models.json'
    if manifest.exists() and not (COMMON / path).exists():
        target = json.loads(manifest.read_text(encoding='utf8')).get('aliases', {}).get(path)
        if target and (COMMON / target).exists():
            return target
    return path


def descriptions():
    result = {
      'resonant_charging_station': 'Recharge one gadget using conduit power. Stores 10,000 RE; transfers up to 800 RE/s. Crafted at the Laboratory Bench after Resonant Energy Fundamentals.',
      'resonant_energy_storage': 'Store up to 60,000 RE and transfer up to 400 RE/s. Configure each face as Input, Output or Disabled. Starts empty; Pack Up preserves the charge and settings.',
      'resonant_battery_pack': 'Equip in your chest slot to charge inventory gadgets from a 60,000 RE reserve. Prioritizes the held gadget. Recharge in a charging station or burner dock. No armor resistance.',
      'gravitic_manipulator': 'Secondary lifts or releases a block, creature or intact chest; primary launches it. Chests keep their inventory. Stores 12,000 RE. Acquisition, holding and launching consume power.',
      'arc_projector': 'Hold primary to fire electrical pulses that chain through nearby enemies. Stores 12,000 RE; each pulse costs 200 RE. Walls stop the arc.',
      'anomalous_dirt': 'Anomaly-saturated subsoil. The spectral veins remain after excavation; use it beneath anomalous grass or in laboratory terrain displays.',
      'anomalous_grass': 'Grass touched by unstable matter. Slowly spreads to nearby grass; a solid opaque covering turns it back to dirt.',
      'anomaly_resonator': 'Locate the nearest surveyed anomaly. Secondary cycles the six field frequencies or searches for any type.',
      'anomaly_nullifier': 'Suppresses anomaly effects within 12 blocks. Use to turn it on or off. Needs no fuel or resonant power.',
      'chrono_blister': 'Launch a temporal blister. Its impact leaves a ragged field that slows creatures and fades after 10 to 30 seconds.',
      'containment_capsule': 'An empty anomaly vessel. Carry it while using the Echo Vacuum to seal a field inside.',
      'echo_vacuum': 'Hold primary on an anomaly for two seconds to capture it. Requires an empty capsule and room for the filled vessel.',
      'echoform_imprinter': 'Hold primary on a creature or player for one second to copy their appearance. Secondary or Use restores your own form.',
      'field_scanner': 'Hold primary on a natural anomaly for two seconds to earn observations. Each field identity rewards your journal once.',
      'graviton_hammer': 'Primary swings with thorium mining strength across a 3 by 3 face; crouch for one block. Hold secondary for 1, 2 or 3 seconds to tunnel 3, 6 or 9 blocks deep.',
      'hoverboard': 'Deploy near solid ground. Move to steer, sprint to boost and jump to hop; dismount to fold the board back into your inventory.',
      'levitation_pad': 'A vertical laboratory lift. Use to switch ascent and descent; an unobstructed shaft reaches up to 16 blocks by default.',
      'paradoxical_energy_cell': 'A creative-only contradiction in a reinforced cage. Supplies up to 1000 RE per tick by default without consuming fuel.',
      'raw_resonite': 'Unrefined resonite locked in dark host rock. Smelt it into ingots for laboratory instruments and building materials.',
      'reality_forge': 'Assemble researched anomalous equipment from materials and the required shard types. Select a recipe and collect its finished output.',
      'research_machine': 'Load prepared research notes and solve their linked experiments. A stable result unlocks the corresponding discovery.',
      'research_notes': 'A written experiment proposal. Prepare its research topic with the Research Tablet, then test it in the Research Machine.',
      'research_tablet': 'Open your field journal to review discoveries, spend observations and prepare research notes for new experiments.',
      'resonance_condenser': 'Draw matching shards from a nearby anomaly. By default, consumes 2 RE per tick and produces one shard every 75 seconds within 10 blocks.',
      'resonant_burner': 'Burn one furnace fuel item at a time to generate 200 RE/s. Burning pauses when the reserve is full. Connect machines with resonant conduits or charge a gadget in the side dock.',
      'resonant_circuit': 'A tuned resonite control circuit. Used to assemble instruments, machines and more advanced Reality Forge devices.',
      'resonant_coil': 'A copper winding around a resonite conductor. A component for transferring and shaping anomalous energy.',
      'resonant_conduit': 'Connect adjacent energy sources and machines on any of six faces. Longer paths reduce transfer throughput; short routes deliver power faster.',
      'resonite_block': 'Nine resonite ingots compressed into a metal block. Useful for compact storage, construction, or crafting back into ingots.',
      'resonite_door': 'A hinged resonite laboratory door. Use to swing the reinforced panel open or closed.',
      'resonite_ingot': 'Refined resonite, the foundation of Strange Matter equipment. Smelt raw resonite or unpack a resonite block to obtain it.',
      'resonite_nugget': 'A small portion of refined resonite. Nine nuggets combine into one ingot at the laboratory bench.',
      'resonite_ore': 'Resonite veins embedded in ordinary stone. Mine with an iron-quality pickaxe or better to recover raw resonite.',
      'resonite_pillar': 'A ribbed structural column for laboratory walls and supports. Place along any of the three axes.',
      'resonite_tile': 'A navy resonite floor or wall panel, edged for clean laboratory construction.',
      'fancy_resonite_tile': 'An ornamental resonite panel with raised trim. Matches ordinary tiles for patterned laboratory floors and walls.',
      'resonite_tile_slab': 'A half-height resonite panel. Place as an upper or lower slab, or combine two into a full tile.',
      'resonite_tile_stairs': 'Stepped resonite panels for laboratory stairways and trim. Supports upright and inverted placement.',
      'resonite_trapdoor': 'A compact resonite access hatch. Use to open or close the panel over shafts and service spaces.',
      'rift_stabilizer': 'Harvest renewable energy from an energetic rift through a containment arch. Produces 40 RE/s within 16 blocks, with at most three stabilizers per rift.',
      'stabilized_core': 'A reinforced anomaly-energy core. A key component in advanced laboratory machinery and Reality Forge equipment.',
      'stasis_projector': 'A specimen display pad. Use to toggle its beam, which suspends one nearby creature and one dropped item above the emitter.',
      'time_dilation_block': 'A temporary golden temporal field. Creatures passing through are slowed; the field dissolves after 10 to 30 seconds.',
      'tinfoil_hat': 'Wear this carefully folded shield to resist thoughtwell perception effects and energetic rift damage. Suspicion, now with a brim.',
      'warp_gun': 'Primary and secondary launch the two ends of your personal gate pair. Travel through either linked gate; Use clears the pair.',
      'laboratory_bench': 'Craft researched Strange Matter equipment and building materials. The Shards & Lighting tab groups crystals and their light fixtures.',
      'lab_cyan_glass': 'Cyan glazing recovered from an anomaly scientist laboratory. A thin translucent pane for specimen rooms and observation windows.',
      'lab_lamp': 'A luminous cyan ceiling panel from an anomaly scientist laboratory. Fits a full building cell for continuous laboratory lighting.'
    }
    origins = {'gravitic': ('gravity anomaly', 'orange-red', 'gravity'),
      'chrono': ('temporal bloom', 'golden', 'time'), 'energetic': ('energetic rift', 'electric cyan', 'energy'),
      'spatial': ('warp gate', 'blue', 'space'), 'shade': ('echoing shadow', 'purple', 'shadow'),
      'insight': ('thoughtwell', 'green', 'cognition')}
    for family, (origin, color, discipline) in origins.items():
        base = family + '_shard'
        result[base] = f'Crystallized {discipline} from a {origin}. Condense it from the field or mine its ore; used in research-driven crafting and the Reality Forge.'
        result[base + '_crystal'] = f'A pointed bloom of four {family} shards. Emits a soft {color} glow and attaches to any of six block faces.'
        result[base + '_lamp'] = f'A two-block standing lamp with a suspended {family} crystal. Casts bright {color} light without fuel or resonant power.'
        result[base + '_lantern'] = f'A compact cage lantern sheltering a {family} crystal. Casts bright {color} light without fuel or resonant power.'
        result[base + '_ore'] = f'{family.title()} shards trapped in stone. Mine with an iron-quality pickaxe or better to recover material attuned to {discipline}.'
    capsules = {'gravity': 'gravity anomaly', 'temporal_bloom': 'temporal bloom', 'energetic': 'energetic rift',
      'warp_gate': 'warp gate', 'echoing_shadow': 'echoing shadow', 'thoughtwell': 'thoughtwell'}
    for suffix, label in capsules.items():
        result['containment_capsule_' + suffix] = f'A sealed {label}, retaining its original identity. Throw the capsule to deploy the field on impact; transported fields grant no new scans.'
    return result
