package com.hexvane.strangematter.ui;

import com.google.gson.JsonParser;
import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPage;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.protocol.SmartMoveType;
import com.hypixel.hytale.protocol.packets.window.OpenWindow;
import com.hypixel.hytale.protocol.packets.window.UpdateWindow;
import com.hypixel.hytale.protocol.packets.window.CloseWindow;
import com.hypixel.hytale.protocol.packets.inventory.MoveItemStack;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ValidatedWindow;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventoryUtils;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.EmptyItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.joml.Vector3d;

/** Native packet/backend coverage; the client still needs a live drag and split interaction check. */
public final class NativeMachineInventoryVerification {
    public static void verify(World world) throws Exception {
        world.debugAssertInTickingThread();
        try (var lifecycle = new MachineWindowLifecycle();
             var fixture = NativePlayerFixture.create(world, "NativeMachineInventory", new Vector3d(24.5, 210, 24.5))) {
            var storage = fixture.store().getComponent(fixture.ref(), InventoryComponent.Storage.getComponentType()).getInventory();
            var hotbar = fixture.hotbar();
            var input = new SimpleItemContainer((short) 20);
            var output = new SimpleItemContainer((short) 10);
            for (short slot = 0; slot < input.getCapacity(); slot++) input.setSlotFilter(FilterActionType.ADD, slot,
                    (action, container, index, item) -> ItemStack.isEmpty(item) || item.getItemId().equals("SM_Resonite_Ingot"));
            var permitted = new AtomicBoolean(true);
            var page = new TestPage(fixture, input, output, permitted);
            require(fixture.player().getPageManager().openCustomPageWithWindows(fixture.ref(), fixture.store(), page, page.panel.windows()),
                    "Native custom page opens both real machine windows");
            int in = page.panel.windows()[0].getId(), out = page.panel.windows()[1].getId();
            require(in > 0 && out > 0 && in != out, "Distinct positive section IDs are allocated before UI build");
            var initial = fixture.packets().ofType(CustomPage.class).getLast();
            require(integer(initial, "#MachineInputGrid.InventorySectionId") == in
                    && integer(initial, "#MachineOutputGrid.InventorySectionId") == out
                    && integer(initial, "#PlayerStorageGrid.InventorySectionId") == InventoryComponent.STORAGE_SECTION_ID
                    && integer(initial, "#PlayerHotbarGrid.InventorySectionId") == InventoryComponent.HOTBAR_SECTION_ID,
                    "UI binds actual input/output IDs plus distinct native storage and hotbar, without backpack");
            require(initial.eventBindings.length == 75 * 7, "Every visible native cell has explicit scoped gesture callbacks");
            require(arraySize(initial, "#PlayerStorageGrid.Slots") == 36 && arraySize(initial, "#PlayerHotbarGrid.Slots") == 9,
                    "All main inventory and hotbar slots are present");
            for (String grid : new String[] {"#MachineInputGrid", "#MachineOutputGrid", "#PlayerStorageGrid", "#PlayerHotbarGrid"}) {
                for (int slot = 0; slot < arraySize(initial, grid + ".Slots"); slot++)
                    require(slotAddress(initial, grid, slot) == slot, "Every visible item and empty drop target has its actual inventory slot index");
            }
            var open = fixture.packets().ofType(OpenWindow.class);
            require(open.stream().anyMatch(p -> p.id == in && p.inventory.capacity == 20)
                    && open.stream().anyMatch(p -> p.id == out && p.inventory.capacity == 10), "Native windows carry real section capacities");
            for (var packet : open) {
                var memory = MemorySegment.ofArray(new byte[packet.computeSize()]);
                packet.serialize(memory, 0);
                require(OpenWindow.toObject(memory).equals(packet), "Native open window packet round trip is exact");
            }
            var specimen = new ItemStack("SM_Resonite_Ingot", 10).withMetadata("InventorySpecimen", Codec.STRING, "keep full metadata");
            storage.setItemStackForSlot((short) 35, specimen, false);
            moveRendered(fixture, initial, "#PlayerStorageGrid", 35, 10, "#MachineInputGrid", 19);
            require(ItemStack.isEmpty(storage.getItemStack((short) 35)) && input.getItemStack((short) 19).equals(specimen),
                    "A drag packet using the last displayed storage and empty machine slot addresses moves the exact stack");
            moveRendered(fixture, initial, "#MachineInputGrid", 19, 10, "#PlayerHotbarGrid", 8);
            require(ItemStack.isEmpty(input.getItemStack((short) 19)) && hotbar.getItemStack((short) 8).equals(specimen),
                    "Displayed machine and hotbar addresses also support extraction into the last empty hotbar slot");
            hotbar.setItemStackForSlot((short) 8, null, false);
            storage.setItemStackForSlot((short) 1, specimen, false);
            // The initial CustomPage is deliberately not acknowledged before these actual native moves.
            move(fixture, InventoryComponent.STORAGE_SECTION_ID, 1, 3, in, 2);
            require(input.getItemStack((short) 2).equals(specimen.withQuantity(3))
                    && storage.getItemStack((short) 1).equals(specimen.withQuantity(7)), "Native split quantity preserves metadata under a pending UI ACK");
            move(fixture, in, 2, 3, InventoryComponent.HOTBAR_SECTION_ID, 1);
            require(hotbar.getItemStack((short) 1).equals(specimen.withQuantity(3)) && ItemStack.isEmpty(input.getItemStack((short) 2)),
                    "Native hotbar is writable from a machine slot with exact stack payload");
            move(fixture, InventoryComponent.HOTBAR_SECTION_ID, 1, 3, out, 0);
            require(hotbar.getItemStack((short) 1).equals(specimen.withQuantity(3)) && output.isEmpty(), "Output insertion is rejected even when the raw output container allows ADD");
            var finished = new ItemStack("SM_Resonite_Ingot", 5).withMetadata("ResultSpecimen", Codec.STRING, "finished");
            output.setItemStackForSlot((short) 0, finished, false);
            move(fixture, out, 0, 5, InventoryComponent.STORAGE_SECTION_ID, 2);
            require(storage.getItemStack((short) 2).equals(finished) && output.isEmpty(), "Finished stack can be picked up with full metadata");
            hotbar.setItemStackForSlot((short) 2, new ItemStack("SM_Insight_Shard", 1), false);
            move(fixture, InventoryComponent.HOTBAR_SECTION_ID, 2, 1, in, 0);
            require(input.isEmpty() && !ItemStack.isEmpty(hotbar.getItemStack((short) 2)), "Underlying recipe input filter is preserved beneath the per-viewer guard");
            InventoryUtils.smartMoveItem(fixture.ref(), InventoryComponent.STORAGE_SECTION_ID, 1, 7,
                    SmartMoveType.PutInHotbarOrWindow, PlayerSettings.defaults(), fixture.store());
            require(input.getItemStack((short) 0).equals(specimen.withQuantity(7)), "Native shift move routes storage into the actual input window");
            verifyOtherViewer(world, fixture, input, output, in);
            verifyGestureCallbacks(fixture, initial, page.panel, input, output, storage, hotbar, permitted);
            NativeMachineInventoryDragVerification.verify(fixture, initial, page.panel, input, output, storage, hotbar, permitted);
            permitted.set(false);
            InventoryUtils.smartMoveItem(fixture.ref(), InventoryComponent.HOTBAR_SECTION_ID, 1, 3,
                    SmartMoveType.PutInHotbarOrWindow, PlayerSettings.defaults(), fixture.store());
            require(input.getItemStack((short) 0).equals(specimen.withQuantity(7)), "Delegate guard also rejects native smart move target traversal which skips ValidatedWindow");
            var before = input.getItemStack((short) 0);
            storage.setItemStackForSlot((short) 3, specimen.withQuantity(1), false);
            InventoryUtils.smartMoveItem(fixture.ref(), InventoryComponent.STORAGE_SECTION_ID, 3, 1,
                    SmartMoveType.EquipOrMergeStack, PlayerSettings.defaults(), fixture.store());
            require(input.getItemStack((short) 0).equals(before), "Native double-click merge cannot drain a frozen machine");
            move(fixture, in, 0, 7, InventoryComponent.STORAGE_SECTION_ID, 3);
            require(input.getItemStack((short) 0).equals(specimen.withQuantity(7)), "Recovery or permission freeze rejects direct machine extraction");
            InventoryUtils.smartMoveItem(fixture.ref(), in, 0, 7, SmartMoveType.PutInHotbarOrWindow, PlayerSettings.defaults(), fixture.store());
            var guardedInput = ((ContainerWindow) page.panel.windows()[0]).getItemContainer();
            require(!guardedInput.moveItemStackFromSlot((short) 0, storage).succeeded()
                    && input.getItemStack((short) 0).equals(specimen.withQuantity(7)),
                    "Frozen generic quantity and whole-stack moves fail normally without the SDK null transaction exception");
            fixture.player().getWindowManager().validateWindows(fixture.ref(), fixture.store());
            require(fixture.player().getWindowManager().getWindow(in) == page.panel.windows()[0] && page.panel.isOpen(),
                    "Temporary mutation freezes preserve both native window IDs and the client's drag targets");
            permitted.set(true);
            require(!CompletableFuture.supplyAsync(() -> ((ValidatedWindow) page.panel.windows()[0]).validate(fixture.ref(), fixture.store())).get(),
                    "Off-thread validation rejects before touching ECS");
            var projection = new UICommandBuilder(); page.panel.draw(projection,new UIEventBuilder());
            require(Arrays.stream(projection.getCommands()).filter(c -> c.selector.endsWith(".Slots")).noneMatch(c -> c.data.contains("InventorySpecimen") || c.data.contains("ResultSpecimen")),
                    "Custom UI projections omit server BSON while native containers retain it");
            var unchanged = new UICommandBuilder(); page.panel.draw(unchanged,new UIEventBuilder());
            require(unchanged.getCommands().length == 0, "Unchanged live frames do not replace draggable slot arrays");
            var named = new ItemStack("SM_Insight_Shard", 1).withMetadata(
                    com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata.KEYED_CODEC,
                    new com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata(
                            com.hypixel.hytale.server.core.Message.raw("Research Notes: Gravity anomalies"),
                            com.hypixel.hytale.server.core.Message.raw("A specific research experiment.")));
            hotbar.setItemStackForSlot((short)2,named,false);
            var namedFrame=new UICommandBuilder();page.panel.draw(namedFrame,new UIEventBuilder());
            require(Arrays.stream(namedFrame.getCommands()).anyMatch(c->c.selector.startsWith("#PlayerHotbarGrid[")&&c.selector.endsWith(".Slots")
                    &&c.data.contains("Gravity anomalies")&&c.data.contains("A specific research experiment.")&&!c.data.contains("ItemDisplay")),
                    "Explicit native slot Name and Description preserve named item tooltips without server BSON");
            hotbar.setItemStackForSlot((short)2,named.withMetadata(
                    com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata.KEYED_CODEC,
                    new com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata(
                            com.hypixel.hytale.server.core.Message.raw("Research Notes: Energetic rifts"),null)),false);
            var renamedFrame=new UICommandBuilder();page.panel.draw(renamedFrame,new UIEventBuilder());
            require(Arrays.stream(renamedFrame.getCommands()).anyMatch(c->c.selector.startsWith("#PlayerHotbarGrid[")&&c.selector.endsWith(".Slots")&&c.data.contains("Energetic rifts")),
                    "A display-only change refreshes tooltips even when item ID, quantity and durability are unchanged");
            fixture.player().getWindowManager().updateWindows();
            require(!fixture.packets().ofType(UpdateWindow.class).isEmpty(), "Native container changes drive native window updates");
            fixture.player().getPageManager().setPage(fixture.ref(), fixture.store(), Page.None, false);
            require(fixture.player().getWindowManager().getWindow(in) == null && fixture.player().getWindowManager().getWindow(out) == null,
                    "Page dismissal removes both native section IDs");
            int closePackets = fixture.packets().ofType(CloseWindow.class).size();
            require(closeFromClient(fixture, in) && closeFromClient(fixture, out) && closeFromClient(fixture, in),
                    "Client echoes and repeated closes for dismissed mod windows are consumed idempotently");
            require(fixture.packets().ofType(CloseWindow.class).size() == closePackets,
                    "Duplicate client closes neither invoke invalid native close nor send another close echo");
            move(fixture, in, 0, 7, InventoryComponent.STORAGE_SECTION_ID, 4);
            require(input.getItemStack((short) 0).equals(before), "Delayed stale section move cannot change a dismissed machine");
            require(input.getItemStack((short) 0).equals(specimen.withQuantity(7)), "Closing never removes a speculative cursor item from its real source slot");
            var one = new TestPage(fixture, new SimpleItemContainer((short) 1), EmptyItemContainer.INSTANCE, permitted);
            require(one.panel.windows().length == 1 && fixture.player().getPageManager().openCustomPageWithWindows(fixture.ref(), fixture.store(), one, one.panel.windows()),
                    "A one-slot note input opens without a phantom zero-capacity output window");
            require(!JsonParser.parseString(command(fixture.packets().ofType(CustomPage.class).getLast(), "#MachineOutputGrid.Visible")).getAsJsonObject().get("0").getAsBoolean(),
                    "Missing output grid is hidden");
            int next = one.panel.windows()[0].getId();
            gesture(fixture,initial,"#MachineInputGrid",0,com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.SlotClicking);
            gesture(fixture,initial,"#PlayerStorageGrid",6,com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Dropped);
            settle(fixture);
            require(input.getItemStack((short)0).equals(before)&&ItemStack.isEmpty(storage.getItemStack((short)6)),
                    "Old page gesture bindings cannot mutate its containers after a replacement page opens");
            require(closeFromClient(fixture, in) && fixture.player().getWindowManager().getWindow(next) == one.panel.windows()[0],
                    "A delayed old close cannot close a replacement page's inventory");
            require(closeFromClient(fixture, next) && fixture.player().getWindowManager().getWindow(next) == null,
                    "A first genuine client close still closes its live native window");
            require(closeFromClient(fixture, next), "A repeated live close is harmless");
            fixture.player().getPageManager().setPage(fixture.ref(), fixture.store(), Page.None, false);
            var foreign = new ContainerWindow(new SimpleItemContainer((short) 1));
            fixture.player().getWindowManager().openWindow(fixture.ref(), foreign, fixture.store());
            require(!closeFromClient(fixture, foreign.getId()), "Unrelated windows retain native packet handling");
            fixture.packets().handleCloseWindow(new CloseWindow(foreign.getId()), fixture.owner(), fixture.ref(), world, fixture.store());
            require(fixture.player().getWindowManager().getWindow(foreign.getId()) == null, "An unrelated native container still closes normally");
            fixture.reattach();
            require(closeFromClient(fixture, in) && closeFromClient(fixture, next),
                    "Native removal/reentry retains close tombstones despite replacing the player entity reference");
        }
        System.out.println("NATIVE_MACHINE_INVENTORY_VERIFICATION_PASSED: addressed empty and occupied slots, wire-decoded moves, metadata splits, filters, pending ACK moves, frozen shift guards with stable windows, all storage and hotbar, duplicate client closes, unrelated windows and absent output. Client drag behavior remains a live playtest requirement.");
    }

    private static void move(NativePlayerFixture f, int from, int fromSlot, int quantity, int to, int toSlot) {
        InventoryUtils.moveItem(f.ref(), from, fromSlot, quantity, to, toSlot, f.store());
    }
    static void gesture(NativePlayerFixture f, CustomPage frame, String grid, int slot,
                                com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType type) {
        String selector=MachineInventoryPanel.cell(grid,slot);
        var binding=Arrays.stream(frame.eventBindings).filter(b->b.selector.equals(selector)&&b.type==type).findFirst().orElseThrow();
        var data=JsonParser.parseString(binding.data).getAsJsonObject();
        String versionProperty=data.get("@SMInventoryVersion").getAsString();
        require(versionProperty.equals(MachineInventoryPanel.revisionCell(grid,slot)+".Value"),
                "The unchanged native binding reads its own current hidden TextField Value");
        data.addProperty("@SMInventoryVersion",JsonParser.parseString(command(frame,versionProperty)).getAsJsonObject().get("0").getAsString());
        var event=new com.hypixel.hytale.protocol.packets.interface_.CustomPageEvent(
                com.hypixel.hytale.protocol.packets.interface_.CustomPageEventType.Data,data.toString());
        var wire=MemorySegment.ofArray(new byte[event.computeSize()]);event.serialize(wire,0);
        require(PacketAdapters.__handleInbound(f.packets(),com.hypixel.hytale.protocol.packets.interface_.CustomPageEvent.toObject(wire)),
                "Actual serialized cell binding is owned before the native page ACK gate");
        f.world().consumeTaskQueue();
    }
    static void project(MachineInventoryPanel panel, CustomPage frame) {
        var commands=new UICommandBuilder();var events=new UIEventBuilder();panel.draw(commands,events);
        require(events.getEvents().length==0,"Inventory redraws never replace or add client callbacks");
        var delta=new CustomPage(TestPage.class.getName(),false,false,CustomPageLifetime.CanDismissOrCloseThroughInteraction,commands.getCommands(),events.getEvents());
        var wire=MemorySegment.ofArray(new byte[delta.computeSize()]);delta.serialize(wire,0);
        var decoded=CustomPage.toObject(wire);
        // Model only the published property values. All input keeps the original binding
        // array, and values arrive through actual serialized native UI commands.
        frame.commands=java.util.stream.Stream.concat(Arrays.stream(frame.commands),Arrays.stream(decoded.commands))
                .toArray(com.hypixel.hytale.protocol.packets.interface_.CustomUICommand[]::new);
    }
    static void settle(NativePlayerFixture f) throws InterruptedException {
        // This isolated fixture is on its test world's thread. Let the real scheduled fallback
        // enqueue, then drain that same world queue; no production scheduler is replaced.
        Thread.sleep(250);f.world().consumeTaskQueue();
    }
    private static void verifyGestureCallbacks(NativePlayerFixture f, CustomPage frame, MachineInventoryPanel panel,
                                               ItemContainer input, ItemContainer output, ItemContainer storage,
                                               ItemContainer hotbar, AtomicBoolean permitted) throws Exception {
        var press=com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.SlotClicking;
        var drop=com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Dropped;
        var completed=com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.SlotMouseDragCompleted;
        var specimen=new ItemStack("SM_Resonite_Ingot",10).withMetadata("GestureSpecimen",Codec.STRING,"unchanged metadata");
        storage.setItemStackForSlot((short)34,specimen,false);
        project(panel,frame);
        gesture(f,frame,"#PlayerStorageGrid",34,press);
        gesture(f,frame,"#MachineInputGrid",18,drop);
        settle(f);
        require(ItemStack.isEmpty(storage.getItemStack((short)34))&&specimen.equals(input.getItemStack((short)18)),
                "Custom drag release moves the exact stack without a native MoveItemStack packet");
        gesture(f,frame,"#MachineInputGrid",18,completed);settle(f);
        require(specimen.equals(input.getItemStack((short)18)),"A second release callback cannot repeat the completed gesture");
        project(panel,frame);
        gesture(f,frame,"#MachineInputGrid",18,press);
        project(panel,frame);
        gesture(f,frame,"#PlayerHotbarGrid",7,press);settle(f);
        require(specimen.equals(hotbar.getItemStack((short)7))&&ItemStack.isEmpty(input.getItemStack((short)18)),
                "Click pickup then click place uses the same metadata preserving transfer");
        project(panel,frame);
        gesture(f,frame,"#PlayerHotbarGrid",7,press);
        gesture(f,frame,"#MachineInputGrid",18,drop);
        moveRendered(f,frame,"#PlayerHotbarGrid",7,3,"#MachineInputGrid",18);settle(f);
        require(specimen.withQuantity(7).equals(hotbar.getItemStack((short)7))&&specimen.withQuantity(3).equals(input.getItemStack((short)18)),
                "Native split packet wins over the pending custom whole stack fallback exactly once");
        moveRendered(f,frame,"#PlayerHotbarGrid",7,3,"#MachineInputGrid",18);
        require(input.getItemStack((short)18).getQuantity()==3,"Late duplicate native move cannot extract another remainder");
        project(panel,frame);
        gesture(f,frame,"#PlayerHotbarGrid",7,press);
        gesture(f,frame,"#MachineOutputGrid",9,drop);settle(f);
        require(ItemStack.isEmpty(output.getItemStack((short)9))&&hotbar.getItemStack((short)7).getQuantity()==7,
                "Custom release respects output insertion filters");
        project(panel,frame);
        gesture(f,frame,"#PlayerHotbarGrid",7,press);
        gesture(f,frame,"#MachineInputGrid",17,drop);
        permitted.set(false);settle(f);permitted.set(true);
        require(ItemStack.isEmpty(input.getItemStack((short)17)),"A checkpoint blocks an already pending custom release");
        project(panel,frame);
        gesture(f,frame,"#PlayerHotbarGrid",7,press);
        gesture(f,frame,"#MachineInputGrid",17,drop);
        hotbar.setItemStackForSlot((short)7,specimen.withQuantity(6),false);settle(f);
        require(ItemStack.isEmpty(input.getItemStack((short)17)),"Concurrent source change rejects a stale drag snapshot");
        hotbar.setItemStackForSlot((short)7,null,false);input.setItemStackForSlot((short)18,null,false);
        storage.setItemStackForSlot((short)33,specimen,false);project(panel,frame);
        moveRendered(f,frame,"#PlayerStorageGrid",33,3,"#MachineInputGrid",16);
        gesture(f,frame,"#PlayerStorageGrid",33,press);
        gesture(f,frame,"#MachineInputGrid",16,drop);settle(f);
        require(storage.getItemStack((short)33).getQuantity()==7&&input.getItemStack((short)16).getQuantity()==3,
                "Native first delivery rejects a later custom press on the old displayed stack");
        project(panel,frame);
        gesture(f,frame,"#PlayerStorageGrid",33,press);
        gesture(f,frame,"#MachineInputGrid",16,drop);settle(f);
        require(ItemStack.isEmpty(storage.getItemStack((short)33))&&input.getItemStack((short)16).getQuantity()==10,
                "A newly displayed revision permits a deliberate second move to the same destination");
        input.setItemStackForSlot((short)16,null,false);
        var other=new ItemStack("SM_Insight_Shard",10).withMetadata("GestureSpecimen",Codec.STRING,"other stack");
        storage.setItemStackForSlot((short)31,specimen,false);storage.setItemStackForSlot((short)32,other,false);project(panel,frame);
        gesture(f,frame,"#PlayerStorageGrid",31,press);gesture(f,frame,"#PlayerStorageGrid",32,drop);settle(f);
        require(other.equals(storage.getItemStack((short)31))&&specimen.equals(storage.getItemStack((short)32)),
                "Custom drag can swap two occupied storage cells with both metadata payloads intact");
        moveRendered(f,frame,"#PlayerStorageGrid",31,10,"#PlayerStorageGrid",32);
        require(other.equals(storage.getItemStack((short)31))&&specimen.equals(storage.getItemStack((short)32)),
                "A late native packet cannot undo the completed swap");
        storage.setItemStackForSlot((short)31,null,false);storage.setItemStackForSlot((short)32,null,false);
    }
    private static int slotAddress(CustomPage frame, String grid, int visible) {
        return JsonParser.parseString(command(frame, MachineInventoryPanel.cell(grid, visible) + ".Slots")).getAsJsonObject().getAsJsonArray("0")
                .get(0).getAsJsonObject().get("InventorySlotIndex").getAsInt();
    }
    static void moveRendered(NativePlayerFixture f, CustomPage frame, String from, int fromSlot, int quantity, String to, int toSlot) {
        var packet = new MoveItemStack(integer(frame, from + ".InventorySectionId"), slotAddress(frame, from, fromSlot),
                quantity, integer(frame, to + ".InventorySectionId"), slotAddress(frame, to, toSlot));
        var memory = MemorySegment.ofArray(new byte[packet.computeSize()]); packet.serialize(memory, 0);
        var decoded = MoveItemStack.toObject(memory);
        require(decoded.equals(packet), "Native move wire round trip retains the exact quantity and addresses");
        if (!PacketAdapters.__handleInbound(f.packets(), decoded))
            new com.hypixel.hytale.server.core.io.handlers.game.InventoryPacketHandler(f.packets()).handle(decoded);
        f.world().consumeTaskQueue();
    }
    private static boolean closeFromClient(NativePlayerFixture f, int id) {
        var packet = new CloseWindow(id);
        var memory = MemorySegment.ofArray(new byte[packet.computeSize()]); packet.serialize(memory, 0);
        return PacketAdapters.__handleInbound(f.packets(), CloseWindow.toObject(memory));
    }
    private static void verifyOtherViewer(World world, NativePlayerFixture actor, ItemContainer input, ItemContainer output, int inputId) throws Exception {
        try (var observer = NativePlayerFixture.create(world, "NativeInventoryObserver", new Vector3d(25.5, 210, 24.5))) {
            var page = new TestPage(observer, input, output, new AtomicBoolean(true));
            require(observer.player().getPageManager().openCustomPageWithWindows(observer.ref(), observer.store(), page, page.panel.windows()),
                    "Second viewer opens its own guarded sections over the same machine");
            int observerInput = page.panel.windows()[0].getId();
            var storage = actor.store().getComponent(actor.ref(), InventoryComponent.Storage.getComponentType()).getInventory();
            storage.setItemStackForSlot((short) 5, new ItemStack("SM_Resonite_Ingot", 1), false);
            move(actor, InventoryComponent.STORAGE_SECTION_ID, 5, 1, inputId, 10);
            require(input.getItemStack((short) 10) != null && input.getItemStack((short) 10).getQuantity() == 1
                    && ItemStack.isEmpty(storage.getItemStack((short) 5)),
                    "Two-viewer notification test first performs an actual successful native move through a live section");
            observer.player().getWindowManager().updateWindows();
            require(observer.packets().ofType(UpdateWindow.class).stream().anyMatch(p -> p.id == observerInput && p.inventory.items.containsKey(10)),
                    "A native move through one viewer's delegate updates the other viewer's native window cache");
            move(actor, inputId, 10, 1, InventoryComponent.STORAGE_SECTION_ID, 5);
            observer.player().getPageManager().setPage(observer.ref(), observer.store(), Page.None, false);
        }
    }
    private static String command(CustomPage frame, String selector) {
        return Arrays.stream(frame.commands).filter(c -> selector.equals(c.selector)).toList().getLast().data;
    }
    private static int integer(CustomPage frame, String selector) {
        if(selector.endsWith(".InventorySectionId"))selector=MachineInventoryPanel.cell(selector.substring(0,selector.indexOf(".")),0)+".InventorySectionId";
        return JsonParser.parseString(command(frame, selector)).getAsJsonObject().get("0").getAsInt();
    }
    private static int arraySize(CustomPage frame, String selector) {
        String host=selector.substring(0,selector.indexOf("."));
        return (int)Arrays.stream(frame.commands).filter(c->c.selector!=null&&c.selector.startsWith(host+"[")&&c.selector.endsWith(".Slots")).count();
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class TestPage extends CustomUIPage {
        private final MachineInventoryPanel panel;
        private TestPage(NativePlayerFixture f, ItemContainer input, ItemContainer output, AtomicBoolean permitted) {
            super(f.owner(), CustomPageLifetime.CanDismissOrCloseThroughInteraction);
            panel = new MachineInventoryPanel(f.ref(), f.store(), input, output, () -> true, permitted::get);
            panel.setPage(this);
        }
        @Override public void build(Ref<EntityStore> ref, UICommandBuilder commands, UIEventBuilder events, Store<EntityStore> store) {
            MachineInventoryPanel.append(commands, null); panel.build(commands, events);
        }
        @Override public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) { panel.close(ref, store); }
    }
}
