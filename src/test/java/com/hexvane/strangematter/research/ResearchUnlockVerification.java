package com.hexvane.strangematter.research;

import com.hypixel.hytale.builtin.crafting.CraftingPlugin;
import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.protocol.packets.interface_.UpdateKnownRecipes;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.io.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.*;

/** Runs inside the isolated native world harness after crafting registries and item assets load. */
public final class ResearchUnlockVerification {
    public static void verify(World world) throws Exception {
        var directory = Files.createTempDirectory("sm-native-research-unlocks-"); UUID id = UUID.randomUUID();
        var player = new Player(); var config = player.getPlayerConfigData();
        var packets = new RecordingPackets(); var owner = new PlayerRef(null, id, "ResearchVerification", "en-US", packets, null);
        var ref = new Ref<EntityStore>(world.getEntityStore().getStore());
        // Only this test fixture supplies the socket-free player components. Native crafting
        // validation and packet generation below are the actual server implementations.
        @SuppressWarnings("unchecked") ComponentAccessor<EntityStore> accessor = (ComponentAccessor<EntityStore>) Proxy.newProxyInstance(
                ComponentAccessor.class.getClassLoader(), new Class<?>[]{ComponentAccessor.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getComponent")) return args[1] == Player.getComponentType() ? player : args[1] == PlayerRef.getComponentType() ? owner : null;
                    if (method.getName().equals("getExternalData")) return world.getEntityStore();
                    throw new UnsupportedOperationException(method.getName());
                });
        var recipe = CraftingRecipe.getAssetMap().getAsset("SM_Reality_Forge_Recipe_Generated_0");
        require(recipe != null && recipe.isKnowledgeRequired(), "Real native Reality Forge recipe requires learned knowledge");
        int gated = 0;
        for (var candidate : CraftingRecipe.getAssetMap().getAssetMap().values()) {
            if (!candidate.getId().startsWith("SM_") || candidate.getBenchRequirement() == null) continue;
            if (Arrays.stream(candidate.getBenchRequirement()).noneMatch(b -> b.type == BenchType.Crafting)) continue;
            require(candidate.isKnowledgeRequired(), "Every native SM crafting recipe requires knowledge: " + candidate.getId());
            require(candidate.getPrimaryOutput() != null && ResearchRecipeBridge.requirement(candidate.getPrimaryOutput().getItemId()) != null, "Every native SM crafting output maps to a research topic: " + candidate.getId());
            gated++;
        }
        require(gated >= 45, "All native laboratory crafting recipe families were audited");
        var manager = new CraftingManager(); manager.setBench(0, 0, 0, BlockType.getAssetMap().getAsset("SM_Laboratory_Bench"));
        var nativeValidation = CraftingManager.class.getDeclaredMethod("isValidBenchForRecipe", Ref.class, ComponentAccessor.class, CraftingRecipe.class); nativeValidation.setAccessible(true);
        try (var research = new ResearchService(directory)) {
            config.setKnownRecipes(new HashSet<>(Set.of("OtherMod_Recipe", "SM_Reality_Forge", recipe.getId())));
            require(ResearchRecipeBridge.reconcile(research, id, config), "Join migration repairs stale native knowledge");
            require(config.getKnownRecipes().contains("OtherMod_Recipe") && !config.getKnownRecipes().contains("SM_Reality_Forge"), "Locked mod recipe is removed while unrelated recipe remains");
            require(config.getKnownRecipes().contains("SM_Field_Scanner") && config.getKnownRecipes().contains("SM_Field_Scanner_Recipe_Generated_0"), "Starting research grants native output and diagram recipe IDs");
            verifyStarterRecipes(research, id, config, manager, ref, accessor, nativeValidation);
            research.reset(id);
            config.setKnownRecipes(new HashSet<>(Set.of("OtherMod_Recipe")));
            ResearchRecipeBridge.reconcile(research, id, config);
            verifyStarterRecipes(research, id, config, manager, ref, accessor, nativeValidation);
            require(!(boolean) nativeValidation.invoke(manager, ref, accessor, recipe), "Actual CraftingManager rejects unlearned research recipe");
            var gate = new ResearchCraftGate(research); var event = new CraftRecipeEvent.Pre(recipe, 1);
            gate.validate(id, event); require(event.isCancelled(), "Native pre-craft event blocks missing research");
            config.setKnownRecipes(new HashSet<>(Set.of("SM_Reality_Forge", recipe.getId())));
            var forged = new CraftRecipeEvent.Pre(recipe, 1); gate.validate(id, forged);
            require(forged.isCancelled(), "Manually learned native recipe cannot bypass authoritative research");
            research.unlock(id, "reality_forge", true);
            ResearchRecipeBridge.reconcile(research, id, config);
            require((boolean) nativeValidation.invoke(manager, ref, accessor, recipe), "Actual CraftingManager accepts learned recipe at matching native laboratory bench");
            var allowed = new CraftRecipeEvent.Pre(recipe, 1); gate.validate(id, allowed); require(!allowed.isCancelled(), "Completed research passes the native pre-craft guard");
            CraftingPlugin.sendKnownRecipes(ref, accessor);
            var packet = packets.sent.stream().filter(p -> p instanceof UpdateKnownRecipes).map(p -> (UpdateKnownRecipes)p).findFirst().orElseThrow();
            require(packet.known.containsKey("SM_Reality_Forge"), "Native CraftingPlugin sends newly unlocked recipe to the client");
            var bytes = MemorySegment.ofArray(new byte[packet.computeSize()]); require(packet.serialize(bytes, 0) == bytes.byteSize(), "Known recipe packet exact native wire size");
            var decoded = UpdateKnownRecipes.toObject(bytes);
            require(decoded.known.get("SM_Reality_Forge").primaryOutput != null && decoded.equals(packet), "Known recipe packet round trip retains valid output and recipe contents");
            require(!ResearchRecipeBridge.reconcile(research, id, config), "Unchanged knowledge reconciliation is idempotent");
        }
        try (var restored = new ResearchService(directory)) {
            config.setKnownRecipes(new HashSet<>()); ResearchRecipeBridge.reconcile(restored, id, config);
            require(config.getKnownRecipes().contains("SM_Reality_Forge"), "Existing completed ledger migrates to native recipes after restart/join");
            verifyStarterRecipes(restored, id, config, manager, ref, accessor, nativeValidation);
        }
        System.out.println("PASS: native recipe migration, output/diagram knowledge IDs, actual CraftingManager reject/accept, authoritative pre-craft gate, native UpdateKnownRecipes wire packet and restart migration.");
    }
    private static void verifyStarterRecipes(ResearchService research, UUID id,
            com.hypixel.hytale.server.core.entity.entities.player.data.PlayerConfigData config,
            CraftingManager manager, Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor,
            java.lang.reflect.Method nativeValidation) throws Exception {
        var gate = new ResearchCraftGate(research);
        for (String output : List.of("SM_Resonant_Coil", "SM_Resonant_Circuit", "SM_Stabilized_Core",
                "SM_Research_Machine", "SM_Research_Tablet", "SM_Field_Scanner")) {
            var recipe = CraftingRecipe.getAssetMap().getAsset(output + "_Recipe_Generated_0");
            require(recipe != null && config.getKnownRecipes().contains(output) && config.getKnownRecipes().contains(recipe.getId()),
                    "Starter component/instrument is exposed in native output and diagram knowledge: " + output);
            require((boolean) nativeValidation.invoke(manager, ref, accessor, recipe),
                    "Actual CraftingManager permits startup recipe without paid research: " + output);
            var event = new CraftRecipeEvent.Pre(recipe, 1);
            gate.validate(id, event);
            require(!event.isCancelled(), "Authoritative crafting gate allows bootstrap recipe: " + output);
        }
        for (String output : List.of("SM_Resonant_Burner", "SM_Resonant_Charging_Station", "SM_Resonant_Conduit", "SM_Resonant_Energy_Storage")) {
            var recipe = CraftingRecipe.getAssetMap().getAsset(output + "_Recipe_Generated_0");
            require(recipe != null && !config.getKnownRecipes().contains(output), "Power infrastructure remains undiscovered: " + output);
            var event = new CraftRecipeEvent.Pre(recipe, 1);
            gate.validate(id, event);
            require(event.isCancelled(), "Default component access cannot bypass paid power research: " + output);
        }
    }
    public static final class RecordingPackets extends PacketHandler {
        final List<ToClientPacket> sent = new ArrayList<>();
        RecordingPackets() { super(null, new ProtocolVersion(0)); }
        @Override public String getIdentifier() { return "Research unlock verification"; }
        @Override public void accept(ToServerPacket packet) {}
        @Override public void write(ToClientPacket packet) { sent.add(packet); }
        @Override public void writeNoCache(ToClientPacket packet) { sent.add(packet); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
