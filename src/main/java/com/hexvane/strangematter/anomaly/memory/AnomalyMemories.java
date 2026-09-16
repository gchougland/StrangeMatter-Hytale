package com.hexvane.strangematter.anomaly.memory;

import com.hexvane.strangematter.anomaly.AnomalyRecord;
import com.hypixel.hytale.builtin.adventure.memories.MemoriesGameplayConfig;
import com.hypixel.hytale.builtin.adventure.memories.MemoriesPlugin;
import com.hypixel.hytale.builtin.adventure.memories.component.PlayerMemories;
import com.hypixel.hytale.builtin.adventure.memories.memories.Memory;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.player.UpdateMemoriesCount;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginState;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import java.util.HashSet;
import java.util.Objects;

/** Native collection only: player persistence and the Memory bench own recording and resets. */
public final class AnomalyMemories {
    private static final AnomalyMemoryProvider PROVIDER = new AnomalyMemoryProvider();
    private static MemoriesPlugin registeredPlugin;

    private AnomalyMemories() {}

    /** Call during dependent-plugin setup, before world/player memory deserialization begins. */
    public static synchronized void register(JavaPlugin owner) {
        MemoriesPlugin nativePlugin = Objects.requireNonNull(MemoriesPlugin.get(), "Hytale:Memories must load first");
        if (registeredPlugin == nativePlugin) return;
        // Native registerMemoryProvider only appends its provider list. Its codec-registration
        // loop already ran during native setup, so our owning plugin must register the codec.
        owner.getCodecRegistry(Memory.CODEC).register(AnomalyMemory.ID, AnomalyMemory.class, AnomalyMemory.CODEC);
        nativePlugin.registerMemoryProvider(PROVIDER);
        // Also publish now if native start/assets initialization preceded this setup. Native
        // subsequent catalog rebuilds obtain exactly the same entries from the provider.
        for (var entry : PROVIDER.getAllMemories().entrySet()) {
            nativePlugin.getAllMemories().computeIfAbsent(entry.getKey(), ignored -> new HashSet<>()).addAll(entry.getValue());
        }
        registeredPlugin = nativePlugin;
    }

    /** Called on the world's thread once per second for a loaded active field. */
    public static void collectNearby(World world, AnomalyRecord field) {
        if (!eligibleField(world, field)) return;
        double radius = collectionRadius();
        if (radius < 0) return;
        for (PlayerRef player : world.getPlayerRefs()) collect(world, player, field, radius * radius);
    }

    /** Encounters always enforce distance, game mode, native unlock/capacity and global dedup. */
    public static boolean collect(World world, PlayerRef player, AnomalyRecord field) {
        if (!eligibleField(world, field)) return false;
        double radius = collectionRadius();
        return radius >= 0 && collect(world, player, field, radius * radius);
    }

    private static boolean eligibleField(World world, AnomalyRecord field) {
        return world != null && field != null && field.type != null && field.active()
                && world.getName().equals(field.world)
                && Double.isFinite(field.x) && Double.isFinite(field.y) && Double.isFinite(field.z);
    }

    private static double collectionRadius() {
        MemoriesPlugin nativePlugin = MemoriesPlugin.get();
        if (nativePlugin == null || nativePlugin != registeredPlugin || nativePlugin.getState() != PluginState.ENABLED) return -1;
        double configured = PROVIDER.getCollectionRadius();
        return Double.isFinite(configured) && configured >= 0 ? configured : AnomalyMemoryProvider.DEFAULT_COLLECTION_RADIUS;
    }

    private static boolean collect(World world, PlayerRef player, AnomalyRecord field, double radiusSquared) {
        if (player == null) return false;
        var ref = player.getReference();
        var store = world.getEntityStore().getStore();
        if (ref == null || !ref.isValid() || ref.getStore() != store) return false;
        var transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null || !(field.distanceSquared(transform.getPosition()) <= radiusSquared)) return false;
        var entity = store.getComponent(ref, Player.getComponentType());
        if (entity == null || entity.getGameMode() != GameMode.Adventure) return false;
        MemoriesGameplayConfig config = MemoriesGameplayConfig.get(world.getGameplayConfig());
        if (config == null) return false;
        // Never ensure this component: the native Forgotten Temple unlock grants it/capacity.
        var carried = store.getComponent(ref, PlayerMemories.getComponentType());
        if (carried == null) return false;
        var memory = new AnomalyMemory(field.type);
        if (MemoriesPlugin.get().hasRecordedMemory(memory) || !carried.recordMemory(memory)) return false;

        NotificationUtil.sendNotification(player.getPacketHandler(),
                Message.translation("server.memories.general.collected")
                        .param("memoryTitle", Message.translation(memory.getTitle())),
                null, "NotificationIcons/MemoriesIcon.png");
        player.getPacketHandler().writeNoCache(new UpdateMemoriesCount(carried.getRecordedMemories().size()));
        String soundId = config.getMemoriesCatchSoundEventId();
        if (soundId != null) {
            int sound = SoundEvent.getAssetMap().getIndex(soundId);
            if (sound != SoundEvent.EMPTY_ID) SoundUtil.playSoundEvent3dToPlayer(ref, sound, SoundCategory.SFX,
                    field.x, field.y, field.z, store);
        }
        return true;
    }
}
