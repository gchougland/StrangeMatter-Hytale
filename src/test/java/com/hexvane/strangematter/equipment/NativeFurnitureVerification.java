package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.research.ResearchRecipeBridge;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.packets.window.OpenWindow;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerBlockWindow;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.connectedblocks.ConnectedBlocksUtil;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import com.hypixel.hytale.server.core.universe.world.meta.state.RespawnBlock;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import org.joml.Vector3d;
import org.joml.Vector3i;

/** Uses loaded native furniture assets, actual placed block storage and native window packets. */
public final class NativeFurnitureVerification {
    private static final List<String> PIECES = List.of("Chair", "Stool", "Sofa", "Table", "Desk", "Bed",
            "Chest", "Wardrobe", "Bookshelf", "Wall_Shelf", "Cabinet", "Wall_Monitor", "Ceiling_Vent",
            "Ladder", "Window", "Sign", "Chest_Large");
    private static final Set<String> STORAGE = Set.of("Chest", "Wardrobe", "Cabinet", "Chest_Large");

    public static void verify(World world) throws Exception {
        world.debugAssertInTickingThread();
        int x = 40, y = 210, z = 16;
        var chunk = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z));
        require(chunk != null, "Furniture test uses the already loaded column");
        try (var player = NativePlayerFixture.create(world, "NativeFurniture", new Vector3d(x + .5, y, z - 2))) {
            for (String piece : PIECES) {
                String id = "SM_Resonite_" + piece;
                var item = Item.getAssetMap().getAsset(id);
                var type = BlockType.getAssetMap().getAsset(id);
                require(item != null && type != null, "Furniture loads as item and block: " + id);
                require(item.toPacket() != null && type.toPacket() != null, "Native item and block packets resolve: " + id);
                require("resonite".equals(ResearchRecipeBridge.requirement(id)), "Furniture is gated by resonite research: " + id);
                var recipe = CraftingRecipe.getAssetMap().getAsset(id + "_Recipe_Generated_0");
                require(piece.equals("Chest_Large") ? recipe == null : recipe != null && recipe.isKnowledgeRequired(),
                        "Furniture recipe requires native knowledge, joined variant has no duplicate recipe: " + id);
                for (var interaction : type.getInteractions().values())
                    require(RootInteraction.getAssetMap().getAsset(interaction) != null, "Native furniture action resolves: " + interaction);
                if (Set.of("Chair", "Stool", "Sofa").contains(piece)) {
                    require(type.getSeats() != null && type.getSeats().size() > 0, "Seating exposes native mount points: " + id);
                    require("Block_Seat".equals(type.getInteractions().get(InteractionType.Use)), "Seating uses the native sitting action: " + id);
                }
                world.setBlock(x, y, z, id);
                require(world.getBlockType(x, y, z).getId().equals(id), "Furniture can be placed: " + id);
                try {
                    if (STORAGE.contains(piece)) {
                        require(type.getCustomModelAnimation() != null && type.getCustomModelAnimation().endsWith("_closed.blockyanim"),
                                "Freshly placed storage has an explicit native closed pose: " + id);
                        require(type.getCustomModelAnimation().equals(type.toPacket().modelAnimation),
                                "Initial closed pose is present in the actual block packet: " + id);
                        var component = liveContainer(world, x, y, z);
                        require(component != null && component.getCapacity() >= 18, "Placed storage has usable native capacity: " + id);
                        var contents = component.getItemContainer();
                        var specimen = new ItemStack("SM_Insight_Shard", 5).withMetadata("FurnitureSpecimen", Codec.STRING, "kept safe");
                        contents.setItemStackForSlot((short)(contents.getCapacity() - 1), specimen, false);
                        var saved = ItemContainerBlock.CODEC.decode(ItemContainerBlock.CODEC.encode(component));
                        require(saved.getItemContainer().getItemStack((short)(contents.getCapacity() - 1)).equals(specimen), "Native storage save retains the last slot and metadata: " + id);
                        var window = new ContainerBlockWindow(x, y, z, 0, type, saved.getItemContainer());
                        var packet = player.player().getWindowManager().openWindow(player.ref(), window, player.store());
                        require(packet != null, "Native container window opens: " + id);
                        try {
                            var wire = MemorySegment.ofArray(new byte[packet.computeSize()]);
                            require(packet.serialize(wire, 0) == wire.byteSize() && OpenWindow.toObject(wire).equals(packet), "Native container packet round trip preserves contents and block identity: " + id);
                        } finally {
                            player.player().getWindowManager().closeWindow(player.ref(), window.getId(), player.store());
                            contents.dropAllItemStacks();
                        }
                    }
                    if (piece.equals("Table")) {
                        var sectionRef = world.getChunkStore().getChunkSectionReferenceAtBlock(x, y, z);
                        var section = world.getChunkStore().getStore().getComponent(sectionRef, BlockSection.getComponentType());
                        for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
                            require(world.getBlockType(x + dx, y, z + dz).getId().equals(id), "Table occupies all four native placement cells");
                            int filler = section.getFiller(x + dx, y, z + dz);
                            require(FillerBlockUtil.unpackX(filler) == dx && FillerBlockUtil.unpackZ(filler) == dz,
                                    "Table filler points to the correct square footprint anchor");
                        }
                    }
                    if (piece.equals("Bed")) {
                        require(type.getBeds() != null && type.getBeds().size() == 1, "Bed exposes one native sleeping mount");
                        var holder = chunk.getBlockComponentHolder(x, y, z);
                        require(holder != null && holder.getComponent(RespawnBlock.getComponentType()) != null, "Placed bed creates native respawn state");
                    }
                } finally {
                    world.setBlock(x, y, z, "Empty");
                }
            }
            verifyJoinedChest(world, x, y, z);
        }
        System.out.println("NATIVE_FURNITURE_VERIFICATION_PASSED: 17 real furniture assets, closed pose packets, four cell table placement, native chest joining and 36 metadata stacks saved and recovered, two chest drop list, seats, beds and actual container window packets.");
    }

    private static ItemContainerBlock liveContainer(World world, int x, int y, int z) {
        var store = world.getChunkStore().getStore();
        var sectionRef = world.getChunkStore().getChunkSectionReferenceAtBlock(x, y, z);
        var section = store.getComponent(sectionRef, BlockComponentSection.getComponentType());
        require(section != null, "Actual placed block component section exists");
        int index = ChunkUtil.indexBlock(x, y, z);
        var ref = section.getBlockReference(index);
        if (ref != null && ref.isValid()) return store.getComponent(ref, ItemContainerBlock.getComponentType());
        var holder = section.getBlockHolder(index);
        return holder == null ? null : holder.getComponent(ItemContainerBlock.getComponentType());
    }

    private static void verifyJoinedChest(World world, int x, int y, int z) {
        var expected = new ArrayList<ItemStack>();
        var sectionRef = world.getChunkStore().getChunkSectionReferenceAtBlock(x, y, z);
        var section = world.getChunkStore().getStore().getComponent(sectionRef, BlockSection.getComponentType());
        try {
            // World setBlock does not run the player placement connection pass.
            // Fill both real containers, then invoke that exact native pass.
            for (int dx = -1; dx <= 0; dx++) {
                world.setBlock(x + dx, y, z, "SM_Resonite_Chest");
                var chest = liveContainer(world, x + dx, y, z);
                require(chest != null && chest.getCapacity() == 18, "Both small chests have real eighteen slot inventories");
                for (short slot = 0; slot < 18; slot++) {
                    var stack = new ItemStack("SM_Insight_Shard", 5).withMetadata("FurnitureSpecimen", Codec.STRING, dx + ":" + slot);
                    chest.getItemContainer().setItemStackForSlot(slot, stack, false);expected.add(stack);
                }
            }
            ConnectedBlocksUtil.setConnectedBlockAndNotifyNeighbors(world.getChunkStore(),
                    BlockType.getAssetMap().getIndex("SM_Resonite_Chest"), RotationTuple.NONE,
                    new Vector3i(0, 1, 0), new Vector3i(x, y, z), sectionRef, section);
            var largeType = world.getBlockType(x, y, z);
            require(largeType.getId().equals("SM_Resonite_Chest_Large"), "Native player placement template joins two small chests");
            require(FillerBlockUtil.unpackX(section.getFiller(x - 1, y, z)) == -1,
                    "Joined chest has the native negative X filler anchor");
            var joined = liveContainer(world, x, y, z);
            require(joined != null && joined.getCapacity() == 36, "Joined inventory has thirty six slots");
            assertContents(joined, expected, "Joining keeps every stack and distinct metadata from both halves");
            var saved = ItemContainerBlock.CODEC.decode(ItemContainerBlock.CODEC.encode(joined));
            assertContents(saved, expected, "Actual native component persistence keeps all thirty six metadata stacks");
            // Native destruction uses dropAllItemStacks, the same extraction as
            // ItemContainerSystems.OnRemove, plus the block's native drop list.
            var recovered = joined.getItemContainer().dropAllItemStacks();
            require(recovered.size() == expected.size() && recovered.containsAll(expected), "Dismantling extracts all contents without quantity or metadata loss");
            var breaking = largeType.getGathering().getBreaking();
            var drops = BlockHarvestUtils.getDrops(largeType, 1, breaking.getItemId(), breaking.getDropListId());
            require(drops.size() == 1 && drops.getFirst().getItemId().equals("SM_Resonite_Chest") && drops.getFirst().getQuantity() == 2,
                    "Native joined chest drop list returns exactly two regular chests");
            world.setBlock(x, y, z, "Empty");
            require(world.getBlock(x - 1, y, z) == BlockType.EMPTY_ID, "Dismantling removes the other half's filler cell");
        } finally {
            for (int dx = -1; dx <= 0; dx++) {
                var remaining = liveContainer(world, x + dx, y, z);
                if (remaining != null) remaining.getItemContainer().dropAllItemStacks();
                world.setBlock(x + dx, y, z, "Empty");
            }
        }
    }

    private static void assertContents(ItemContainerBlock component, List<ItemStack> expected, String message) {
        var actual = new ArrayList<ItemStack>();
        for (short slot = 0; slot < component.getItemContainer().getCapacity(); slot++) {
            var stack = component.getItemContainer().getItemStack(slot);
            if (stack != null && !stack.isEmpty()) actual.add(stack);
        }
        require(actual.size() == expected.size() && actual.containsAll(expected), message);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private NativeFurnitureVerification() {}
}
