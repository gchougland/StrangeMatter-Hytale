package com.hexvane.strangematter.block;

import com.hexvane.strangematter.util.WorldAccess;
import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.packets.world.PlaySoundEvent3D;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.ChangeStateInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkLightDataBuilder;
import java.nio.file.Files;
import java.util.Arrays;
import org.bson.BsonDocument;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.util.Objects;

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
        int[] paletteIds = FixtureLightingRefresh.fixtureIndexes();
        int[] ids = Arrays.stream(FixtureLightingRefresh.fixtureIds()).mapToInt(BlockType.getAssetMap()::getIndex).toArray();
        require(ids.length == 13 && paletteIds.length == 39 && Arrays.stream(paletteIds).allMatch(id -> id > 0), "All thirteen native fixtures and both switch states resolve");
        require(!FixtureLightingRefresh.containsFixture(new BlockSection(), paletteIds), "Empty palette is not a fixture");
        for (int id : paletteIds) {
            var section = new BlockSection(); section.set(0, id, 0, 0);
            require(FixtureLightingRefresh.containsFixture(section, paletteIds), "Every default/On/Off family and Lab Lamp triggers cache refresh");
        }
        var ordinary = new BlockSection(); ordinary.set(0, BlockType.getAssetMap().getIndex("Soil_Dirt"), 0, 0);
        require(!FixtureLightingRefresh.containsFixture(ordinary, ids), "Ordinary native terrain is excluded");

        for (int i = 0; i < ids.length; i++) require(world.getBlockType(2 + i * 2, Y, 24).getId().equals("Empty"), "Test fixture space is empty");
        try {
            for (int i = 0; i < ids.length; i++) world.setBlock(2 + i * 2, Y, 24, BlockType.getAssetMap().getAsset(ids[i]).getId());
            int sectionY = Y >> ChunkUtil.BITS;
            var source = WorldAccess.section(sourceChunk,sectionY*ChunkUtil.SIZE);
            var neighbor = WorldAccess.section(neighborChunk,sectionY*ChunkUtil.SIZE);
            var lower = WorldAccess.section(sourceChunk,(sectionY - 1)*ChunkUtil.SIZE);
            var upper = WorldAccess.section(sourceChunk,(sectionY + 1)*ChunkUtil.SIZE);
            var distant = WorldAccess.section(sourceChunk,(sectionY + 2)*ChunkUtil.SIZE);
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

            require(FixtureLightingRefresh.refreshChunk(sourceChunk) == 3, "Thirteen sources deduplicate to three vertical sections");
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
            verifySwitches(world,sourceChunk,ids);
            System.out.println("NATIVE_FIXTURE_LIGHTING_VERIFICATION_PASSED: disk RGB cache, 39 fixture state palettes, native toggles/sounds, Off save and cache refresh, unchanged collision/rotation/held lights, vertical and late-neighbor spill without chunk loads");
        } finally {
            for (int i = 0; i < ids.length; i++) world.setBlock(2 + i * 2, Y, 24, "Empty");
        }
    }

    private static void verifySwitches(World world,WorldChunk sourceChunk,int[] ids)throws Exception {
        try(var player=NativePlayerFixture.create(world,"NativeLampSwitch",new Vector3d(16.5,Y+3,22.5))) {
            for(int i=0;i<ids.length;i++) {
                var base=BlockType.getAssetMap().getAsset(ids[i]);
                var off=Objects.requireNonNull(base.getBlockForState("Off"));
                var on=Objects.requireNonNull(base.getBlockForState("On"));
                require(base.getLight()!=null&&on.getLight()!=null&&off.getLight()==null,"Native default and On emit while Off is dark: "+base.getId());
                require(off.getParticles()==null||off.getParticles().length==0,"Off disables emitted particles: "+base.getId());
                require(off.getInteractionHint().equals("server.interactionHints.turnon")&&on.getInteractionHint().equals("server.interactionHints.turnoff"),"Native switch key hints follow actual state");
                require(base.getHitboxTypeIndex()==off.getHitboxTypeIndex()&&base.getHitboxTypeIndex()==on.getHitboxTypeIndex(),"Switch preserves native physical collision: "+base.getId());
                require(base.getInteractionHitboxTypeIndex()==off.getInteractionHitboxTypeIndex()&&Arrays.equals(base.toPacket().modelTexture,off.toPacket().modelTexture),"Switch preserves interaction collision and native texture palette");
                var position=new Vector3i(2+i*2,Y,24);
                player.store().getComponent(player.ref(),com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType())
                        .setPosition(new Vector3d(position.x+.5,Y+1,22.5));
                player.store().tick(.01f); // Publish the real listener position into native spatial audio.
                int rotation=WorldAccess.rotation(sourceChunk,position.x,position.y,position.z);
                invokeNativeUse(player,base,position);
                require(world.getBlockType(position.x,position.y,position.z)==off,"Loaded default Use changes the actual block to Off: "+base.getId());
                require(WorldAccess.rotation(sourceChunk,position.x,position.y,position.z)==rotation,"Off transition preserves placement rotation");
                var held=new ItemStack(base.getId(),1);
                player.hotbar().setItemStackForSlot((short)0,held,false);
                require(BlockType.getAssetMap().getAsset(held.getItem().getBlockId()).getLight()!=null,"Held item retains native default light while placed copy is Off");
                invokeNativeUse(player,off,position);
                require(world.getBlockType(position.x,position.y,position.z)==on&&on.getLight().equals(base.getLight()),"Native Off Use restores exact original color and radius");
                invokeNativeUse(player,on,position);
                require(world.getBlockType(position.x,position.y,position.z)==off,"Native On Use can switch off repeatedly");
            }
            int sectionY=Y>>ChunkUtil.BITS;
            var section=WorldAccess.section(sourceChunk,sectionY*ChunkUtil.SIZE);
            int sample=ChunkUtil.indexBlock(2,Y,24);
            var saved=savedWhiteCache(section,sample);
            require(Arrays.equals(blockState(section),blockState(saved)),"Native disk roundtrip retains all Off IDs, fillers and rotations");
            // Publish the native saved Off states again, as a loaded palette would, then install its stale cache.
            for(int i=0;i<ids.length;i++) {
                int x=2+i*2,index=ChunkUtil.indexBlock(x,Y,24),id=saved.get(index);
                var type=BlockType.getAssetMap().getAsset(id);
                WorldAccess.set(sourceChunk,x,Y,24,id,type,saved.getRotationIndex(index),saved.getFiller(index),
                        com.hypixel.hytale.server.core.universe.world.SetBlockSettings.NO_UPDATE_STATE|
                        com.hypixel.hytale.server.core.universe.world.SetBlockSettings.FORCE_CHANGED);
                require(type.getLight()==null,"Reinstalled saved fixture stays Off");
            }
            installCache(section,saved);
            var before=blockState(section);
            short counter=section.getLocalChangeCounter();
            require(FixtureLightingRefresh.refreshLoaded(world)==3,"Saved Off fixtures invalidate obsolete lit RGB caches");
            advanced(counter,section.getLocalChangeCounter(),"Off cache invalidation reaches native light propagation");
            require(Arrays.equals(before,blockState(section)),"Reload light repair never re-enables Off fixtures");
            for(int i=0;i<ids.length;i++)require(world.getBlockType(2+i*2,Y,24).getLight()==null,"Every reloaded Off fixture remains dark");
            int ignite=com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent.getAssetMap().getIndex("SFX_Torch_Ignite");
            int extinguish=com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent.getAssetMap().getIndex("SFX_Torch_Off");
            require(player.packets().ofType(PlaySoundEvent3D.class).stream().filter(p->p.soundEventIndex==ignite||p.soundEventIndex==extinguish).count()==ids.length*3,"Native switch interactions emit exactly their configured short sound cues");
        }
    }

    private static void invokeNativeUse(NativePlayerFixture player,BlockType block,Vector3i position) {
        var root=Objects.requireNonNull(RootInteraction.getAssetMap().getAsset(block.getInteractions().get(InteractionType.Use)));
        require(root.getInteractionIds().length==1,"Switch Use resolves exactly one native interaction");
        var loaded=Interaction.getAssetMap().getAsset(root.getInteractionIds()[0]);
        require(loaded instanceof ChangeStateInteraction,"Switch uses Hytale ChangeState instead of a GUI or custom dispatcher");
        var action=NativeUse.CODEC.decode(ChangeStateInteraction.CODEC.encode((ChangeStateInteraction)loaded,new ExtraInfo()),new ExtraInfo());
        var manager=player.store().getComponent(player.ref(),InteractionModule.get().getInteractionManagerComponent());
        var context=InteractionContext.forInteraction(manager,player.ref(),InteractionType.Use,player.store());
        context.getMetaStore().putMetaObject(Interaction.TARGET_BLOCK,new BlockPosition(position.x,position.y,position.z));
        player.store().forEachChunk(PlayerRef.getComponentType(),(chunk,commands)->{
            for(int i=0;i<chunk.size();i++)if(chunk.getReferenceTo(i).equals(player.ref()))action.apply(player.world(),commands,context,position);
        });
    }
    /** Exposes the native block phase after resolving and decoding its actual loaded Use asset.
     * Client target acquisition/prediction is outside this server fixture. No native mutation is replaced.
     */
    private static final class NativeUse extends ChangeStateInteraction {
        private static final BuilderCodec<NativeUse> CODEC=BuilderCodec.builder(NativeUse.class,NativeUse::new,ChangeStateInteraction.CODEC).build();
        private void apply(World world,CommandBuffer<EntityStore> commands,InteractionContext context,Vector3i target) {
            super.interactWithBlock(world,commands,InteractionType.Use,context,context.getHeldItem(),target,new CooldownHandler());
        }
    }

    private static BlockSection savedWhiteCache(BlockSection source, int sample) throws Exception {
        var detached = BlockSection.CODEC.decode(BlockSection.CODEC.encode(source,new com.hypixel.hytale.codec.ExtraInfo()),new com.hypixel.hytale.codec.ExtraInfo());
        var local = new ChunkLightDataBuilder(detached.getLocalChangeCounter());
        local.setBlockLight(sample, (byte)15, (byte)15, (byte)15); detached.setLocalLight(local);
        var global = new ChunkLightDataBuilder(detached.getGlobalChangeCounter());
        global.setBlockLight(sample, (byte)15, (byte)15, (byte)15); detached.setGlobalLight(global);
        var path = Files.createTempFile("sm-saved-fixture-light-", ".json");
        try {
            Files.writeString(path, BlockSection.CODEC.encode(detached,new com.hypixel.hytale.codec.ExtraInfo()).asDocument().toJson());
            var saved = BlockSection.CODEC.decode(BsonDocument.parse(Files.readString(path)),new com.hypixel.hytale.codec.ExtraInfo());
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
