package com.hexvane.strangematter.equipment;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.packets.entities.PlayAnimation;
import com.hypixel.hytale.server.core.asset.type.itemanimation.config.ItemPlayerAnimations;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Plays mounted clips on the existing skinned avatar, without replacing it. */
public final class HoverboardRiderPose {
    static final String ASSET = "SM_Hoverboard_Rider";
    static final List<String> ANIMATIONS = List.of("SurfIdle", "SurfGlide", "SurfBoost");
    static final AnimationSlot SLOT = AnimationSlot.Action;
    private static final Map<UUID, HoverboardRiderPose> ACTIVE = new ConcurrentHashMap<>();
    private final Map<UUID, Recipient> recipients = new HashMap<>();
    private PlayerRef owner;
    private Ref<EntityStore> rider;
    private World world;
    private int entityId;
    private String animation;
    private record Recipient(PlayerRef player, String animation) { }

    /** Native Action item clips act on the avatar's existing skeleton. Never
     * substitute model, persistent model, skin components, or active-slot arrays.
     */
    void ensure(PlayerRef playerRef, Store<EntityStore> store) {
        store.assertThread();
        var ref = playerRef.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) return;
        var player = store.getComponent(ref, Player.getComponentType());
        var network = store.getComponent(ref, NetworkId.getComponentType());
        var component = store.getComponent(ref, ModelComponent.getComponentType());
        var asset = ItemPlayerAnimations.getAssetMap().getAsset(ASSET);
        if (player == null || player.getMountEntityId() == 0 || network == null || component == null
                || !compatible(component) || asset == null || !asset.getAnimations().keySet().containsAll(ANIMATIONS)) {
            restoreNow(playerRef, store); return;
        }
        if (rider != null && rider != ref) end();
        owner = playerRef; rider = ref; world = store.getExternalData().getWorld(); entityId = network.getId();
        animation = selectAnimation(player.getMountEntityId(), store);
        ACTIVE.put(owner.getUuid(), this);
        var seen = new HashSet<UUID>();
        // The local avatar exists before its own visibility tracker is populated.
        present(owner, animation, false); seen.add(owner.getUuid());
        for (var viewer : world.getPlayerRefs()) {
            if (viewer.getUuid().equals(owner.getUuid()) || !canSee(viewer, store)) continue;
            present(viewer, animation, false); seen.add(viewer.getUuid());
        }
        // Native tracking removes the entity for departed viewers. A returning
        // viewer receives one new start after the tracker has sent the entity.
        recipients.keySet().retainAll(seen);
    }

    private static boolean compatible(ModelComponent component) {
        var path = component.getModel().getModel();
        return "Characters/Player.blockymodel".equals(path) || "Characters/Player_With_Face.blockymodel".equals(path);
    }

    private static String selectAnimation(int mountId, Store<EntityStore> store) {
        var mount = store.getExternalData().getRefFromNetworkId(mountId);
        var movement = mount == null || !mount.isValid() ? null : store.getComponent(mount, MovementStatesComponent.getComponentType());
        var states = movement == null ? null : movement.getMovementStates();
        if (states == null) return "SurfIdle";
        if (states.sprinting) return "SurfBoost";
        return states.walking || states.running ? "SurfGlide" : "SurfIdle";
    }

    private boolean canSee(PlayerRef viewer, Store<EntityStore> store) {
        var ref = viewer.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) return false;
        var tracker = store.getComponent(ref, EntityTrackerSystems.EntityViewer.getComponentType());
        return tracker != null && tracker.visible.contains(rider) && tracker.sent.containsKey(rider);
    }

    private void present(PlayerRef viewer, String id, boolean force) {
        var previous = recipients.get(viewer.getUuid());
        if (!force && previous != null && previous.player() == viewer && previous.animation().equals(id)) return;
        viewer.getPacketHandler().writeNoCache(new PlayAnimation(entityId, ASSET, id, SLOT));
        recipients.put(viewer.getUuid(), new Recipient(viewer, id));
    }

    /** Reapply once after the native fresh-spawn mount reassert, never every tick. */
    void replayOwner(PlayerRef playerRef, Store<EntityStore> store) {
        ensure(playerRef, store);
        if (owner == playerRef && animation != null) present(owner, animation, true);
    }

    void restoreNow(PlayerRef playerRef, Store<EntityStore> store) {
        store.assertThread();
        if (world != null && world != store.getExternalData().getWorld()) return;
        end();
    }

    void restore(PlayerRef playerRef) {
        var source = world;
        if (source == null) return;
        if (source.isInThread()) end();
        else if (source.isAlive()) source.execute(this::end);
    }

    private void end() {
        if (owner == null) return;
        var stop = new PlayAnimation(entityId, null, null, SLOT);
        for (var recipient : recipients.values()) {
            var viewer = recipient.player();
            // Network IDs are world-local. Do not clear a reused destination ID.
            if (world.getWorldConfig().getUuid().equals(viewer.getWorldUuid()))
                viewer.getPacketHandler().writeNoCache(stop);
        }
        ACTIVE.remove(owner.getUuid(), this);
        recipients.clear(); owner = null; rider = null; world = null; animation = null; entityId = 0;
    }

    /** Stop before native entity removal invalidates its network identity. */
    public static final class RestoreOnRemove extends RefSystem<EntityStore> {
        @Override public Query<EntityStore> getQuery() { return PlayerRef.getComponentType(); }
        @Override public void onEntityAdded(Ref<EntityStore> ref, AddReason reason, Store<EntityStore> store, CommandBuffer<EntityStore> commands) { }
        @Override public void onEntityRemove(Ref<EntityStore> ref, RemoveReason reason, Store<EntityStore> store, CommandBuffer<EntityStore> commands) {
            var playerRef = commands.getComponent(ref, PlayerRef.getComponentType());
            var pose = playerRef == null ? null : ACTIVE.get(playerRef.getUuid());
            if (pose != null && pose.rider == ref) pose.end();
        }
    }
}
