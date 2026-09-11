package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.worldgen.GenerationColumn;
import com.hexvane.strangematter.worldgen.GenerationCoordinator;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.Opacity;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.events.ChunkSectionPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedBlockChunk;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedChunk;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedEntityChunk;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises native unpublished holders; never publishes or loads a test terrain column. */
public final class NativeGenerationSectionVerification {
    public static void verify(World world) throws Exception {
        world.debugAssertInTickingThread();
        int cx = 4096, cz = -4096;
        long index = ChunkUtil.indexChunk(cx, cz);
        require(world.getChunkStore().getChunkReference(index) == null, "Test terrain starts outside loaded columns");
        var generated = new GeneratedChunk(new GeneratedBlockChunk(index, cx, cz), new GeneratedEntityChunk(), GeneratedChunk.makeSections());
        var holder = generated.toHolder(world);
        var chunk = holder.getComponent(WorldChunk.getComponentType());
        var column = holder.getComponent(BlockChunk.getComponentType());
        var coordinator = new GenerationCoordinator(null, null);
        var terrain = new AtomicReference<GenerationColumn>();
        var observed = new AtomicInteger();
        var pendingField = GenerationCoordinator.class.getDeclaredField("pending"); pendingField.setAccessible(true);
        @SuppressWarnings("unchecked") var pending = (Map<Object, GenerationColumn>) pendingField.get(coordinator);
        var bus = HytaleServer.get().getEventBus();
        var columnHook = bus.register(EventPriority.LAST, ChunkPreLoadProcessEvent.class, world.getName(), event -> {
            if (event.getChunk() != chunk) return;
            coordinator.column(event);
            require(pending.size() == 1, "New column context exists before its section events");
            terrain.set(pending.values().iterator().next());
        });
        var sectionHook = bus.register(EventPriority.LAST, ChunkSectionPreLoadProcessEvent.class, world.getName(), event -> {
            var section = event.getHolder().getComponent(ChunkSection.getComponentType());
            if (section == null || section.getX() != cx || section.getZ() != cz) return;
            int y = section.getY();
            require(y >= 0 && y < ChunkUtil.HEIGHT_SECTIONS, "Full-column preload emits its finite section range");
            require(event.getHolder() == generated.getSections()[y], "Native event exposes the exact generated holder, not a clone");
            require(section.getChunkColumnReference() == null, "Section is unpublished during preload");
            int count = observed.incrementAndGet();
            coordinator.section(event);
            require(pending.size() == (count == ChunkUtil.HEIGHT_SECTIONS ? 0 : 1), "Generation runs only after all distinct section holders are present");
        });
        Method preload = ChunkStore.class.getDeclaredMethod("preLoadChunkAsync", long.class, Holder.class, boolean.class);
        preload.setAccessible(true);
        try {
            // Uses the real engine preload and event dispatch on an asynchronous worker,
            // without its later postLoad/store-publication stage or any chunk future.
            CompletableFuture.runAsync(() -> {
                try { require(preload.invoke(world.getChunkStore(), index, holder, true) == holder, "Native preload preserves the column holder"); }
                catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
            }).get(10, TimeUnit.SECONDS);
        } finally { sectionHook.unregister(); columnHook.unregister(); }
        require(observed.get() == ChunkUtil.HEIGHT_SECTIONS && terrain.get() != null && pending.isEmpty(), "All10 native section events finish exactly one pending column");
        require(world.getChunkStore().getChunkReference(index) == null, "Preload and collection do not load or publish the test column");
        var raw = terrain.get();
        int x = cx * 32 + 4, z = cz * 32 + 7;
        int stone = BlockType.getAssetMap().getIndex("Rock_Stone");
        raw.set(x, 31, z, "Rock_Stone"); raw.set(x, 32, z, "SM_Resonite_Ore");
        var low = generated.getSections()[0].getComponent(BlockSection.getComponentType());
        var high = generated.getSections()[1].getComponent(BlockSection.getComponentType());
        require(low.get(x, 31, z) == stone && high.get(x, 32, z) == BlockType.getAssetMap().getIndex("SM_Resonite_Ore"),
                "Global signed coordinates edit the exact raw sections across the31/32 boundary");
        Method cached = BlockChunk.class.getDeclaredMethod("getSectionAtBlockY", int.class);
        require(cached.invoke(column, 31) == low && cached.invoke(column, 32) == high, "Native BlockChunk cache observes those same BlockSection objects");
        var fluid = generated.getSections()[1].ensureAndGetComponent(FluidSection.getComponentType());
        fluid.setFluid(x, 34, z, 1, (byte) 1);
        require(raw.fluid(x, 34, z) == 1, "Fluid exclusion reads the raw section holder");
        raw.set(x, 35, z, "Rock_Stone"); raw.set(x, 45, z, "SM_Lab_Cyan_Glass");
        require(BlockType.getAssetMap().getAsset("SM_Lab_Cyan_Glass").getOpacity() == Opacity.Transparent, "Height regression uses a genuinely transparent block");
        raw.updateHeight(x, z);
        require(raw.height(x, z) == 35, "Generation height agrees with native nontransparent height semantics");
        require(((Short) BlockChunk.class.getMethod("updateHeight", int.class, int.class).invoke(column, x, z)) == 35,
                "Actual native updateHeight independently returns the same value");
        verifyFurnishing(raw, generated, x + 1, 33, z);
        int seen = observed.get();
        coordinator.section(new ChunkSectionPreLoadProcessEvent(world, generated.getSections()[0], true, System.nanoTime()));
        require(pending.isEmpty() && observed.get() == seen, "Repeated section event cannot run completed generation again");
        coordinator.column(new ChunkPreLoadProcessEvent(holder, chunk, false, System.nanoTime()));
        require(pending.isEmpty(), "A column loaded from an existing save never enters generation");
        var cubic = ChunkStore.REGISTRY.newHolder();
        cubic.putComponent(ChunkSection.getComponentType(), new ChunkSection(null, cx, 20, cz, true));
        var cubicBlocks = cubic.ensureAndGetComponent(BlockSection.getComponentType());
        cubicBlocks.set(2, 641, 3, stone, 0, 0);
        coordinator.section(new ChunkSectionPreLoadProcessEvent(world, cubic, true, System.nanoTime()));
        require(pending.isEmpty() && cubicBlocks.get(2, 641, 3) == stone, "Independent cubic generation does not retrofit a column or wait for unrelated sections");
        require(world.getChunkStore().getChunkReference(index) == null && world.getChunkStore().getChunkSectionReference(cx, 1, cz) == null,
                "Every tested edit remains in unpublished holders, without new loaded chunks");
        System.out.println("NATIVE_GENERATION_SECTION_VERIFICATION_PASSED: actual asynchronous preload events, exact holder/cache identity, single complete column, raw fluids, native transparent height, furnishing holders and metadata, filler exclusion, saved-column and independent cubic isolation; no test chunks loaded.");
    }

    private static void verifyFurnishing(GenerationColumn terrain, GeneratedChunk generated, int x, int y, int z) {
        terrain.set(x, y, z, "SM_Resonite_Chest");
        var section = generated.getSections()[ChunkUtil.indexSection(y)];
        var blocks = section.getComponent(BlockComponentSection.getComponentType());
        int cell = ChunkUtil.indexBlock(x, y, z);
        var chest = blocks.getBlockHolder(cell);
        var configured = BlockType.getAssetMap().getAsset("SM_Resonite_Chest").getBlockEntity();
        require(chest != null && chest != configured, "Fresh furniture gets a cloned native component holder after the earlier native preprocessor");
        var inventory = chest.getComponent(ItemContainerBlock.getComponentType());
        require(inventory != null && inventory.getCapacity() == configured.getComponent(ItemContainerBlock.getComponentType()).getCapacity(),
                "Generated chest retains its native storage configuration");
        var specimen = new ItemStack("SM_Insight_Shard", 5).withMetadata("GenerationSpecimen", Codec.STRING, "preserved");
        inventory.getItemContainer().setItemStackForSlot((short) 0, specimen, false);
        terrain.set(x, y, z, "SM_Resonite_Chest", 1, 0);
        require(blocks.getBlockHolder(cell) == chest && inventory.getItemContainer().getItemStack((short) 0).equals(specimen),
                "Rotating the same fresh furniture preserves its holder and inventory payload");
        terrain.set(x + 1, y, z, "SM_Resonite_Chest", 0, FillerBlockUtil.pack(1, 0, 0));
        require(blocks.getBlockHolder(ChunkUtil.indexBlock(x + 1, y, z)) == null, "A native filler cell does not get a duplicate storage entity");
        terrain.set(x, y, z, "SM_Resonite_Cabinet");
        require(blocks.getBlockHolder(cell) != chest, "Replacing a generated block replaces its prior component holder");
        terrain.set(x, y, z, "Empty");
        require(blocks.getBlockHolder(cell) == null, "Removing a generated furnishing removes its stale component holder");
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
