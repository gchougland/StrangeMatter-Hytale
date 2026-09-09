package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.ui.gadget.GadgetHudService;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.ComponentUpdate;
import com.hypixel.hytale.protocol.ModelUpdate;
import com.hypixel.hytale.protocol.PlayerSkinUpdate;
import com.hypixel.hytale.protocol.ActiveAnimationsUpdate;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.packets.assets.UpdateItemPlayerAnimations;
import com.hypixel.hytale.protocol.packets.entities.MountMovement;
import com.hypixel.hytale.protocol.packets.entities.PlayAnimation;
import com.hypixel.hytale.protocol.packets.interaction.MountNPC;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.type.itemanimation.ItemPlayerAnimationsPacketGenerator;
import com.hypixel.hytale.server.core.asset.type.itemanimation.config.ItemPlayerAnimations;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.entity.component.ActiveAnimationComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** Regression for the actual dressed avatar: native model AND skin trackers stay clean. */
public final class NativeHoverboardRiderVerification {
    public static void verify(World world) throws Exception {
        world.debugAssertInTickingThread();
        verifyAssets();
        try (var rider = NativePlayerFixture.create(world, "NativeDressedSurfer", new Vector3d(20.5, 18, 20.5));
             var observer = NativePlayerFixture.create(world, "NativeSurfObserver", new Vector3d(23.5, 18, 20.5));
             var hud = new GadgetHudService()) {
            var store = rider.store();
            var cosmetics = CosmeticsModule.get();
            var skinData = cosmetics.generateRandomSkin(new Random(7429));
            cosmetics.validateSkin(skinData);
            var model = new ModelComponent(cosmetics.createModel(skinData));
            var skin = new PlayerSkinComponent(skinData);
            require(model.getModel() != null && skin.getPlayerSkin() != null, "Fixture uses real native nonnull player cosmetics");
            store.putComponent(rider.ref(), ModelComponent.getComponentType(), model);
            store.putComponent(rider.ref(), PlayerSkinComponent.getComponentType(), skin);
            store.putComponent(rider.ref(), PersistentModel.getComponentType(), new PersistentModel(model.getModel().toReference()));
            var persistent = store.getComponent(rider.ref(), PersistentModel.getComponentType());
            var originalReference = persistent.getModelReference();
            var active = store.getComponent(rider.ref(), ActiveAnimationComponent.getComponentType());
            var ownerViewer = store.getComponent(rider.ref(), EntityTrackerSystems.EntityViewer.getComponentType());
            var observerViewer = store.getComponent(observer.ref(), EntityTrackerSystems.EntityViewer.getComponentType());
            var visible = store.getComponent(rider.ref(), EntityTrackerSystems.Visible.getComponentType());
            if (visible == null) { visible = new EntityTrackerSystems.Visible(); store.putComponent(rider.ref(), EntityTrackerSystems.Visible.getComponentType(), visible); }
            int id = store.getComponent(rider.ref(), NetworkId.getComponentType()).getId();
            ownerViewer.visible.add(rider.ref()); ownerViewer.sent.put(rider.ref(), id);
            observerViewer.visible.add(rider.ref()); observerViewer.sent.put(rider.ref(), id);
            visible.visibleTo.put(rider.ref(), ownerViewer); visible.visibleTo.put(observer.ref(), observerViewer);
            visible.newlyVisibleTo.putAll(visible.visibleTo);
            var initial = trackers(rider, ownerViewer, observerViewer);
            require(initial.stream().anyMatch(ModelUpdate.class::isInstance) && initial.stream().anyMatch(part -> part instanceof PlayerSkinUpdate update && update.skin == skinData), "Initial native tracker sends both the model and real customized skin");
            visible.newlyVisibleTo.clear();
            require(trackers(rider, ownerViewer, observerViewer).isEmpty(), "Initial model/skin dirtiness and newly-visible traffic are drained");
            rider.packets().packets.clear(); observer.packets().packets.clear();
            observerViewer.visible.remove(rider.ref()); observerViewer.sent.removeInt(rider.ref());
            var tools = new MobilityTools(hud, Files.createTempDirectory("sm-native-skin-safe-surf-"));
            world.setBlock(20, 17, 20, "Rock_Stone");
            try {
                rider.hotbar().setItemStackForSlot((short) 0, new ItemStack("SM_Hoverboard", 1), false);
                store.getComponent(rider.ref(), InventoryComponent.Hotbar.getComponentType()).setActiveSlot((byte) 0, rider.ref(), store);
                deploy(tools, rider, world);
                assertAppearance(rider, model, skin, persistent, active, ownerViewer, observerViewer);
                require(lastPlay(rider).animationId.equals("SurfIdle"), "Mounted idle is an animation-only packet");
                require(observer.packets().ofType(PlayAnimation.class).isEmpty(), "Animation is not sent to an observer without the entity");
                wire(lastPlay(rider), id, "SurfIdle");
                int starts = rider.packets().ofType(PlayAnimation.class).size();
                for (int i = 0; i < 100; i++) tools.tick(world, .05);
                require(rider.packets().ofType(PlayAnimation.class).size() == starts, "Stable riding does not restart or spam the animation");
                assertAppearance(rider, model, skin, persistent, active, ownerViewer, observerViewer);

                observerViewer.visible.add(rider.ref()); observerViewer.sent.put(rider.ref(), id);
                tools.tick(world, .05);
                wire(lastPlay(observer), id, "SurfIdle");
                int lateStarts = observer.packets().ofType(PlayAnimation.class).size();
                tools.tick(world, .05);
                require(observer.packets().ofType(PlayAnimation.class).size() == lateStarts, "Late observer receives one animation start");

                var mount = world.getEntityStore().getRefFromNetworkId(rider.player().getMountEntityId());
                ownerViewer.visible.add(mount); ownerViewer.sent.put(mount, rider.player().getMountEntityId());
                int mountPackets = rider.packets().ofType(MountNPC.class).size();
                starts = rider.packets().ofType(PlayAnimation.class).size();
                tools.tick(world, .05);
                require(rider.packets().ofType(MountNPC.class).size() == mountPackets + 1 && rider.packets().ofType(PlayAnimation.class).size() == starts + 1, "Native mount reassert is followed by one local animation replay");
                assertAppearance(rider, model, skin, persistent, active, ownerViewer, observerViewer);
                for (var desired : List.of("SurfGlide", "SurfBoost", "SurfIdle")) {
                    var movement = new MountMovement();
                    var location = store.getComponent(mount, TransformComponent.getComponentType()).getPosition();
                    movement.absolutePosition = new Position(location.x, location.y, location.z);
                    movement.bodyOrientation = new Direction(0, 0, 0); movement.movementStates = new MovementStates();
                    movement.movementStates.running = desired.equals("SurfGlide"); movement.movementStates.sprinting = desired.equals("SurfBoost");
                    rider.packets().handleMountMovement(movement, rider.owner(), rider.ref(), world, store);
                    tools.tick(world, .05);
                    wire(lastPlay(rider), id, desired); wire(lastPlay(observer), id, desired);
                    assertAppearance(rider, model, skin, persistent, active, ownerViewer, observerViewer);
                }
                rider.save();
                var saved = Universe.get().getPlayerStorage().load(rider.owner().getUuid()).get(10, TimeUnit.SECONDS);
                require(saved.getComponent(PersistentModel.getComponentType()).getModelReference().equals(originalReference), "Actual save keeps the existing normal model reference while surfing");
                tools.useHoverboard(rider.owner(), store);
                require(rider.player().getMountEntityId() == 0, "Native board folds normally");
                stopped(lastPlay(rider), id); stopped(lastPlay(observer), id);
                assertAppearance(rider, model, skin, persistent, active, ownerViewer, observerViewer);
                starts = rider.packets().ofType(PlayAnimation.class).size();
                for (int i = 0; i < 8; i++) { rider.save(); tools.tick(world, .05); }
                require(rider.packets().ofType(PlayAnimation.class).size() == starts, "Dismount stop is not repeated during inventory return");
                var returnedBoard = rider.hotbar().getItemStack((short) 0);
                require(!ItemStack.isEmpty(returnedBoard) && "SM_Hoverboard".equals(returnedBoard.getItemId())
                        && returnedBoard.getQuantity() == 1 && HoverboardLedger.token(returnedBoard) != null,
                        "Actual refunded board returns directly to the open hotbar with its receipt metadata");
                int boardCount = 0;
                var inventory = rider.inventory();
                for (short slot = 0; slot < inventory.getCapacity(); slot++) {
                    var item = inventory.getItemStack(slot);
                    if (!ItemStack.isEmpty(item) && "SM_Hoverboard".equals(item.getItemId())) boardCount += item.getQuantity();
                }
                require(boardCount == 1, "Hotbar return never leaves a second physical board in storage or backpack");
                // Native remove callbacks run while PlayerRef is temporarily detached.
                deploy(tools, rider, world);
                require(lastPlay(rider).animationId != null, "Second deployment starts a new animation lifecycle");
                var holder = rider.owner().removeFromStore();
                stopped(lastPlay(rider), id); stopped(lastPlay(observer), id);
                require(holder.getComponent(ModelComponent.getComponentType()) == model
                        && holder.getComponent(PlayerSkinComponent.getComponentType()) == skin,
                        "Detached holder retains the exact existing model and skin without wrappers");
                require(holder.getComponent(ActiveAnimationComponent.getComponentType()) == active, "No active animation slot array is introduced during transfer");
                tools.tick(world, .05);
            } finally {
                tools.cleanup(world);
                world.setBlock(20, 17, 20, "Empty");
            }
        }
        System.out.println("NATIVE_HOVERBOARD_RIDER_VERIFICATION_PASSED: real nonnull cosmetics, drained native EntityModel+EntitySkin trackers, no Model/Skin/ActiveAnimation resets, actual item-animation asset and PlayAnimation wire, self/late viewers, mounted transitions, native mount replay, save, fold and removal stop.");
    }

    private static void deploy(MobilityTools tools, NativePlayerFixture rider, World world) throws Exception {
        tools.useHoverboard(rider.owner(), rider.store());
        for (int i = 0; i < 8 && rider.player().getMountEntityId() == 0; i++) { rider.save(); tools.tick(world, .05); }
        require(rider.player().getMountEntityId() != 0, "Actual save-gated hoverboard deployment succeeds");
    }

    private static void verifyAssets() {
        require(ModelAsset.getAssetMap().getAsset(HoverboardRiderPose.ASSET) == null, "Obsolete avatar-replacing ModelAsset is absent");
        var asset = ItemPlayerAnimations.getAssetMap().getAsset(HoverboardRiderPose.ASSET);
        require(asset != null, "Native ItemPlayerAnimations asset is loaded");
        var generated = (UpdateItemPlayerAnimations) new ItemPlayerAnimationsPacketGenerator().generateUpdatePacket(Map.of(asset.getId(), asset));
        var bytes = MemorySegment.ofArray(new byte[generated.computeSize()]);
        require(generated.serialize(bytes, 0) == bytes.byteSize(), "Native animation asset packet size matches wire serialization");
        var packet = UpdateItemPlayerAnimations.toObject(bytes).itemPlayerAnimations.get(asset.getId());
        for (var key : HoverboardRiderPose.ANIMATIONS) {
            var clip = packet.animations.get(key);
            require(clip != null && clip.looping && clip.thirdPerson.equals(clip.thirdPersonMoving), "Stationary and moving avatar use the same complete surfing clip: " + key);
            require(CommonAssetRegistry.getByName(clip.thirdPerson) != null, "Native registry resolves third person clip: " + key);
            require(clip.firstPerson == null && clip.firstPersonOverride == null && clip.keepPreviousFirstPersonAnimation, "First person hands are not replaced by the third person whole-body clip");
        }
    }

    private static List<ComponentUpdate> trackers(NativePlayerFixture rider, EntityTrackerSystems.EntityViewer... viewers) {
        for (var viewer : viewers) viewer.updates.remove(rider.ref());
        var store = rider.store();
        var models = new EntityTrackerSystems.EntityModel(EntityTrackerSystems.Visible.getComponentType());
        var skins = new EntityTrackerSystems.EntitySkin(EntityTrackerSystems.Visible.getComponentType(), PlayerSkinComponent.getComponentType());
        store.forEachChunk(models.getQuery(), (chunk, commands) -> {
            for (int i = 0; i < chunk.size(); i++) if (chunk.getReferenceTo(i).equals(rider.ref())) models.tick(.05f, i, chunk, store, commands);
        });
        store.forEachChunk(skins.getQuery(), (chunk, commands) -> {
            for (int i = 0; i < chunk.size(); i++) if (chunk.getReferenceTo(i).equals(rider.ref())) skins.tick(.05f, i, chunk, store, commands);
        });
        var result = new ArrayList<ComponentUpdate>();
        for (var viewer : viewers) {
            var update = viewer.updates.remove(rider.ref());
            if (update != null && update.toUpdatesArray() != null) result.addAll(List.of(update.toUpdatesArray()));
        }
        return result;
    }

    private static void assertAppearance(NativePlayerFixture rider, ModelComponent model, PlayerSkinComponent skin,
            PersistentModel persistent, ActiveAnimationComponent active, EntityTrackerSystems.EntityViewer... viewers) {
        var store = rider.store();
        require(store.getComponent(rider.ref(), ModelComponent.getComponentType()) == model
                && store.getComponent(rider.ref(), PlayerSkinComponent.getComponentType()) == skin
                && store.getComponent(rider.ref(), PersistentModel.getComponentType()) == persistent,
                "Surfing never replaces the actual customized avatar components");
        require(store.getComponent(rider.ref(), ActiveAnimationComponent.getComponentType()) == active, "Surfing never creates or replaces an active slot array");
        require(trackers(rider, viewers).stream().noneMatch(update -> update instanceof ModelUpdate || update instanceof PlayerSkinUpdate || update instanceof ActiveAnimationsUpdate),
                "Native EntityModel and EntitySkin trackers emit no avatar reset while surfing or dismounting");
    }

    private static PlayAnimation lastPlay(NativePlayerFixture viewer) { return viewer.packets().ofType(PlayAnimation.class).getLast(); }
    private static void wire(PlayAnimation packet, int entityId, String animation) {
        var bytes = MemorySegment.ofArray(new byte[packet.computeSize()]);
        require(packet.serialize(bytes, 0) == bytes.byteSize(), "PlayAnimation wire size matches native schema");
        var decoded = PlayAnimation.toObject(bytes);
        require(decoded.entityId == entityId && decoded.slot == AnimationSlot.Action && HoverboardRiderPose.ASSET.equals(decoded.itemAnimationsId)
                && animation.equals(decoded.animationId), "Native wire targets the existing avatar with the explicit custom item-animation family");
    }
    private static void stopped(PlayAnimation packet, int entityId) {
        require(packet.entityId == entityId && packet.slot == AnimationSlot.Action && packet.animationId == null && packet.itemAnimationsId == null,
                "Dismount/removal clears only the temporary action animation");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
