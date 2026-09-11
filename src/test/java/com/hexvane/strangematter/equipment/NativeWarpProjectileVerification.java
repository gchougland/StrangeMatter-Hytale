package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.anomaly.AnomalyRecord;
import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hexvane.strangematter.anomaly.AnomalyType;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.projectile.component.Projectile;
import com.hypixel.hytale.server.core.modules.projectile.config.ProjectileConfig;
import com.hypixel.hytale.server.core.modules.projectile.config.StandardPhysicsProvider;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Real native projectile physics, block/entity collisions and live inventory endpoint commits. */
public final class NativeWarpProjectileVerification {
    private static final Vector3d EYE = new Vector3d(4.5, 151.4, 8.5);
    private record Bolt(String token, Ref<EntityStore> ref, TransformComponent transform, StandardPhysicsProvider physics) {}

    public static void verify(World world, AnomalyService anomalies) throws Exception {
        var store = world.getEntityStore().getStore();
        var warps = new WarpProjectiles(anomalies);
        Set<UUID> previous = new HashSet<>(); for (var a : anomalies.all()) previous.add(a.id);
        List<Vector3i> wall = new ArrayList<>();
        try (var owner = NativePlayerFixture.create(world, "NativeWarpVerification", new Vector3d(4.5, 150, 8.5))) {
            Player.setGameMode(owner.ref(), GameMode.Adventure, store);
            for (int y = 150; y <= 153; y++) {
                for (int z = 7; z <= 9; z++) place(world, wall, 22, y, z);
                for (int x = 3; x <= 5; x++) { place(world, wall, x, y, 22); place(world, wall, x, y, 4); }
            }
            for (String id : List.of("SM_Warp_Bolt_Cyan", "SM_Warp_Bolt_Purple"))
                require(ProjectileConfig.getAssetMap().getAsset(id) != null, "Native projectile config is loaded: " + id);

            var gun = new ItemStack("SM_Warp_Gun", 1).withMetadata("NativeWarpMarker", Codec.STRING, "slot-move-preserved");
            owner.hotbar().setItemStackForSlot((short) 0, gun, false);
            double initialDurability = gun.getDurability();
            var cyan = launch(warps, owner, (short) 0, false, new Vector3d(1, 0, 0));
            require(gates(anomalies, previous).isEmpty(), "Trigger pull creates no endpoint before the native projectile arrives");
            require(owner.hotbar().getItemStack((short) 0).getDurability() == initialDurability - 1,
                    "A survival trigger pull wears the real gun exactly once");
            var stamped = owner.hotbar().getItemStack((short) 0);
            owner.hotbar().setItemStackForSlot((short) 6, stamped, false);
            owner.hotbar().setItemStackForSlot((short) 0, new ItemStack("SM_Resonite_Ingot", 2), false);
            var before = new Vector3d(cyan.transform.getPosition());
            step(world, warps);
            require(cyan.ref.isValid() && cyan.transform.getPosition().distance(before) > .1
                    && metadata(owner.hotbar().getItemStack((short) 6), WarpProjectiles.portalKey(false)) == null,
                    "Actual native physics moves the cyan bolt through empty space before it reaches the wall");
            finish(world, warps, cyan);
            var firstCyan = gate(anomalies, owner.hotbar().getItemStack((short) 6), false);
            assertNativeHitHeight(firstCyan, cyan);
            require(firstCyan.portalChannel == 1 && firstCyan.pairedGate == null && firstCyan.x > 21.5 && firstCyan.x <= 22.1,
                    "Cyan endpoint comes from the actual wall collision and is initially unpaired");
            require("slot-move-preserved".equals(metadata(owner.hotbar().getItemStack((short) 6), "NativeWarpMarker"))
                    && owner.hotbar().getItemStack((short) 0).getItemId().equals("SM_Resonite_Ingot")
                    && owner.hotbar().getItemStack((short) 0).getQuantity() == 2,
                    "Impact finds the exact stamped gun after a slot move and leaves the former slot untouched");
            require(metadata(owner.hotbar().getItemStack((short) 6), WarpProjectiles.flightKey(false)) == null,
                    "Successful impact consumes its channel reservation");

            var purple = launch(warps, owner, (short) 6, true, new Vector3d(0, 0, 1));
            finish(world, warps, purple);
            var firstPurple = gate(anomalies, owner.hotbar().getItemStack((short) 6), true);
            assertNativeHitHeight(firstPurple, purple);
            require(firstPurple.portalChannel == 2 && firstPurple.pairedGate.equals(firstCyan.id)
                    && firstCyan.pairedGate.equals(firstPurple.id), "Both real impacts create a reciprocal cyan and purple pair");
            require(owner.hotbar().getItemStack((short) 6).getDurability() == initialDurability - 2,
                    "Travel and impact do not apply additional durability costs");

            var oldCyan = launch(warps, owner, (short) 6, false, new Vector3d(1, 0, 0));
            var latestCyan = launch(warps, owner, (short) 6, false, new Vector3d(0, 0, -1));
            require(!warps.inFlight(oldCyan.token) && !oldCyan.ref.isValid() && warps.inFlight(latestCyan.token),
                    "A newer cyan trigger retires the older cyan projectile without cancelling purple");
            finish(world, warps, latestCyan);
            var replacedCyan = gate(anomalies, owner.hotbar().getItemStack((short) 6), false);
            assertNativeHitHeight(replacedCyan, latestCyan);
            require(replacedCyan.z >= 4.9 && replacedCyan.z < 5.5 && !replacedCyan.id.equals(firstCyan.id)
                    && anomalies.get(firstCyan.id).isEmpty() && replacedCyan.pairedGate.equals(firstPurple.id)
                    && firstPurple.pairedGate.equals(replacedCyan.id),
                    "Only the newest channel intent replaces its endpoint and re-pairs the unchanged other colour");

            var pendingClear = launch(warps, owner, (short) 6, true, new Vector3d(1, 0, 0));
            var beforeClear = owner.hotbar().getItemStack((short) 6);
            require(warps.clear(world, owner.hotbar(), (short) 6, beforeClear), "Clear accepts the actual current gun");
            require(!warps.inFlight(pendingClear.token) && !pendingClear.ref.isValid(), "Clear cancels native in-flight entities immediately");
            var cleared = owner.hotbar().getItemStack((short) 6);
            for (boolean channel : new boolean[]{false, true}) require(metadata(cleared, WarpProjectiles.portalKey(channel)) == null
                    && metadata(cleared, WarpProjectiles.flightKey(channel)) == null, "Clear removes both endpoint and flight identities");
            require(cleared.getDurability() == beforeClear.getDurability() && gates(anomalies, previous).isEmpty(),
                    "Clear removes owned portals without charging another use");
            for (int i = 0; i < 45; i++) step(world, warps);
            require(gates(anomalies, previous).isEmpty(), "Cleared shots cannot create late portals");

            place(world, wall, 4, 148, 8);
            var downward = launch(warps, owner, (short) 6, false, new Vector3d(0, -1, 0));
            finish(world, warps, downward);
            var floorGate = gate(anomalies, owner.hotbar().getItemStack((short) 6), false);
            assertNativeHitHeight(floorGate, downward);
            require(downward.physics.getContactBlock().y == 148 && floorGate.y > 150,
                    "A real downward floor impact places the gate centre above the surface instead of half buried");
            require(warps.clear(world, owner.hotbar(), (short) 6, owner.hotbar().getItemStack((short) 6)),
                    "Floor fixture clears its owned endpoint before miss tests");

            var miss = launch(warps, owner, (short) 6, false, new Vector3d(0, 1, 0));
            finish(world, warps, miss);
            require(miss.physics.getContactBlock() == null && miss.transform.getPosition().y > EYE.y + 10
                    && gates(anomalies, previous).isEmpty(), "A genuine upward miss travels through empty space and expires without an endpoint");
            require(metadata(owner.hotbar().getItemStack((short) 6), WarpProjectiles.flightKey(false)) == null,
                    "Miss expiry clears the gun's pending reservation");

            try (var target = NativePlayerFixture.create(world, "NativeWarpTarget", new Vector3d(12.5, 150, 8.5))) {
                store.putComponent(target.ref(), BoundingBox.getComponentType(), new BoundingBox(new Box(-.45, 0, -.45, .45, 1.85, .45)));
                store.tick(tickDelta(world)); // Populate the native entity collision spatial index.
                var entityHit = launch(warps, owner, (short) 6, true, new Vector3d(1, 0, 0));
                finish(world, warps, entityHit);
                require(entityHit.physics.getContactBlock() == null && entityHit.physics.getPosition().x > 11
                        && entityHit.physics.getPosition().x < 14 && gates(anomalies, previous).isEmpty(),
                        "Native collision with a real player stops the shot before the wall and creates no portal");
            }

            // A long native step can see a creature behind the wall as well as the nearer
            // wall itself. The block contact must win even when native collision reports both.
            try (var behindWall = NativePlayerFixture.create(world, "NativeWarpBehindWall", new Vector3d(23.5, 150, 8.5))) {
                store.putComponent(behindWall.ref(), BoundingBox.getComponentType(), new BoundingBox(new Box(-.45, 0, -.45, .45, 1.85, .45)));
                store.getComponent(owner.ref(), TransformComponent.getComponentType()).setPosition(new Vector3d(21.3, 150, 8.5));
                store.tick(tickDelta(world));
                var wallFirst = launch(warps, owner, (short) 6, false, new Vector3d(1, 0, 0), new Vector3d(21.3, EYE.y, 8.5));
                // StandardPhysicsTickSystem fixes its dt to 1/world TPS; passing .1 to
                // store.tick alone would still simulate only the original native tick length.
                int originalTps = world.getTps();
                try { world.setTps(10); step(world, warps); }
                finally { world.setTps(originalTps); }
                require(!warps.inFlight(wallFirst.token), "Native long step resolves the nearer wall in front of a target");
                var wallGate = gate(anomalies, owner.hotbar().getItemStack((short) 6), false);
                assertNativeHitHeight(wallGate, wallFirst);
                require(wallGate.x > 21.5 && wallGate.x <= 22.1, "Target behind the wall cannot move the portal to its body");
                require(warps.clear(world, owner.hotbar(), (short) 6, owner.hotbar().getItemStack((short) 6)), "Wall precedence fixture clears its owned endpoint");
                store.getComponent(owner.ref(), TransformComponent.getComponentType()).setPosition(new Vector3d(4.5, 150, 8.5));
            }

            var actual = owner.hotbar().getItemStack((short) 6);
            require(!warps.launch(owner.owner(), store, EYE, new Vector3d(1, 0, 0), owner.hotbar(), (short) 6, gun, false)
                    && owner.hotbar().getItemStack((short) 6).equals(actual), "A stale stack snapshot cannot charge or launch another shot");
            var broken = actual.withDurability(0); owner.hotbar().setItemStackForSlot((short) 6, broken, false);
            require(!warps.launch(owner.owner(), store, EYE, new Vector3d(1, 0, 0), owner.hotbar(), (short) 6, broken, false)
                    && nativeBolts(store).isEmpty(), "A broken survival gun cannot launch a native projectile");
            owner.hotbar().setItemStackForSlot((short) 6, actual, false);
            Player.setGameMode(owner.ref(), GameMode.Creative, store);
            var creative = launch(warps, owner, (short) 6, false, new Vector3d(0, 1, 0));
            require(owner.hotbar().getItemStack((short) 6).getDurability() == actual.getDurability(), "Creative launch retains durability");
            var otherColour = launch(warps, owner, (short) 6, true, new Vector3d(0, 1, 0));
            warps.cleanup(world);
            require(!warps.inFlight(creative.token) && !warps.inFlight(otherColour.token)
                    && !creative.ref.isValid() && !otherColour.ref.isValid() && nativeBolts(store).isEmpty(),
                    "World cleanup retires both native projectile channels");
            for (boolean channel : new boolean[]{false, true}) require(metadata(owner.hotbar().getItemStack((short) 6), WarpProjectiles.flightKey(channel)) == null,
                    "Cleanup clears matching inventory flight metadata");
            owner.save();
        } finally {
            warps.cleanup(world);
            for (var a : gates(anomalies, previous)) anomalies.remove(a.id);
            anomalies.save();
            for (var p : wall) world.setBlock(p.x, p.y, p.z, "Empty");
        }
        System.out.println("NATIVE_WARP_PROJECTILE_VERIFICATION_PASSED: real native flight and block/entity collision, impact height plus one, independent colour pairing, moved inventory identity, exact durability, stale/latest shot rules, clear, miss expiry and world cleanup.");
    }

    private static Bolt launch(WarpProjectiles warps, NativePlayerFixture owner, short slot, boolean purple, Vector3d direction) {
        return launch(warps, owner, slot, purple, direction, EYE);
    }
    private static Bolt launch(WarpProjectiles warps, NativePlayerFixture owner, short slot, boolean purple, Vector3d direction, Vector3d eye) {
        var before = new HashSet<>(nativeBolts(owner.store()));
        var gun = owner.hotbar().getItemStack(slot);
        require(warps.launch(owner.owner(), owner.store(), new Vector3d(eye), direction, owner.hotbar(), slot, gun, purple),
                "Production launch creates a " + (purple ? "purple" : "cyan") + " projectile");
        String token = metadata(owner.hotbar().getItemStack(slot), WarpProjectiles.flightKey(purple));
        require(token != null && warps.inFlight(token), "Launch stamps and owns the exact channel nonce");
        var created = nativeBolts(owner.store()).stream().filter(ref -> !before.contains(ref)).toList();
        require(created.size() == 1, "Exactly one new native Projectile entity was created");
        var ref = created.getFirst();
        var physics = owner.store().getComponent(ref, StandardPhysicsProvider.getComponentType());
        var model = owner.store().getComponent(ref, ModelComponent.getComponentType());
        require(physics != null && model != null && physics.getCreatorUuid().equals(owner.owner().getUuid())
                && model.getModel().getModelAssetId().equals(purple ? "SM_Warp_Bolt_Purple" : "SM_Warp_Bolt_Cyan"),
                "Native model colour and standard physics creator match the actual launch");
        require(owner.store().getComponent(ref, EntityStore.REGISTRY.getNonSerializedComponentType()) != null,
                "Transient native shots cannot reappear as independently saved entities");
        return new Bolt(token, ref, owner.store().getComponent(ref, TransformComponent.getComponentType()), physics);
    }
    private static float tickDelta(World world) { return 1f / world.getTps(); }
    private static void step(World world, WarpProjectiles warps) {
        float dt = tickDelta(world);
        world.getEntityStore().getStore().tick(dt); warps.tick(world, dt);
    }
    private static void finish(World world, WarpProjectiles warps, Bolt bolt) {
        for (int i = 0; i < 220 && warps.inFlight(bolt.token); i++) step(world, warps);
        require(!warps.inFlight(bolt.token) && !bolt.ref.isValid(), "Native flight reaches collision or bounded expiry");
    }
    private static List<Ref<EntityStore>> nativeBolts(Store<EntityStore> store) {
        var result = new ArrayList<Ref<EntityStore>>();
        store.forEachChunk(Projectile.getComponentType(), (chunk, commands) -> {
            for (int i = 0; i < chunk.size(); i++) {
                var model = chunk.getComponent(i, ModelComponent.getComponentType());
                if (model != null && Set.of("SM_Warp_Bolt_Cyan", "SM_Warp_Bolt_Purple").contains(model.getModel().getModelAssetId())) result.add(chunk.getReferenceTo(i));
            }
        });
        return result;
    }
    private static AnomalyRecord gate(AnomalyService anomalies, ItemStack gun, boolean purple) {
        String id = metadata(gun, WarpProjectiles.portalKey(purple));
        require(id != null, "Block impact commits an endpoint UUID to the live gun");
        return anomalies.get(UUID.fromString(id)).orElseThrow(() -> new AssertionError("Committed portal is absent"));
    }
    private static void assertNativeHitHeight(AnomalyRecord gate, Bolt bolt) {
        require(bolt.physics.getContactBlock() != null, "Actual native collision records its contacted block");
        var impact = bolt.physics.getContactPosition();
        require(Math.abs(gate.x - impact.x) < .001 && Math.abs(gate.y - impact.y - 1) < .001 && Math.abs(gate.z - impact.z) < .001,
                "Portal centre is the actual native impact position plus exactly one block vertically");
    }
    private static List<AnomalyRecord> gates(AnomalyService anomalies, Set<UUID> previous) {
        return anomalies.all().stream().filter(a -> a.type == AnomalyType.WARP_GATE && !previous.contains(a.id)).toList();
    }
    private static String metadata(ItemStack stack, String key) { return stack.getFromMetadataOrNull(key, Codec.STRING); }
    private static void place(World world, List<Vector3i> wall, int x, int y, int z) {
        require(world.getBlock(x, y, z) == 0, "Warp fixture reserves an empty isolated wall cell");
        wall.add(new Vector3i(x, y, z)); world.setBlock(x, y, z, "Rock_Stone");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
