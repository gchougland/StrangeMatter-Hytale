package com.hexvane.strangematter.equipment;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.AudioComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import java.util.ArrayList;

/** Quiet entity-owned sound and short, local coil trails. No movement writes. */
final class HoverboardRideEffects {
    static final String HUM = "SM_Hoverboard_Riding_Hum";
    static final String GLIDE = "SM_Hoverboard_Glide", IDLE = "SM_Hoverboard_Idle_Lift";
    static final double RANGE = 24;

    static void attach(Holder<EntityStore> holder) {
        int sound = SoundEvent.getAssetMap().getIndex(HUM);
        if (sound == SoundEvent.EMPTY_ID) return;
        var audio = new AudioComponent();
        audio.addSound(sound);
        // Native AudioSystems replicates this once and to late viewers. Removing
        // the disposable board also removes the looping sound on each client.
        holder.putComponent(AudioComponent.getComponentType(), audio);
    }

    static Vector3d coilPosition(Vector3d origin, float yaw, int side) {
        // Native forward is -Z at yaw0, so the aft pair lies at +Z.
        // Original paired coils use X +/-5, Y2 and |Z|15 art units.
        return new Vector3d(side * 5 / 32d, MobilityTools.BOARD_VISUAL_ORIGIN_Y + 2 / 32d, 15 / 32d)
                .rotateY(yaw).add(origin);
    }

    static void pulse(Store<EntityStore> store, Ref<EntityStore> board) {
        var transform = store.getComponent(board, TransformComponent.getComponentType());
        if (transform == null) return;
        var movement = store.getComponent(board, MovementStatesComponent.getComponentType());
        var states = movement == null ? null : movement.getMovementStates();
        boolean moving = states != null && (states.walking || states.running || states.sprinting);
        float scale = states != null && states.sprinting ? 1.15f : moving ? 1 : .72f;
        var recipients = new ArrayList<Ref<EntityStore>>();
        for (var viewer : store.getExternalData().getWorld().getPlayerRefs()) {
            var ref = viewer.getReference();
            if (ref == null || !ref.isValid() || ref.getStore() != store) continue;
            var location = store.getComponent(ref, TransformComponent.getComponentType());
            if (location != null && location.getPosition().distanceSquared(transform.getPosition()) <= RANGE * RANGE) recipients.add(ref);
        }
        if (recipients.isEmpty()) return;
        float yaw = transform.getRotation().yaw();
        for (int side : new int[]{-1, 1}) {
            var position = coilPosition(transform.getPosition(), yaw, side);
            ParticleUtil.spawnParticleEffect(moving ? GLIDE : IDLE, position.x, position.y, position.z,
                    yaw, 0, 0, scale, null, null, recipients, store);
        }
    }
}
