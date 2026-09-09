package com.hexvane.strangematter.block;

import com.hypixel.hytale.assetstore.map.IndexedAssetMap;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.Opacity;
import com.hypixel.hytale.server.core.asset.type.blocktick.config.RandomTickProcedure;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import java.util.concurrent.ThreadLocalRandom;

/** Runs only for randomly ticked, loaded grass sections; no world scan or chunk load. */
public final class AnomalousGrassService implements RandomTickProcedure {
    public static final BuilderCodec<AnomalousGrassService> CODEC =
        BuilderCodec.builder(AnomalousGrassService.class, AnomalousGrassService::new).build();

    @Override public void onRandomTick(Store<ChunkStore> store, CommandBuffer<ChunkStore> buffer,
            BlockSection source, int x, int y, int z, int blockId, BlockType blockType) {
        BlockSection above = section(store, buffer, x, y + 1, z);
        if (above == null) return;
        BlockType covering = BlockType.getAssetMap().getAsset(above.get(x, y + 1, z));
        if (covering != null && covering.getMaterial() == BlockMaterial.Solid
                && covering.getOpacity() == Opacity.Solid) {
            int dirt = BlockType.getAssetMap().getIndex("Soil_Dirt");
            if (dirt != IndexedAssetMap.NOT_FOUND)
                source.set(x, y, z, dirt, RotationTuple.NONE_INDEX, FillerBlockUtil.NO_FILLER);
            return;
        }
        // Original AnomalousGrassBlock: one 1/100 trial, then one uniform offset
        // in [-1,1]^3. This deliberately cannot infect dirt or other mod's soils.
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextInt(100) != 0) return;
        int tx = x + random.nextInt(-1, 2), ty = y + random.nextInt(-1, 2), tz = z + random.nextInt(-1, 2);
        BlockSection target = section(store, buffer, tx, ty, tz);
        if (target == null) return;
        BlockType current = BlockType.getAssetMap().getAsset(target.get(tx, ty, tz));
        if (current != null && (current.getId().equals("Soil_Grass") || current.getId().startsWith("Soil_Grass_")))
            target.set(tx, ty, tz, blockId, RotationTuple.NONE_INDEX, FillerBlockUtil.NO_FILLER);
    }

    private static BlockSection section(Store<ChunkStore> store, CommandBuffer<ChunkStore> buffer, int x, int y, int z) {
        var ref = store.getExternalData().getChunkSectionReferenceAtBlock(x, y, z);
        return ref == null || !ref.isValid() ? null : buffer.getComponent(ref, BlockSection.getComponentType());
    }
}
