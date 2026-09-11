package com.hexvane.strangematter.block;

import com.hexvane.strangematter.util.WorldAccess;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.BitSet;
import java.util.ArrayList;
import javax.annotation.Nonnull;

/** Refreshes saved fixture lighting after load without changing or loading blocks.
 * Native BlockSection saves RGB caches and their validity counters, but has no
 * asset-light version check. Changing BlockType.Light alone leaves those caches valid.
 */
public final class FixtureLightingRefresh extends RefSystem<ChunkStore> {
    private static final Query<ChunkStore> QUERY = Query.and(
        WorldChunk.getComponentType(), BlockChunk.getComponentType());
    private static final String[] FAMILIES = {
        "Gravitic", "Chrono", "Energetic", "Spatial", "Shade", "Insight"
    };

    @Override public Query<ChunkStore> getQuery() { return QUERY; }

    @Override public void onEntityAdded(@Nonnull Ref<ChunkStore> ref, @Nonnull AddReason reason,
            @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        var world = store.getExternalData().getWorld();
        // ChunkStore.add publishes section refs and the column's loaded reference
        // AFTER this callback. Queue behind that operation; never join from here.
        world.execute(() -> {
            if (!ref.isValid() || store.isShutdown()) return;
            var chunk = store.getComponent(ref, WorldChunk.getComponentType());
            if (chunk != null) refreshChunk(chunk);
        });
    }

    @Override public void onEntityRemove(@Nonnull Ref<ChunkStore> ref, @Nonnull RemoveReason reason,
            @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) { }

    /** Call once on the world thread when starting with chunks already in memory. */
    public static int refreshLoaded(World world) {
        world.debugAssertInTickingThread();
        var chunkStore = world.getChunkStore();
        int count = 0;
        for (long index : chunkStore.getChunkIndexes().toLongArray()) {
            // This accessor only reads a loaded reference; it cannot start a load.
            var ref = chunkStore.getChunkReference(index);
            if (ref == null || !ref.isValid()) continue;
            var chunk = chunkStore.getStore().getComponent(ref, WorldChunk.getComponentType());
            if (chunk != null) count += refreshOwnFixtures(chunk);
        }
        return count;
    }

    /** Refresh a newly loaded column, including spill from already loaded fixtures.
     * A fixture-free neighbor can itself bring a valid but obsolete global cache
     * from disk, after its fixture source was refreshed while that neighbor was absent.
     */
    public static int refreshChunk(WorldChunk chunk) {
        var world = chunk.getWorld();
        world.debugAssertInTickingThread();
        int count = 0;
        for (int x = chunk.getX() - 1; x <= chunk.getX() + 1; x++) {
            for (int z = chunk.getZ() - 1; z <= chunk.getZ() + 1; z++) {
                var ref = world.getChunkStore().getChunkReference(ChunkUtil.indexChunk(x, z));
                if (ref == null || !ref.isValid()) continue;
                var source = world.getChunkStore().getStore().getComponent(ref, WorldChunk.getComponentType());
                if (source != null) count += refreshOwnFixtures(source);
            }
        }
        return count;
    }

    /** Returns the number of source-column sections handed to native lighting. */
    private static int refreshOwnFixtures(WorldChunk chunk) {
        var world = chunk.getWorld();
        var ids = fixtureIndexes();
        var affected = new BitSet(ChunkUtil.HEIGHT_SECTIONS);
        for (int y = 0; y < ChunkUtil.HEIGHT_SECTIONS; y++) {
            if (!containsFixture(WorldAccess.section(chunk,y*ChunkUtil.SIZE), ids)) continue;
            // RGB travels at most 15 blocks, less than one 32-block section.
            // Include vertical spill; the native API includes all eight loaded
            // horizontal neighbors, invalidates packet caches, and queues flood work.
            affected.set(Math.max(0, y - 1), Math.min(ChunkUtil.HEIGHT_SECTIONS, y + 2));
        }
        for (int y = affected.nextSetBit(0); y >= 0; y = affected.nextSetBit(y + 1)) {
            world.getChunkLighting().invalidateLightInChunkSection(
                world.getChunkStore(), chunk.getX(), chunk.getZ(), y);
        }
        return affected.cardinality();
    }

    static int[] fixtureIndexes() {
        var ids = new ArrayList<Integer>();
        // Resolve against the current map, including when assets have been reloaded.
        for (var name : fixtureIds()) {
            int id = BlockType.getAssetMap().getIndex(name);
            if (id <= 0) continue;
            ids.add(id);
            var base = BlockType.getAssetMap().getAsset(id);
            // An Off fixture can also arrive with stale saved light. Invalidate its
            // cache using the actual state; never replace it with the lit base.
            for (var state : new String[]{"On", "Off"}) {
                var variant = base.getBlockForState(state);
                if (variant != null) ids.add(BlockType.getAssetMap().getIndex(variant.getId()));
            }
        }
        return ids.stream().mapToInt(Integer::intValue).distinct().toArray();
    }

    static String[] fixtureIds() {
        var names = new ArrayList<String>();
        for (var family : FAMILIES) for (var kind : new String[]{"Lamp", "Lantern"})
            names.add("SM_" + family + "_Shard_" + kind);
        names.add("SM_Lab_Lamp");
        return names.toArray(String[]::new);
    }

    static boolean containsFixture(BlockSection section, int[] ids) {
        if (section == null) return false;
        for (int id : ids) if (id > 0 && section.contains(id)) return true;
        return false;
    }
}
