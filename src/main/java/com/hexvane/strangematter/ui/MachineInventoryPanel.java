package com.hexvane.strangematter.ui;

import com.hexvane.strangematter.equipment.GadgetEnergy;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ValidatedWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.DelegateItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.inventory.transaction.*;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A shared layout over native inventory sections, never a second copy of the player's items. */
public final class MachineInventoryPanel {
    private final Ref<EntityStore> owner;
    private final Store<EntityStore> store;
    private final World world;
    private final BooleanSupplier validInventory, mayMutate;
    private final GuardedWindow input;
    private final GuardedWindow output;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, ItemStack[]> shown = new HashMap<>();
    private final Map<String, String[]> shownTooltips = new HashMap<>();
    private final Map<String, ItemStack[]> shownActual = new HashMap<>();
    private CustomUIPage page;
    private boolean sectionsBound;
    private MachineInventoryInput gestures;

    public MachineInventoryPanel(Ref<EntityStore> owner, Store<EntityStore> store,
                                 ItemContainer input, ItemContainer output, BooleanSupplier mayMutate) {
        this(owner, store, input, output, mayMutate, () -> true);
    }

    /** Keep section identity separate from short transfer checkpoints that only pause mutations. */
    public MachineInventoryPanel(Ref<EntityStore> owner, Store<EntityStore> store,
                                 ItemContainer input, ItemContainer output,
                                 BooleanSupplier validInventory, BooleanSupplier mayMutate) {
        this(owner, store, input, output, validInventory, mayMutate, true);
    }

    /** A dock is a writable native section; finished-item trays retain their take-only guard. */
    public MachineInventoryPanel(Ref<EntityStore> owner, Store<EntityStore> store,
                                 ItemContainer input, ItemContainer output,
                                 BooleanSupplier validInventory, BooleanSupplier mayMutate, boolean takeOnlyOutput) {
        this.owner = Objects.requireNonNull(owner);
        this.store = Objects.requireNonNull(store);
        this.world = store.getExternalData().getWorld();
        this.mayMutate = Objects.requireNonNull(mayMutate);
        this.validInventory = Objects.requireNonNull(validInventory);
        this.input = new GuardedWindow(guard(Objects.requireNonNull(input), false));
        this.output = new GuardedWindow(guard(Objects.requireNonNull(output), takeOnlyOutput));
    }

    /** Call before openCustomPageWithWindows. Window IDs are assigned before the page builds. */
    public void setPage(CustomUIPage page) {
        if (this.page != null && this.page != page) throw new IllegalStateException("Inventory panel already belongs to a page");
        this.page = Objects.requireNonNull(page);
    }

    public Window[] windows() {
        return java.util.stream.Stream.of(input, output).filter(window -> window.getItemContainer().getCapacity() > 0)
                .toArray(Window[]::new);
    }

    /** A native teleport/location check may close a temporarily blocked section. */
    public boolean isOpen() {
        if (!world.isInThread() || !owner.isValid() || closed.get()) return false;
        var player = store.getComponent(owner, Player.getComponentType());
        return player != null && Arrays.stream(windows()).allMatch(window ->
                window.getId() > 0 && player.getWindowManager().getWindow(window.getId()) == window);
    }

    public static void append(UICommandBuilder commands, String host) {
        commands.append(host, "StrangeMatter/MachineInventoryPanel.ui");
    }

    /** Call when rebuilding/replacing the markup while retaining this panel instance. */
    public void resetProjection() {
        world.debugAssertInTickingThread();
        sectionsBound = false;
        shown.clear();
        shownTooltips.clear();
        shownActual.clear();
        if (gestures != null) { gestures.close(); gestures = null; }
    }

    /** Build real native cells with explicit addresses for click and release callbacks. */
    public void build(UICommandBuilder commands, UIEventBuilder events) {
        world.debugAssertInTickingThread();
        if (gestures != null) gestures.close();
        var sections = new HashMap<Integer, ItemContainer>();
        for (var window : new GuardedWindow[] {input, output}) {
            if (window.getItemContainer().getCapacity() > 0) sections.put(window.getId(), window.getItemContainer());
        }
        var storage = store.getComponent(owner, InventoryComponent.Storage.getComponentType());
        var hotbar = store.getComponent(owner, InventoryComponent.Hotbar.getComponentType());
        if (storage != null) sections.put(InventoryComponent.STORAGE_SECTION_ID, storage.getInventory());
        if (hotbar != null) sections.put(InventoryComponent.HOTBAR_SECTION_ID, hotbar.getInventory());
        gestures = new MachineInventoryInput(owner, store, sections, () -> current() && isOpen());
        cells(commands, events, "#MachineInputGrid", input.getId(), input.getItemContainer());
        cells(commands, events, "#MachineOutputGrid", output.getId(), output.getItemContainer());
        cells(commands, events, "#PlayerStorageGrid", InventoryComponent.STORAGE_SECTION_ID, storage == null ? null : storage.getInventory());
        cells(commands, events, "#PlayerHotbarGrid", InventoryComponent.HOTBAR_SECTION_ID, hotbar == null ? null : hotbar.getInventory());
        sectionsBound = false; shown.clear(); shownTooltips.clear(); shownActual.clear();
        draw(commands, events);
    }
    private void cells(UICommandBuilder commands, UIEventBuilder events, String host, int section, ItemContainer container) {
        commands.clear(host);
        if (container == null) return;
        int columns = columns(host);
        for (short slot = 0; slot < container.getCapacity(); slot++) {
            String row = host + "[" + slot / columns + "]";
            if (slot % columns == 0) commands.append(host, "StrangeMatter/MachineInventoryRow.ui");
            commands.append(row, "StrangeMatter/MachineInventorySlot.ui");
            String cell = cell(host, slot);
            commands.set(cell + ".InventorySectionId", section);
            gestures.bind(events, cell, revisionCell(host, slot) + ".Value", section, slot);
        }
    }
    static int columns(String host) { return host.startsWith("#Player") ? 9 : 5; }
    static String cell(String host, int slot) { return cellHost(host, slot) + " #Slot"; }
    static String revisionCell(String host, int slot) { return cellHost(host, slot) + " #DisplayRevision"; }
    private static String cellHost(String host, int slot) { return host + "[" + slot / columns(host) + "][" + slot % columns(host) + "]"; }

    /** Refresh only changed cells, retaining each native drag control throughout live animation. */
    public void draw(UICommandBuilder commands, UIEventBuilder events) {
        world.debugAssertInTickingThread();
        if (closed.get() || !owner.isValid()) return;
        if (!sectionsBound) {
            bindSection(commands, "#MachineInput", input);
            bindSection(commands, "#MachineOutput", output);
            sectionsBound = true;
        }
        drawSlots(commands, events, "#MachineInputGrid", input.getId(), input.getItemContainer());
        drawSlots(commands, events, "#MachineOutputGrid", output.getId(), output.getItemContainer());
        var storage = store.getComponent(owner, InventoryComponent.Storage.getComponentType());
        var hotbar = store.getComponent(owner, InventoryComponent.Hotbar.getComponentType());
        drawSlots(commands, events, "#PlayerStorageGrid", InventoryComponent.STORAGE_SECTION_ID, storage == null ? null : storage.getInventory());
        drawSlots(commands, events, "#PlayerHotbarGrid", InventoryComponent.HOTBAR_SECTION_ID, hotbar == null ? null : hotbar.getInventory());
    }

    private void bindSection(UICommandBuilder commands, String selector, GuardedWindow window) {
        boolean present = window.getItemContainer().getCapacity() > 0;
        commands.set(selector + "Grid.Visible", present);
        commands.set(selector + "Label.Visible", present);
        if (!present) return;
        if (window.getId() <= 0) throw new IllegalStateException("Open inventory windows before building the custom page");
        MachineWindowLifecycle.track(store.getComponent(owner, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType()), owner, window);
    }

    private void drawSlots(UICommandBuilder commands, UIEventBuilder events, String selector, int section, ItemContainer container) {
        var stacks = new ItemStack[container == null ? 0 : container.getCapacity()];
        var actuals = new ItemStack[stacks.length];
        var tooltips = new String[stacks.length * 2];
        for (short slot = 0; slot < stacks.length; slot++) {
            var actual = container.getItemStack(slot);
            actuals[slot] = actual;
            stacks[slot] = presentation(actual);
            if (stacks[slot] != null) {
                // Explicit slot strings are part of ItemGridSlot's native schema. Never put
                // server BSON into the custom slot merely to retain a named note's tooltip.
                try {
                    tooltips[slot * 2] = actual.getDisplayName().getRawText();
                    tooltips[slot * 2 + 1] = GadgetEnergy.powered(actual)?GadgetEnergy.descriptionText(actual):actual.getDisplayDescription().getRawText();
                } catch (RuntimeException invalidDisplayMetadata) { /* The native item icon remains usable. */ }
            }
        }
        if (Arrays.equals(shownActual.get(selector), actuals) && Arrays.equals(shownTooltips.get(selector), tooltips)) return;
        var previous = shown.get(selector);
        var previousActual = shownActual.get(selector);
        var previousTips = shownTooltips.get(selector);
        for (int slot = 0; slot < stacks.length; slot++) {
            if (previous != null && slot < previous.length && Objects.equals(previousActual[slot], actuals[slot])
                    && previousTips != null && Objects.equals(previousTips[slot * 2], tooltips[slot * 2])
                    && Objects.equals(previousTips[slot * 2 + 1], tooltips[slot * 2 + 1])) continue;
            // Custom ItemGridStyle does not specify native durability-bar artwork. This
            // explicit energy meter also stays visible for a fully charged or empty gadget.
            boolean powered=GadgetEnergy.powered(actuals[slot]);
            commands.set(cellHost(selector,slot)+" #ChargeMeter.Visible",powered);
            if(powered){
                int charge=GadgetEnergy.charge(actuals[slot]),capacity=GadgetEnergy.capacity(actuals[slot]);
                var fill=new com.hypixel.hytale.server.core.ui.Anchor();
                fill.setLeft(Value.of(0));fill.setTop(Value.of(0));fill.setHeight(Value.of(3));
                fill.setWidth(Value.of((int)((34L*charge+capacity-1)/capacity)));
                commands.setObject(cellHost(selector,slot)+" #ChargeFill.Anchor",fill);
            }
            var entry = new ItemGridSlot(stacks[slot]);
            if (tooltips[slot * 2] != null) entry.setName(tooltips[slot * 2]);
            if (tooltips[slot * 2 + 1] != null) entry.setDescription(tooltips[slot * 2 + 1]);
            entry.setActivatable(true);
            commands.set(cell(selector, slot) + ".Slots", new ItemGridSlot[] {entry});
        // The client ItemGridSlot schema includes InventorySlotIndex, but the current Java
        // helper omits it. Each visible slot must retain its real inventory address, including
        // empty drop targets. Use the public command payload rather than reflective SDK edits.
        // https://hytalemodding.dev/docs/official-documentation/custom-ui/type-documentation/property-types/itemgridslot
        var frame = commands.getCommands();
        var slotCommand = frame[frame.length - 1];
        var payload = com.google.gson.JsonParser.parseString(slotCommand.data).getAsJsonObject();
        var indexed = payload.getAsJsonArray("0");
        indexed.get(0).getAsJsonObject().addProperty("InventorySlotIndex", slot);
        slotCommand.data = payload.toString();
        // The callback remains registered once. The client reads the revision from its
        // current cell when sending input, using the same dynamic Value path as text fields.
        if (gestures != null) commands.set(revisionCell(selector, slot) + ".Value", Long.toString(gestures.project(section, (short)slot)));
        }
        shown.put(selector, stacks);
        shownActual.put(selector, actuals);
        shownTooltips.put(selector, tooltips);
    }

    /** Custom ItemGrid JSON has a client metadata schema, unlike native inventory wire packets. */
    static ItemStack presentation(ItemStack stack) {
        if(GadgetEnergy.powered(stack))stack=GadgetEnergy.normalize(stack);
        return ItemStack.isEmpty(stack) || !stack.isValid() ? null : new ItemStack(stack.getItemId(), stack.getQuantity(),
                stack.getDurability(), stack.getMaxDurability(), null);
    }

    private DelegateItemContainer<ItemContainer> guard(ItemContainer actual, boolean takeOnly) {
        var view = new DelegateItemContainer<>(actual) {
            @Override protected MoveTransaction<ItemStackTransaction> internal_moveItemStackFromSlot(
                    short slot, int quantity, ItemContainer destination, boolean allOrNothing, boolean filter) {
                ItemContainer.validateSlotIndex(slot, getCapacity());
                if (filter && cantRemoveFromSlot(slot)) return refusedMove(slot, destination, filter);
                return super.internal_moveItemStackFromSlot(slot, quantity, destination, allOrNothing, filter);
            }
            @Override protected MoveTransaction<ItemStackTransaction> internal_moveItemStackFromSlot(
                    short slot, ItemContainer destination, boolean allOrNothing, boolean filter) {
                ItemContainer.validateSlotIndex(slot, getCapacity());
                if (filter && cantRemoveFromSlot(slot)) return refusedMove(slot, destination, filter);
                return super.internal_moveItemStackFromSlot(slot, destination, allOrNothing, filter);
            }
            private MoveTransaction<ItemStackTransaction> refusedMove(short slot, ItemContainer destination, boolean filter) {
                // Native whole-container moves return null for REMOVE denial and then dereference
                // that result. A normal failed transaction leaves both inventories untouched.
                var item = getItemStack(slot);
                return new MoveTransaction<>(false, new SlotTransaction(false, ActionType.REMOVE, slot,
                        item, item, null, false, false, filter), MoveType.MOVE_FROM_SELF, destination, ItemStackTransaction.FAILED_ADD);
            }
            @Override public EventRegistration<Void, ItemContainerChangeEvent> registerChangeEvent(
                    short priority, Consumer<ItemContainerChangeEvent> consumer) {
                // DelegateItemContainer normally watches only its delegate's internal events.
                // A second player's delegate emits an external event on the shared container.
                // Watching that native public stream keeps every viewer's window in sync.
                return actual.registerChangeEvent(priority, event -> consumer.accept(new ItemContainerChangeEvent(
                        this, event.transaction().toParent(this, (short) 0, actual))));
            }
        };
        // Native smart moves enumerate window containers without invoking ValidatedWindow.
        // Filters on this per-viewer delegate cover that path without changing the shared machine.
        for (short slot = 0; slot < actual.getCapacity(); slot++) {
            for (var action : new FilterActionType[] {FilterActionType.ADD, FilterActionType.REMOVE, FilterActionType.DROP})
                view.setSlotFilter(action, slot, (type, container, index, stack) ->
                        !(takeOnly && type == FilterActionType.ADD) && current());
        }
        return view;
    }

    private boolean available() {
        if (closed.get() || !world.isInThread() || !owner.isValid() || owner.getStore() != store) return false;
        return validInventory.getAsBoolean() && mayMutate.getAsBoolean();
    }

    private boolean current() {
        return belongsToCurrentPage() && mayMutate.getAsBoolean();
    }

    private boolean belongsToCurrentPage() {
        if (closed.get() || !world.isInThread() || !owner.isValid() || owner.getStore() != store || page == null) return false;
        var player = store.getComponent(owner, Player.getComponentType());
        return player != null && owner.equals(player.getReference()) && player.getPageManager().getCustomPage() == page
                && validInventory.getAsBoolean();
    }

    /** Dismissal invalidates access immediately; it never withdraws or returns a phantom cursor stack. */
    public void close(Ref<EntityStore> ref, Store<EntityStore> accessor) {
        if (!closed.compareAndSet(false, true)) return;
        if (gestures != null) gestures.close();
        Runnable cleanup = () -> {
            if (!owner.equals(ref) || accessor != store || !owner.isValid()) return;
            var player = store.getComponent(owner, Player.getComponentType());
            if (player == null) return;
            for (Window window : windows()) {
                if (window.getId() > 0 && player.getWindowManager().getWindow(window.getId()) == window)
                    player.getWindowManager().closeWindow(owner, window.getId(), store);
            }
        };
        if (world.isInThread()) cleanup.run();
        else try { world.execute(cleanup); } catch (RuntimeException stopped) { /* World teardown owns the windows. */ }
    }

    private final class GuardedWindow extends ContainerWindow implements ValidatedWindow {
        private GuardedWindow(ItemContainer container) { super(container); }
        @Override public boolean onOpen0(Ref<EntityStore> ref, Store<EntityStore> accessor) {
            return owner.equals(ref) && accessor == store && page != null && available();
        }
        @Override public boolean validate(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
            // Transfer checkpoints pause slot mutations through the delegate filters. They must
            // not close/reopen native windows or invalidate a client's current drag operation.
            return owner.equals(ref) && belongsToCurrentPage() && accessor.getExternalData() == store.getExternalData();
        }
    }
}
