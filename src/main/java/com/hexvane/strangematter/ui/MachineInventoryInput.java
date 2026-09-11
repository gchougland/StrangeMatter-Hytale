package com.hexvane.strangematter.ui;

import com.google.gson.JsonParser;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.protocol.packets.inventory.MoveItemStack;
import com.hypixel.hytale.server.core.inventory.InventoryUtils;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.PreventInventoryAccess;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Explicit Custom ItemGrid gestures over real containers, with one owner for native and UI moves. */
final class MachineInventoryInput implements AutoCloseable {
    private static final String NONCE = "SMInventoryNonce", ACTION = "SMInventoryAction", ADDRESS = "SMInventoryAddress", VERSION = "SMInventoryVersion";
    private static final Map<PlayerRef, WeakReference<MachineInventoryInput>> ACTIVE = new WeakHashMap<>();
    private final String nonce = UUID.randomUUID().toString();
    private final Ref<EntityStore> ref;
    private final Store<EntityStore> store;
    private final World world;
    private final PlayerRef player;
    private final BooleanSupplier current;
    private final Map<Integer, ItemContainer> sections;
    private volatile boolean closed;
    private int queued;
    private double credit = 120;
    private long replenished = System.nanoTime(), generation;
    private Selection selection;
    private Selection cancelling;
    private Completion completion;
    private Pending pending;
    private final Map<Address, Projection> projections = new HashMap<>();
    private long revision;

    record Address(int section, short slot) {}
    private record Selection(Address from, ItemStack stack, long generation, long version) {}
    private record Pending(Selection selection, Address to) {}
    private record Completion(Address from, Address to) {}
    private record Projection(long version, ItemStack stack) {}

    MachineInventoryInput(Ref<EntityStore> ref, Store<EntityStore> store,
                          Map<Integer, ItemContainer> sections, BooleanSupplier current) {
        this.ref = ref; this.store = store; this.world = store.getExternalData().getWorld();
        this.player = store.getComponent(ref, PlayerRef.getComponentType());
        this.current = current; this.sections = Map.copyOf(sections);
        synchronized (ACTIVE) {
            var previous = ACTIVE.put(player, new WeakReference<>(this));
            var old = previous == null ? null : previous.get();
            if (old != null) old.close();
        }
    }

    long project(int section, short slot) {
        var at = new Address(section, slot);
        long version = ++revision;
        projections.put(at, new Projection(version, stack(at)));
        return version;
    }
    void bind(UIEventBuilder events, String selector, String versionSelector, int section, short slot) {
        bind(events, selector, CustomUIEventBindingType.SlotClicking, "press", section, slot, versionSelector);
        bind(events, selector, CustomUIEventBindingType.SlotMouseDragExited, "drag", section, slot, versionSelector);
        for (var type : new CustomUIEventBindingType[] {CustomUIEventBindingType.Dropped,
                CustomUIEventBindingType.SlotMouseDragCompleted, CustomUIEventBindingType.SlotClickReleaseWhileDragging,
                CustomUIEventBindingType.SlotClickPressWhileDragging}) bind(events, selector, type, "drop", section, slot, versionSelector);
        bind(events, selector, CustomUIEventBindingType.DragCancelled, "cancel", section, slot, versionSelector);
    }
    private void bind(UIEventBuilder events, String selector, CustomUIEventBindingType type, String action, int section, short slot, String versionSelector) {
        // One native slot per cell makes both addresses explicit. Do not guess undocumented
        // event payload names such as MouseButton, DraggedSlot or Quantity.
        events.addEventBinding(type, selector, EventData.of(NONCE, nonce).append(ACTION, action)
                .append(ADDRESS, section + ":" + slot).append("@" + VERSION, versionSelector), false);
    }

    static boolean receive(PlayerRef player, Packet packet) {
        MachineInventoryInput input;
        synchronized (ACTIVE) { var entry = ACTIVE.get(player); input = entry == null ? null : entry.get(); }
        if (packet instanceof CustomPageEvent event && event.type == CustomPageEventType.Data
                && event.data != null && event.data.contains("\"" + NONCE + "\"")) {
            // Our reserved events never fall into a newly opened page's handler or its ACK gate.
            if (input == null || event.data.length() > 512) return true;
            try {
                var data = JsonParser.parseString(event.data).getAsJsonObject();
                if (!input.nonce.equals(data.get(NONCE).getAsString())) return true;
                String action = data.get(ACTION).getAsString();
                var address = data.get(ADDRESS).getAsString().split(":", -1);
                if (address.length != 2 || action.length() > 8) return true;
                var at = new Address(Integer.parseInt(address[0]), Short.parseShort(address[1]));
                var suppliedVersion = data.has(VERSION) ? data.get(VERSION) : data.get("@" + VERSION);
                long version = Long.parseLong(suppliedVersion.getAsString());
                input.enqueue(() -> input.event(action, at, version));
            } catch (RuntimeException malformed) { /* Invalid scoped input is consumed without a mutation. */ }
            return true;
        }
        if (input != null && !input.closed && packet instanceof MoveItemStack move
                && input.sections.containsKey(move.fromSectionId) && input.sections.containsKey(move.toSectionId)) {
            int fromSlot = move.fromSlotId, toSlot = move.toSlotId, quantity = move.quantity;
            int fromSection = move.fromSectionId, toSection = move.toSectionId;
            if (fromSlot >= 0 && fromSlot <= Short.MAX_VALUE && toSlot >= 0 && toSlot <= Short.MAX_VALUE)
                input.enqueue(() -> input.nativeMove(new Address(fromSection, (short) fromSlot), quantity,
                        new Address(toSection, (short) toSlot)));
            return true;
        }
        return false;
    }

    private synchronized void enqueue(Runnable work) {
        if (closed) return;
        long now = System.nanoTime();
        credit = Math.min(120, credit + Math.max(0, now - replenished) / 1_000_000_000.0 * 60);
        replenished = now;
        if (credit < 1 || queued >= 128) return;
        credit--; queued++;
        try { world.execute(() -> {
            synchronized (this) { queued--; }
            if (usable()) work.run();
            else { selection = null; cancelling = null; pending = null; }
        }); } catch (RuntimeException stopped) { queued--; }
    }
    private boolean usable() {
        return !closed && world.isInThread() && ref.isValid() && ref.equals(player.getReference())
                && current.getAsBoolean() && !store.getArchetype(ref).contains(PreventInventoryAccess.getComponentType());
    }
    private boolean valid(Address at) {
        var inventory = sections.get(at.section());
        return inventory != null && at.slot() >= 0 && at.slot() < inventory.getCapacity();
    }
    private ItemStack stack(Address at) { return sections.get(at.section()).getItemStack(at.slot()); }

    private void event(String action, Address at, long version) {
        if (!valid(at)) return;
        if (action.equals("cancel")) {
            if (selection != null && selection.version() == version) cancel(at);
            return;
        }
        var projection = projections.get(at);
        if (projection == null || projection.version() != version) return;
        if ((action.equals("press") || action.equals("drag")) && selection != null
                && (selection == cancelling || !Objects.equals(stack(selection.from()), selection.stack()))) {
            selection = null; cancelling = null; pending = null;
        }
        switch (action) {
            case "press" -> {
                if (selection != null && !selection.from().equals(at)) { drop(at); return; }
                // Native moves can arrive before the custom press envelope. An old displayed
                // stack may never select the remainder of an already completed native move.
                if (!Objects.equals(stack(at), projection.stack())) return;
                pending = null; completion = null;
                var item = stack(at);
                selection = ItemStack.isEmpty(item) ? null : new Selection(at, item, ++generation, version);
            }
            case "drag" -> {
                // Crossing other cells cannot change the source of an ongoing drag.
                if (selection == null && Objects.equals(stack(at), projection.stack()) && !ItemStack.isEmpty(stack(at))) {
                    // Dragging does not have to emit SlotClicking. A fresh valid source begins
                    // its own gesture even when the previous transfer has a duplicate guard.
                    completion = null;
                    selection = new Selection(at, stack(at), ++generation, version);
                }
            }
            case "drop" -> drop(at);
            default -> { }
        }
    }
    private void cancel(Address at) {
        if (selection == null || !selection.from().equals(at) || pending != null) return;
        var cancelled = selection;
        cancelling = cancelled;
        // Native drag cleanup can precede the target callback. Retain the source briefly
        // for that already released drag, and never erase a drop accepted on a real slot.
        CompletableFuture.delayedExecutor(150, TimeUnit.MILLISECONDS).execute(() -> {
            try { world.execute(() -> {
                if (selection == cancelled && pending == null) selection = null;
                if (cancelling == cancelled) cancelling = null;
            }); } catch (RuntimeException stopped) { }
        });
    }
    private void drop(Address to) {
        if (selection == null || pending != null) return;
        if (selection.from().equals(to)) { cancel(to); return; }
        var candidate = new Pending(selection, to);
        cancelling = null;
        pending = candidate;
        // If the client supplies its native quantity/split packet, it owns this same gesture.
        // The fallback is only for a Custom ItemGrid release without a native move packet.
        CompletableFuture.delayedExecutor(150, TimeUnit.MILLISECONDS).execute(() -> {
            try { world.execute(() -> complete(candidate)); } catch (RuntimeException stopped) { }
        });
    }
    private void complete(Pending candidate) {
        if (pending != candidate || selection != candidate.selection()) return;
        var selected = selection;
        pending = null; selection = null;
        if (!usable()) return;
        if (!Objects.equals(stack(selected.from()), selected.stack())) return;
        completion = new Completion(selected.from(), candidate.to());
        InventoryUtils.moveItem(ref, selected.from().section(), selected.from().slot(), selected.stack().getQuantity(),
                candidate.to().section(), candidate.to().slot(), store);
    }
    private void nativeMove(Address from, int quantity, Address to) {
        if (!valid(from) || !valid(to) || from.equals(to) || quantity <= 0) return;
        // Prevent a second release callback/native move from moving a remainder or undoing a swap.
        if (completion != null && completion.from().equals(from) && completion.to().equals(to)) return;
        if (selection != null && selection.from().equals(from)) {
            if (!Objects.equals(stack(from), selection.stack())) { selection = null; pending = null; return; }
            selection = null; pending = null;
        }
        var item = stack(from);
        if (ItemStack.isEmpty(item) || quantity > item.getQuantity()) return;
        completion = new Completion(from, to);
        InventoryUtils.moveItem(ref, from.section(), from.slot(), quantity, to.section(), to.slot(), store);
    }
    @Override public void close() {
        closed = true;
        synchronized (ACTIVE) {
            var entry = ACTIVE.get(player);
            if (entry != null && entry.get() == this) ACTIVE.remove(player);
        }
    }
}
