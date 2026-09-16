package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.util.WorldAccess;

import com.hypixel.hytale.builtin.mounts.MountPlugin;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Holder;
import com.hexvane.strangematter.effects.GadgetEffects;
import com.hexvane.strangematter.ui.gadget.GadgetHudService;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementConfig;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.protocol.packets.interaction.MountNPC;
import com.hypixel.hytale.server.core.modules.physics.component.PhysicsValues;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import java.util.*;
import java.nio.file.Path;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.npc.components.StepComponent;
import java.util.concurrent.ConcurrentHashMap;

/** Ground-following native hoverboard mounts and the cosmetic Echoform Imprinter. */
public final class MobilityTools {
    private static final String BOARD = "SM_Hoverboard", BOARD_ROLE = "SM_Hoverboard_Mount", IMPRINTER = "SM_Echoform_Imprinter";
    static final float BOARD_SCALE = 2, BOARD_ANCHOR_Y = 1.64f;
    // Entity artwork uses 64 units per block. At scale 2, shifting the original
    // roots 43.98 units puts each grip top (8.5 units) exactly at the rider anchor.
    // The collider and mounted controller keep their existing native feet origin.
    static final double BOARD_VISUAL_ORIGIN_Y = 43.98 * BOARD_SCALE / 64;
    private final Map<UUID, Ride> rides = new ConcurrentHashMap<>();
    private final Map<UUID, Scan> scans = new ConcurrentHashMap<>();
    private final Map<UUID, Morph> morphs = new ConcurrentHashMap<>();
    private final Set<UUID> mountPresented = ConcurrentHashMap.newKeySet();
    private final Map<String,Double> time = new ConcurrentHashMap<>();
    private final Map<UUID,Double> boardPaidUntil=new ConcurrentHashMap<>(),morphPaidUntil=new ConcurrentHashMap<>();
    private final Map<UUID,String> morphTokens=new ConcurrentHashMap<>();
    private final GadgetHudService hud;
    private final HoverboardRecovery recovery;
    public MobilityTools(GadgetHudService hud,Path directory) { this.hud = hud;this.recovery=new HoverboardRecovery(directory); }
    private record Ride(World world, PlayerRef player, Ref<EntityStore> board, UUID boardId, UUID receipt, int networkId, MovementManager originalMovement, HoverboardRiderPose pose) {}
    private record Scan(World world, PlayerRef player, Ref<EntityStore> target, long began, boolean automatic) {}
    private record Morph(World world, PlayerRef player, Model originalModel, Model.ModelReference originalReference, PlayerSkinComponent originalSkin, Model appliedModel, PlayerSkinComponent appliedSkin) {}

    /** Deploys the board from inventory and mounts it; a second activation folds it away. */
    public void useHoverboard(PlayerRef playerRef, Store<EntityStore> store) {
        UUID uuid = playerRef.getUuid();
        Ride old = rides.get(uuid);
        if (old != null) { stopRide(old); say(playerRef, "Hoverboard folded away."); return; }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid() || ref.getStore()!=store) return;
        var hotbar=store.getComponent(ref,InventoryComponent.Hotbar.getComponentType());
        if(hotbar==null)return;
        short slot=hotbar.getActiveSlot();
        if(slot<0||slot>=hotbar.getInventory().getCapacity())return;
        ItemStack held=hotbar.getInventory().getItemStack(slot);
        if(ItemStack.isEmpty(held)||!BOARD.equals(held.getItemId()))return;
        if(!canDeploy(playerRef,store))return;
        var entity=store.getComponent(ref,Player.getComponentType());
        if(entity!=null&&entity.getGameMode()!=com.hypixel.hytale.protocol.GameMode.Creative&&GadgetEnergy.charge(held)<GadgetEnergy.cost("board_start")+GadgetEnergy.cost("board_second")){say(playerRef,"The hoverboard needs more Resonant Energy. Recharge it in a burner or charging station.");return;}
        if(recovery.begin(store.getExternalData().getWorld(),playerRef,hotbar.getInventory(),slot,held))
            say(playerRef,"Securing hoverboard inventory. The board will deploy when its save completes.");
        else say(playerRef,"This hoverboard is already deployed, recovering, or has a stale identity.");
    }
    private boolean canDeploy(PlayerRef playerRef,Store<EntityStore> store){
        var ref=playerRef.getReference();
        if(ref==null||!ref.isValid()||ref.getStore()!=store)return false;
        var player=store.getComponent(ref,Player.getComponentType());
        var transform=store.getComponent(ref,TransformComponent.getComponentType());
        if(player==null||transform==null||store.getComponent(ref,DeathComponent.getComponentType())!=null)return false;
        if(player.getMountEntityId()!=0){say(playerRef,"Dismount before deploying a hoverboard.");return false;}
        if(!surfaceNearby(store.getExternalData().getWorld(),transform.getPosition())){say(playerRef,"Deploy the hoverboard close to solid ground.");return false;}
        return true;
    }
    private boolean deploy(PlayerRef playerRef,UUID receipt){
        Ref<EntityStore> ref=playerRef.getReference();if(ref==null||!ref.isValid())return false;
        Store<EntityStore> store=ref.getStore();
        if(!canDeploy(playerRef,store))return false;
        UUID uuid=playerRef.getUuid();
        Player player = store.getComponent(ref, Player.getComponentType());
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        MovementManager movement = store.getComponent(ref, MovementManager.getComponentType());
        PhysicsValues physics = store.getComponent(ref, PhysicsValues.getComponentType());
        if (player == null || transform == null || movement == null || physics == null) return false;
        if (player.getMountEntityId() != 0) { say(playerRef, "Dismount before deploying a hoverboard."); return false; }
        World world = store.getExternalData().getWorld();
        Vector3d p = new Vector3d(transform.getPosition());
        if (!surfaceNearby(world, p)) { say(playerRef, "Deploy the hoverboard close to solid ground."); return false; }
        int role = NPCPlugin.get().getIndex(BOARD_ROLE);
        ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(BOARD_ROLE);
        MovementConfig config = MovementConfig.getAssetMap().getAsset(BOARD_ROLE);
        if (role < 0 || modelAsset == null || config == null) { say(playerRef, "The hoverboard's mount assets are unavailable. Check the server asset log."); return false; }
        HeadRotation head = store.getComponent(ref, HeadRotation.getComponentType());
        Rotation3f rotation = new Rotation3f();
        if (head != null) rotation.setYaw(head.getRotation().yaw());
        var mount = new NPCMountComponent(); mount.setOriginalRoleIndex(role); mount.setOwnerPlayerRef(playerRef); mount.setAnchor(0, BOARD_ANCHOR_Y, 0);
        Model model = boardModel(modelAsset);
        // The client mount controller expects a feet origin. Do not compensate for a negative
        // collider here: model, collider, eye height and rider anchor share the same origin.
        if (Math.abs(model.getBoundingBox().min.y) > 1e-6) {
            say(playerRef, "The hoverboard mount has incompatible collision assets. Reload the current asset pack.");
            return false;
        }
        var spawned = NPCPlugin.get().spawnEntity(store, role, p, rotation, model,
                (npc, holder, entityStore) -> {
                    // NPCMountSystems.OnAdd is a RefSystem: adding the component to an existing
                    // entity cannot mount its rider. It must exist when the holder enters the store.
                    prepareBoard(holder,mount);
                }, null);
        if (spawned == null || spawned.first() == null) { say(playerRef, "The hoverboard could not be deployed here."); return false; }
        Ref<EntityStore> board = spawned.first();
        var network = store.getComponent(board, NetworkId.getComponentType());
        if (network == null) { store.removeEntity(board, RemoveReason.REMOVE); return false; }
        if(!recovery.ledger().spend(receipt,GadgetEnergy.cost("board_start")+GadgetEnergy.cost("board_second"),player.getGameMode()==com.hypixel.hytale.protocol.GameMode.Creative)){store.removeEntity(board,RemoveReason.REMOVE);return false;}
        boardPaidUntil.put(uuid,time.getOrDefault(world.getName(),0d)+1);
        MovementManager original = (MovementManager) movement.clone();
        movement.setDefaultSettings(config, physics, player.getGameMode()); movement.applyDefaultSettings(); movement.update(playerRef.getPacketHandler());
        var velocity = store.getComponent(board, Velocity.getComponentType()); if (velocity != null) velocity.setZero();
        var pose = new HoverboardRiderPose();
        pose.ensure(playerRef, store);
        rides.put(uuid, new Ride(world, playerRef, board, store.getComponent(board,UUIDComponent.getComponentType()).getUuid(), receipt, network.getId(), original, pose));
        GadgetEffects.use(world, "SM_Hoverboard_Engage", new Vector3d(p).add(0, BOARD_VISUAL_ORIGIN_Y, 0));
        say(playerRef, "Hoverboard engaged. Move to steer, sprint to boost, jump to hop; dismount to fold it away.");
        return true;
    }
    static void prepareBoard(Holder<EntityStore> holder,NPCMountComponent mount){
        holder.ensureComponent(Interactable.getComponentType());
        // Mounted movement packets own this disposable entity. Without this marker the NPC
        // Walk steering system also applies a second gravity/collision simulation.
        // Frozen gates NPC simulation only; GamePacketHandler still applies rider movement.
        holder.addComponent(Frozen.getComponentType(),Frozen.get());
        holder.tryRemoveComponent(StepComponent.getComponentType());
        holder.addComponent(NPCMountComponent.getComponentType(),mount);
        holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(),NonSerialized.get());
        HoverboardRideEffects.attach(holder);
    }
    static Model boardModel(ModelAsset asset) { return Model.createScaledModel(asset, BOARD_SCALE); }

    /** Convenience invocation; native charging callers should use the action overload. */
    public void useImprinter(PlayerRef player, Store<EntityStore> store, Ref<EntityStore> target) {
        if (crouching(player, store)) { revert(player.getUuid(), true); return; }
        beginScan(player, store, target, true);
    }
    /** The charging interaction sends start, successful one-second completion, or release/cancel. */
    public void useImprinter(PlayerRef player, Store<EntityStore> store, Ref<EntityStore> target, String action) {
        if ("imprint_revert".equals(action) || "secondary".equals(action) || "use".equals(action)) {
            scans.remove(player.getUuid());
            if (!morphs.containsKey(player.getUuid())) { say(player, "You are already in your original form. Hold primary while aiming at a creature to imprint it."); return; }
            revert(player.getUuid(), true); return;
        }
        if ("cancel".equals(action)) { scans.remove(player.getUuid()); return; }
        if (crouching(player, store)) { scans.remove(player.getUuid()); revert(player.getUuid(), true); return; }
        if ("imprint_start".equals(action)) beginScan(player, store, target, false);
        else if ("imprint_complete".equals(action)) {
            Scan scan = scans.remove(player.getUuid());
            if (scan == null || scan.target() != target || System.nanoTime() - scan.began() < 900_000_000L || !validScan(scan, store)) return;
            applyMorph(scan, store);
        }
    }
    private void beginScan(PlayerRef player, Store<EntityStore> store, Ref<EntityStore> target, boolean automatic) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid() || target == null || !target.isValid() || target.equals(ref) || target.getStore() != store) { say(player, "Aim at a creature or player within five blocks."); return; }
        if (store.getComponent(target, NPCEntity.getComponentType()) == null && store.getComponent(target, Player.getComponentType()) == null) { say(player, "The imprinter requires a living echoform."); return; }
        Scan scan = new Scan(store.getExternalData().getWorld(), player, target, System.nanoTime(), automatic);
        if (!validScan(scan, store)) { say(player, "Keep the target in sight within five blocks."); return; }
        scans.put(player.getUuid(), scan);
        say(player, "Imprinting echoform. Keep aiming for one second; secondary or Use restores your own form.");
    }
    private boolean validScan(Scan scan, Store<EntityStore> store) {
        Ref<EntityStore> ref = scan.player().getReference(), target = scan.target();
        if (ref == null || !ref.isValid() || !target.isValid() || ref.getStore() != store || target.getStore() != store || scan.world() != store.getExternalData().getWorld()) return false;
        if (store.getComponent(ref, DeathComponent.getComponentType()) != null || store.getComponent(target, DeathComponent.getComponentType()) != null) return false;
        var held = InventoryComponent.getItemInHand(store, ref);
        if (ItemStack.isEmpty(held) || !IMPRINTER.equals(held.getItemId())) return false;
        var source = store.getComponent(ref, TransformComponent.getComponentType());
        var dest = store.getComponent(target, TransformComponent.getComponentType());
        var head = store.getComponent(ref, HeadRotation.getComponentType());
        if (source == null || dest == null || head == null || source.getPosition().distanceSquared(dest.getPosition()) > 25) return false;
        Vector3d eye = new Vector3d(source.getPosition()).add(0, ModelComponent.getEyeHeight(ref, store), 0);
        Vector3d center = new Vector3d(dest.getPosition()).add(0, Math.max(.25, ModelComponent.getEyeHeight(target, store) * .6), 0);
        Vector3d direction = new Vector3d(center).sub(eye);
        double distance = direction.length(); if (distance < .01) return true;
        direction.div(distance);
        if (direction.dot(head.getDirection()) < .85) return false;
        for (double d = .25; d < distance - .25; d += .2) {
            Vector3d point = new Vector3d(direction).mul(d).add(eye);
            int x = (int) Math.floor(point.x), y = (int) Math.floor(point.y), z = (int) Math.floor(point.z);
            if (y < 0 || y >= ChunkUtil.HEIGHT || WorldAccess.inMemory(scan.world(),ChunkUtil.indexChunkFromBlock(x, z)) == null) return false;
            var block = scan.world().getBlockType(x, y, z);
            if (block != null && !block.getId().equals("Empty") && block.getMaterial() == com.hypixel.hytale.protocol.BlockMaterial.Solid) return false;
        }
        return true;
    }
    private void applyMorph(Scan scan, Store<EntityStore> store) {
        Ref<EntityStore> ref = scan.player().getReference();
        ModelComponent originalComponent = store.getComponent(ref, ModelComponent.getComponentType());
        ModelComponent target = store.getComponent(scan.target(), ModelComponent.getComponentType());
        if (originalComponent == null || target == null) { say(scan.player(), "This echoform has no compatible model."); return; }
        var hotbar=store.getComponent(ref,InventoryComponent.Hotbar.getComponentType());var player=store.getComponent(ref,Player.getComponentType());
        if(hotbar==null||player==null||hotbar.getActiveSlot()<0)return;
        short slot=hotbar.getActiveSlot();var instrument=hotbar.getInventory().getItemStack(slot);
        String energyToken=UUID.randomUUID().toString();
        try(var debit=GadgetEnergy.reserve(hotbar.getInventory(),slot,instrument,GadgetEnergy.cost("imprint_start")+GadgetEnergy.cost("imprint_second"),player.getGameMode()==com.hypixel.hytale.protocol.GameMode.Creative)){
            if(debit==null){say(scan.player(),"The imprinter needs more Resonant Energy.");return;}
            if(!hotbar.getInventory().setItemStackForSlot(slot,hotbar.getInventory().getItemStack(slot).withMetadata("SMImprintEnergy",com.hypixel.hytale.codec.Codec.STRING,energyToken),false).succeeded())return;
            debit.commit();
        }
        morphTokens.put(scan.player().getUuid(),energyToken);morphPaidUntil.put(scan.player().getUuid(),time.getOrDefault(scan.world().getName(),0d)+1);
        suspendRiderPose(scan.player(), store);
        Morph previous = morphs.get(scan.player().getUuid());
        Model originalModel = previous == null ? originalComponent.getModel() : previous.originalModel();
        PersistentModel persistent = store.getComponent(ref, PersistentModel.getComponentType());
        Model.ModelReference originalReference = previous != null ? previous.originalReference() : persistent == null ? null : persistent.getModelReference();
        PlayerSkinComponent originalSkin = previous == null ? store.getComponent(ref, PlayerSkinComponent.getComponentType()) : previous.originalSkin();
        PlayerSkinComponent targetSkin = store.getComponent(scan.target(), PlayerSkinComponent.getComponentType());
        PlayerSkinComponent appliedSkin = targetSkin == null ? null : new PlayerSkinComponent(targetSkin.getPlayerSkin());
        Model appearance = cosmeticModel(target.getModel(), originalModel);
        store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(appearance));
        // ModelSystems.ModelChange automatically rewrites PersistentModel. Keep the underlying
        // character reference intact so a crash/logout cannot save a permanent disguise.
        if (persistent != null && originalReference != null) persistent.setModelReference(originalReference);
        if (appliedSkin != null) store.putComponent(ref, PlayerSkinComponent.getComponentType(), appliedSkin);
        else store.tryRemoveComponent(ref, PlayerSkinComponent.getComponentType());
        morphs.put(scan.player().getUuid(), new Morph(scan.world(), scan.player(), originalModel, originalReference, originalSkin, appearance, appliedSkin));
        resumeRiderPose(scan.player(), store);
        var location = store.getComponent(ref, TransformComponent.getComponentType());
        if (location != null) GadgetEffects.use(scan.world(), "SM_Imprint", new Vector3d(location.getPosition()).add(0, 1, 0));
        say(scan.player(), "Echoform imprinted. Secondary or Use restores your original form.");
    }
    /** Preserve player collision, view height and physics: the original imprinter is an appearance disguise. */
    private static Model cosmeticModel(Model appearance, Model body) {
        return new Model(appearance.getModelAssetId(), appearance.getScale(), appearance.getRandomAttachmentIds(), appearance.getAttachments(), body.getBoundingBox(),
                appearance.getModel(), appearance.getTexture(), appearance.getGradientSet(), appearance.getGradientId(), body.getEyeHeight(), body.getCrouchOffset(), body.getSittingOffset(), body.getSleepingOffset(),
                appearance.getAnimationSetMap(), body.getCamera(), appearance.getLight(), appearance.getParticles(), appearance.getTrails(), body.getPhysicsValues(), body.getDetailBoxes(), appearance.getPhobia(), appearance.getPhobiaModelAssetId());
    }
    private void revert(UUID uuid, boolean announce) {
        Morph morph = morphs.remove(uuid);
        morphTokens.remove(uuid);morphPaidUntil.remove(uuid);
        if (morph == null) return;
        Ref<EntityStore> ref = morph.player().getReference();
        if (ref == null || !ref.isValid()) return;
        Store<EntityStore> store = ref.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (!ref.isValid()) return;
            suspendRiderPose(morph.player(), store);
            var current = store.getComponent(ref, ModelComponent.getComponentType());
            // Another mod replacing the appearance takes precedence over this temporary disguise.
            if (current != null && current.getModel() == morph.appliedModel()) {
                store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(morph.originalModel()));
                var persistent = store.getComponent(ref, PersistentModel.getComponentType());
                if (persistent != null && morph.originalReference() != null) persistent.setModelReference(morph.originalReference());
                if (morph.originalSkin() != null) store.putComponent(ref, PlayerSkinComponent.getComponentType(), new PlayerSkinComponent(morph.originalSkin().getPlayerSkin()));
                else store.tryRemoveComponent(ref, PlayerSkinComponent.getComponentType());
            }
            resumeRiderPose(morph.player(), store);
            if (announce) {
                var location = store.getComponent(ref, TransformComponent.getComponentType());
                if (location != null) GadgetEffects.use(store.getExternalData().getWorld(), "SM_Imprint_Revert", new Vector3d(location.getPosition()).add(0, 1, 0));
                say(morph.player(), "Your original form has been restored.");
            }
        });
    }
    public void tick(World world, double dt) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        double previousTime=time.getOrDefault(world.getName(),0d),currentTime=previousTime+Math.clamp(dt,0,.25);time.put(world.getName(),currentTime);
        boolean visual=(int)(previousTime*5)!=(int)(currentTime*5);
        recovery.tick(world,this::deploy,this::say);
        for (var entry : scans.entrySet()) {
            Scan scan = entry.getValue(); if (scan.world() != world) continue;
            if (!validScan(scan, store) || System.nanoTime() - scan.began() > 3_000_000_000L) { scans.remove(entry.getKey(), scan); continue; }
            if(visual){
                var origin=store.getComponent(scan.player().getReference(),TransformComponent.getComponentType());
                var target=store.getComponent(scan.target(),TransformComponent.getComponentType());
                if(origin!=null&&target!=null)GadgetEffects.beam(world,"SM_Stasis_Beam",new Vector3d(origin.getPosition()).add(0,1.3,0),new Vector3d(target.getPosition()).add(0,.7,0));
            }
            if (scan.automatic() && System.nanoTime() - scan.began() >= 1_000_000_000L && scans.remove(entry.getKey(), scan)) applyMorph(scan, store);
        }
        for (Ride ride : rides.values()) {
            if (ride.world() != world) continue;
            Ref<EntityStore> ref = ride.player().getReference();
            if (ref == null || !ref.isValid() || ref.getStore() != store || !ride.board().isValid()) { stopRide(ride); continue; }
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null || player.getMountEntityId() != ride.networkId() || store.getComponent(ref, DeathComponent.getComponentType()) != null) { stopRide(ride); continue; }
            if(currentTime>=boardPaidUntil.getOrDefault(ride.player().getUuid(),0d)){
                if(!recovery.ledger().spend(ride.receipt(),GadgetEnergy.cost("board_second"),player.getGameMode()==com.hypixel.hytale.protocol.GameMode.Creative)){
                    var transform=store.getComponent(ref,TransformComponent.getComponentType());
                    if(transform==null||surfaceNearby(world,transform.getPosition())){say(ride.player(),"Hoverboard depleted. Folded safely near the ground.");stopRide(ride);continue;}
                    // The ground-following native mount retains gravity while finishing its descent.
                    if(visual)hud.update(ride.player(),store,"Hoverboard","Energy empty / landing","The board will fold when it reaches the ground.",-1);
                }else boardPaidUntil.put(ride.player().getUuid(),currentTime+1);
            }
            ride.pose().ensure(ride.player(), store);
            var viewer = store.getComponent(ref, EntityTrackerSystems.EntityViewer.getComponentType());
            if (viewer != null && viewer.sent.containsKey(ride.board()) && mountPresented.add(ride.player().getUuid())) {
                // The native OnAdd packet may precede the first entity update on a fresh spawn.
                // Reassert once after the tracker has sent the board, never every tick.
                ride.player().getPacketHandler().write(new MountNPC(0, BOARD_ANCHOR_Y, 0, ride.networkId()));
                ride.pose().replayOwner(ride.player(), store);
            }
            // Leave mounted velocity and transforms to the native rider controller.
            if (visual) HoverboardRideEffects.pulse(store, ride.board());
        }
        for (Morph morph : morphs.values()) {
            if (morph.world() != world) continue;
            Ref<EntityStore> ref = morph.player().getReference();
            if (ref == null || !ref.isValid()) { morphs.remove(morph.player().getUuid(), morph); continue; }
            if (ref.getStore() != store || store.getComponent(ref, DeathComponent.getComponentType()) != null) revert(morph.player().getUuid(), false);
            else if(currentTime>=morphPaidUntil.getOrDefault(morph.player().getUuid(),0d)){
                if(!spendMorph(morph,store)){revert(morph.player().getUuid(),true);say(morph.player(),"Disguise ended: the imprinter is empty or no longer carried.");}
                else morphPaidUntil.put(morph.player().getUuid(),currentTime+1);
            }
        }
    }
    private void stopRide(Ride ride) {
        if (rides.get(ride.player().getUuid())!=ride) return;
        recovery.fold(ride.receipt());
        if (!rides.remove(ride.player().getUuid(), ride)) return;
        boardPaidUntil.remove(ride.player().getUuid());
        mountPresented.remove(ride.player().getUuid());
        ride.pose().restore(ride.player());
        Store<EntityStore> store = ride.world().getEntityStore().getStore();
        onWorld(ride.world(),() -> {
            // Native dismount role changes can unload/add the holder and replace its Ref.
            // Resolve its stable UUID as well so that rebuilt mount cannot become a ghost.
            var board=ride.board().isValid()?ride.board():ride.world().getEntityStore().getRefFromUUID(ride.boardId());
            if (board!=null&&board.isValid()) {
                var position = store.getComponent(board, TransformComponent.getComponentType());
                if (position != null) GadgetEffects.use(ride.world(), "SM_Hoverboard_Disengage", new Vector3d(position.getPosition()).add(0,BOARD_VISUAL_ORIGIN_Y,0));
                var mount = store.getComponent(board, NPCMountComponent.getComponentType());
                // A moving player can already belong to a different world; detach ownership before
                // the native NPC removal callback tries to restore movement through the old store.
                if (mount != null) mount.setOwnerPlayerRef(null);
                store.removeEntity(board, RemoveReason.REMOVE);
            }
        });
        Ref<EntityStore> ref = ride.player().getReference();
        if (ref == null || !ref.isValid()) return;
        Store<EntityStore> riderStore = ref.getStore();
        // Destination join owns its movement defaults and mount IDs are local to each world.
        // Restoring a source-world snapshot here could overwrite those defaults or dismount an
        // unrelated destination entity whose network ID happens to equal this old board's ID.
        if(riderStore!=store)return;
        onWorld(riderStore.getExternalData().getWorld(),() -> {
            if (!ref.isValid() || ref.getStore()!=store) return;
            Player player = riderStore.getComponent(ref, Player.getComponentType());
            if (player != null && (player.getMountEntityId() == ride.networkId() || player.getMountEntityId() == 0)) {
                player.setMountEntityId(0);
                MountPlugin.resetOriginalPlayerMovementSettings(ref, riderStore, ride.networkId());
                riderStore.putComponent(ref, MovementManager.getComponentType(), ride.originalMovement());
                ride.originalMovement().update(ride.player().getPacketHandler());
            }
        });
    }
    private static void onWorld(World world,Runnable action){if(world.isInThread())action.run();else world.execute(action);}
    private boolean spendMorph(Morph morph,Store<EntityStore> store){
        var ref=morph.player().getReference();var player=store.getComponent(ref,Player.getComponentType());if(player==null)return false;
        var inventory=InventoryComponent.getCombined(store,ref,InventoryComponent.HOTBAR_STORAGE_BACKPACK);String token=morphTokens.get(morph.player().getUuid());
        if(inventory==null||token==null)return false;
        for(short slot=0;slot<inventory.getCapacity();slot++){
            var item=inventory.getItemStack(slot);if(ItemStack.isEmpty(item)||!IMPRINTER.equals(item.getItemId()))continue;
            if(token.equals(item.getFromMetadataOrNull("SMImprintEnergy",com.hypixel.hytale.codec.Codec.STRING)))return GadgetEnergy.spend(inventory,slot,item,GadgetEnergy.cost("imprint_second"),player.getGameMode()==com.hypixel.hytale.protocol.GameMode.Creative);
        }return false;
    }
    private void suspendRiderPose(PlayerRef owner, Store<EntityStore> store) {
        var ride = rides.get(owner.getUuid());
        if (ride != null && ride.world().getEntityStore().getStore() == store) ride.pose().restoreNow(owner, store);
    }
    private void resumeRiderPose(PlayerRef owner, Store<EntityStore> store) {
        var ride = rides.get(owner.getUuid());
        if (ride != null && ride.world().getEntityStore().getStore() == store) ride.pose().ensure(owner, store);
    }
    public void cleanup(World world) {
        recovery.cleanup(world);
        for (Ride ride : List.copyOf(rides.values())) if (ride.world() == world) stopRide(ride);
        for (Morph morph : List.copyOf(morphs.values())) if (morph.world() == world) revert(morph.player().getUuid(), false);
        scans.entrySet().removeIf(entry -> entry.getValue().world() == world);
        time.remove(world.getName());
    }
    public void present(PlayerRef player,Store<EntityStore> store,String heldId){
        if(BOARD.equals(heldId)){
            hud.update(player,store,"Hoverboard",rides.containsKey(player.getUuid())?"Board engaged":"Board folded",rides.containsKey(player.getUuid())?"Move to steer. Sprint to boost. Jump to hop. Dismount or activate again to fold.":"Activate near solid ground to deploy and mount the board.",-1);
            return;
        }
        Scan scan=scans.get(player.getUuid());boolean morphed=morphs.containsKey(player.getUuid());
        hud.update(player,store,"Echoform Imprinter",scan!=null?"Reading a living echoform":morphed?"Disguise active":"Original form", "Hold primary on a creature or player for 1s. Secondary or Use restores your own form.",scan==null?-1:Math.min(1,(System.nanoTime()-scan.began())/1_000_000_000d));
    }
    public boolean presentActive(PlayerRef player,Store<EntityStore> store){
        var ride=rides.get(player.getUuid());if(ride==null||ride.world()!=store.getExternalData().getWorld())return false;
        var receipt=recovery.ledger().get(ride.receipt());if(receipt==null)return false;
        var board=HoverboardLedger.decode(receipt.item);
        hud.active(player,store,board,"Hoverboard",GadgetEnergy.charge(board)>0?"Board engaged":"Energy empty / landing","Move to steer. Dismount to fold. Recharge the folded board in a burner or charging station.");return true;
    }
    private static boolean surfaceNearby(World world, Vector3d p) {
        int x = (int) Math.floor(p.x), z = (int) Math.floor(p.z);
        if (WorldAccess.inMemory(world,ChunkUtil.indexChunkFromBlock(x, z)) == null) return false;
        for (int offset = 0; offset <= 3; offset++) {
            int y = (int) Math.floor(p.y) - offset; if (y < 0 || y >= ChunkUtil.HEIGHT) continue;
            var block = world.getBlockType(x, y, z);
            if (block != null && block.getMaterial() == com.hypixel.hytale.protocol.BlockMaterial.Solid && !block.getId().equals("Empty")) return true;
        }
        return false;
    }
    private static boolean crouching(PlayerRef player, Store<EntityStore> store) {
        Ref<EntityStore> ref = player.getReference(); if (ref == null || !ref.isValid()) return false;
        var movement = store.getComponent(ref, MovementStatesComponent.getComponentType());
        return movement != null && movement.getMovementStates().crouching;
    }
    private void say(PlayerRef player, String message) { var ref=player.getReference();if(ref!=null&&ref.isValid())hud.notice(player,ref.getStore(),"Echoform / Mobility",message,"",false); }
}
