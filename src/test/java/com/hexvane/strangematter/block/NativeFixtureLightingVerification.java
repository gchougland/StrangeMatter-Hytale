package com.hexvane.strangematter.block;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkLightDataBuilder;
import java.nio.file.Files;
import java.util.Arrays;
import org.bson.BsonDocument;

/** Native integration fixture. Call on the world thread with columns (0,0) and
 * (1,0) already loaded. The production refresh must never load that neighbor.
 */
public final class NativeFixtureLightingVerification {
    private static final int Y = 160;

    public static void verify(World world) throws Exception {
        world.debugAssertInTickingThread();
        var sourceChunk = loaded(world, 0, 0);
        var neighborChunk = loaded(world, 1, 0);
        require(sourceChunk != null && neighborChunk != null, "Fixture columns must be preloaded by the native harness");
        int[] ids = FixtureLightingRefresh.fixtureIndexes();
        require(Arrays.stream(ids).allMatch(id -> id > 0), "All twelve native fixture IDs resolve");
        require(!FixtureLightingRefresh.containsFixture(new BlockSection(), ids), "Empty palette is not a fixture");
        for (int id : ids) {
            var section = new BlockSection(); section.set(0, id, 0, 0);
            require(FixtureLightingRefresh.containsFixture(section, ids), "Each family/kind triggers refresh");
        }
        var ordinary = new BlockSection(); ordinary.set(0, BlockType.getAssetMap().getIndex("Soil_Dirt"), 0, 0);
        require(!FixtureLightingRefresh.containsFixture(ordinary, ids), "Ordinary native terrain is excluded");

        for (int i = 0; i < ids.length; i++) require(world.getBlockType(2 + i * 2, Y, 24).getId().equals("Empty"), "Test fixture space is empty");
        try {
            for (int i = 0; i < ids.length; i++) world.setBlock(2 + i * 2, Y, 24, BlockType.getAssetMap().getAsset(ids[i]).getId());
            int sectionY = Y >> ChunkUtil.BITS;
            var source = sourceChunk.getBlockChunk().getSectionAtIndex(sectionY);
            var neighbor = neighborChunk.getBlockChunk().getSectionAtIndex(sectionY);
            var lower = sourceChunk.getBlockChunk().getSectionAtIndex(sectionY - 1);
            var upper = sourceChunk.getBlockChunk().getSectionAtIndex(sectionY + 1);
            var distant = sourceChunk.getBlockChunk().getSectionAtIndex(sectionY + 2);
            int sample = ChunkUtil.indexBlock(2, Y, 24);

            // Serialize native block palettes, rotation/filler layers, RGB and validity
            // counters to a real disk file, then read them through the native codec.
            var savedSource = savedWhiteCache(source, sample);
            var savedNeighbor = savedWhiteCache(neighbor, sample);
            require(Arrays.equals(blockState(source), blockState(savedSource)), "Disk roundtrip preserves every source block/filler/rotation");
            installCache(source, savedSource); installCache(neighbor, savedNeighbor);
            require(source.hasLocalLight() && source.hasGlobalLight(), "Restored obsolete source cache is accepted as valid by native code");
            require(source.getLocalLight().getRedBlockLight(sample) == 15
                && source.getLocalLight().getGreenBlockLight(sample) == 15
                && source.getLocalLight().getBlueBlockLight(sample) == 15, "Old white RGB survived native disk serialization");
            require(BlockType.getAssetMap().getAsset(ids[0]).getLight().green == 0, "Current fixture asset is colored despite valid saved white cache");
            var sourceState = blockState(source); var neighborState = blockState(neighbor);
            short sourceLocal = source.getLocalChangeCounter(), sourceGlobal = source.getGlobalChangeCounter();
            short neighborLocal = neighbor.getLocalChangeCounter(), neighborGlobal = neighbor.getGlobalChangeCounter();
            short lowerLocal = lower.getLocalChangeCounter(), upperLocal = upper.getLocalChangeCounter();
            short distantLocal = distant.getLocalChangeCounter(), distantGlobal = distant.getGlobalChangeCounter();
            int loadedBefore = world.getChunkStore().getLoadedChunksCount();

            require(FixtureLightingRefresh.refreshChunk(sourceChunk) == 3, "Twelve sources deduplicate to three vertical sections");
            advanced(sourceLocal, source.getLocalChangeCounter(), "Source local cache invalidated");
            advanced(sourceGlobal, source.getGlobalChangeCounter(), "Source global cache invalidated");
            advanced(neighborGlobal, neighbor.getGlobalChangeCounter(), "Already loaded neighbor global cache invalidated");
            require(neighbor.getLocalChangeCounter() == neighborLocal, "Fixture-free neighbor's local data is preserved");
            advanced(lowerLocal, lower.getLocalChangeCounter(), "Lower boundary spill refreshed");
            advanced(upperLocal, upper.getLocalChangeCounter(), "Upper boundary spill refreshed");
            require(distantLocal == distant.getLocalChangeCounter() && distantGlobal == distant.getGlobalChangeCounter(), "Distant vertical sections stay untouched");
            require(Arrays.equals(sourceState, blockState(source)) && Arrays.equals(neighborState, blockState(neighbor)), "Refreshing light never changes blocks, fillers or rotations");

            // Simulate the inverse load order: the fixture source is already loaded,
            // while a fixture-free neighbor now arrives carrying another valid old cache.
            installCache(neighbor, savedNeighbor);
            neighborLocal = neighbor.getLocalChangeCounter(); neighborGlobal = neighbor.getGlobalChangeCounter();
            require(!FixtureLightingRefresh.containsFixture(neighbor, ids) && neighbor.hasGlobalLight(), "Late neighbor has no fixture and a valid stale cache");
            require(FixtureLightingRefresh.refreshChunk(neighborChunk) == 3, "Late neighbor finds only the adjacent fixture source");
            advanced(neighborGlobal, neighbor.getGlobalChangeCounter(), "Late-loaded neighbor cannot retain stale global light");
            require(neighbor.getLocalChangeCounter() == neighborLocal, "Late neighbor local data stays intact");

            sourceLocal = source.getLocalChangeCounter();
            require(FixtureLightingRefresh.refreshLoaded(world) == 3, "Startup pass refreshes each fixture source once");
            advanced(sourceLocal, source.getLocalChangeCounter(), "Startup pass invalidates existing fixture cache");
            require(world.getChunkStore().getLoadedChunksCount() == loadedBefore, "Neither load nor startup refresh loads extra chunks");
            System.out.println("NATIVE_FIXTURE_LIGHTING_VERIFICATION_PASSED: disk RGB cache, 12 fixture palettes, native invalidation, vertical and late-neighbor spill, startup, unchanged blocks and chunk count");
        } finally {
            for (int i = 0; i < ids.length; i++) world.setBlock(2 + i * 2, Y, 24, "Empty");
        }
    }

    private static BlockSection savedWhiteCache(BlockSection source, int sample) throws Exception {
        var detached = BlockSection.CODEC.decode(BlockSection.CODEC.encode(source));
        var local = new ChunkLightDataBuilder(detached.getLocalChangeCounter());
        local.setBlockLight(sample, (byte)15, (byte)15, (byte)15); detached.setLocalLight(local);
        var global = new ChunkLightDataBuilder(detached.getGlobalChangeCounter());
        global.setBlockLight(sample, (byte)15, (byte)15, (byte)15); detached.setGlobalLight(global);
        var path = Files.createTempFile("sm-saved-fixture-light-", ".json");
        try {
            Files.writeString(path, BlockSection.CODEC.encode(detached).asDocument().toJson());
            var saved = BlockSection.CODEC.decode(BsonDocument.parse(Files.readString(path)));
            require(saved.hasLocalLight() && saved.hasGlobalLight(), "Disk read retains native cache validity counters");
            return saved;
        } finally { Files.deleteIfExists(path); }
    }

    private static void installCache(BlockSection target, BlockSection saved) {
        target.setLocalLight(new ChunkLightDataBuilder(saved.getLocalLight(), target.getLocalChangeCounter()));
        target.setGlobalLight(new ChunkLightDataBuilder(saved.getGlobalLight(), target.getGlobalChangeCounter()));
    }

    private static int[] blockState(BlockSection section) {
        int[] values = new int[ChunkUtil.SIZE_BLOCKS * 3];
        for (int i = 0; i < ChunkUtil.SIZE_BLOCKS; i++) {
            values[i * 3] = section.get(i); values[i * 3 + 1] = section.getFiller(i); values[i * 3 + 2] = section.getRotationIndex(i);
        }
        return values;
    }

    private static WorldChunk loaded(World world, int x, int z) {
        var ref = world.getChunkStore().getChunkReference(ChunkUtil.indexChunk(x, z));
        return ref == null ? null : world.getChunkStore().getStore().getComponent(ref, WorldChunk.getComponentType());
    }
    private static void advanced(short before, short after, String message) { require(after == (short)(before + 1), message); }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
