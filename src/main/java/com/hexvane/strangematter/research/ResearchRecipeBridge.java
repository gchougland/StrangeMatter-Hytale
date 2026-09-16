package com.hexvane.strangematter.research;

import com.hypixel.hytale.builtin.crafting.CraftingPlugin;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerConfigData;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;

/** Research is authoritative; native knowledge supplies crafting discovery and native validation. */
public final class ResearchRecipeBridge {
    private static final Map<String,String> TECHNOLOGIES = Map.ofEntries(
            Map.entry("SM_Anomaly_Resonator", "anomaly_resonator"), Map.entry("SM_Reality_Forge", "reality_forge"),
            Map.entry("SM_Tinfoil_Hat", "tinfoil_hat"), Map.entry("SM_Field_Scanner", "field_scanner"),
            Map.entry("SM_Research_Tablet", "research"), Map.entry("SM_Research_Machine", "research"),
            // The research desk itself needs these components before any paid discovery.
            Map.entry("SM_Resonant_Coil", "resonite"), Map.entry("SM_Resonant_Circuit", "resonite"),
            Map.entry("SM_Stabilized_Core", "resonite"),
            Map.entry("SM_Laboratory_Bench", "research"), Map.entry("SM_Resonance_Condenser", "resonance_condenser"),
            Map.entry("SM_Containment_Capsule", "containment_basics"), Map.entry("SM_Echo_Vacuum", "containment_basics"),
            Map.entry("SM_Echoform_Imprinter", "echoform_imprinter"), Map.entry("SM_Warp_Gun", "warp_gun"),
            Map.entry("SM_Chrono_Blister", "chrono_blister"), Map.entry("SM_Graviton_Hammer", "graviton_hammer"),
            Map.entry("SM_Stasis_Projector", "stasis_projector"), Map.entry("SM_Rift_Stabilizer", "rift_stabilizer"),
            Map.entry("SM_Anomaly_Nullifier", "rift_stabilizer"),
            Map.entry("SM_Resonant_Charging_Station", "resonant_energy"),
            Map.entry("SM_Resonant_Energy_Storage", "resonant_energy"),
            Map.entry("SM_Resonant_Battery_Pack", "resonant_battery_pack"),
            Map.entry("SM_Gravitic_Manipulator", "gravitic_manipulation"), Map.entry("SM_Arc_Projector", "arc_projection"),
            Map.entry("SM_Resonant_Separator", "resonant_separation"), Map.entry("SM_Flux_Furnace", "flux_smelting"),
            Map.entry("SM_Gravitic_Tube", "gravitic_transport"), Map.entry("SM_Pattern_Assembler", "pattern_assembly"),
            Map.entry("SM_Levitation_Pad", "levitation_pad"), Map.entry("SM_Hoverboard", "hoverboard"));
    public static String requirement(String item) {
        if (item == null) return null;
        String specific = TECHNOLOGIES.get(item); if (specific != null) return specific;
        if (item.matches("SM_(Chrono|Energetic|Gravitic|Insight|Shade|Spatial)_Shard(?:_Crystal|_Lamp|_Lantern)?")) return "anomaly_shards";
        if (item.startsWith("SM_Resonant_")) return "resonant_energy";
        if (item.startsWith("SM_Resonite_") || item.equals("SM_Raw_Resonite") || item.equals("SM_Fancy_Resonite_Tile")) return "resonite";
        return null;
    }
    /** Native CraftingManager tests output IDs; DiagramCraftingWindow tests recipe IDs. */
    static Set<String> knowledgeFor(ResearchService research, UUID player) {
        Set<String> knowledge = new HashSet<>();
        for (var recipe : CraftingRecipe.getAssetMap().getAssetMap().values()) {
            if (!recipe.getId().startsWith("SM_") || recipe.getPrimaryOutput() == null) continue;
            String output = recipe.getPrimaryOutput().getItemId(), node = requirement(output);
            if (node != null && research.hasUnlocked(player, node)) { knowledge.add(output); knowledge.add(recipe.getId()); }
        }
        return knowledge;
    }
    static boolean reconcile(ResearchService research, UUID player, PlayerConfigData config) {
        research.migrateNativeEnergyKnowledge(player, config.getKnownRecipes());
        Set<String> updated = new HashSet<>(config.getKnownRecipes());
        // Restrict revocation to this mod's mapped recipes. Unrelated mods and vanilla are untouched.
        updated.removeIf(id -> {
            var recipe = CraftingRecipe.getAssetMap().getAsset(id);
            String node = requirement(id);
            if (node == null && recipe != null && recipe.getId().startsWith("SM_") && recipe.getPrimaryOutput() != null)
                node = requirement(recipe.getPrimaryOutput().getItemId());
            return node != null && !research.hasUnlocked(player, node);
        });
        updated.addAll(knowledgeFor(research, player));
        if (updated.equals(config.getKnownRecipes())) return false;
        config.setKnownRecipes(updated); return true;
    }
    public static void sync(ResearchService research, PlayerRef playerRef, Store<EntityStore> store) {
        var ref = playerRef.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) return;
        var player = store.getComponent(ref, Player.getComponentType()); if (player == null) return;
        reconcile(research, playerRef.getUuid(), player.getPlayerConfigData());
        // Native packet generation resolves actual loaded recipe/material data and persistence
        // uses the player's ordinary KnownRecipes config field.
        CraftingPlugin.sendKnownRecipes(ref, store);
    }
    private ResearchRecipeBridge() {}
}
