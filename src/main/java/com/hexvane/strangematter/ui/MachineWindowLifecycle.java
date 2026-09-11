package com.hexvane.strangematter.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.window.CloseWindow;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.entity.entities.player.windows.WindowManager;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Idempotent client close requests for this mod's own recent windows only. */
final class MachineWindowLifecycle implements AutoCloseable {
    private static final Map<PlayerRef, Owned> OWNED = new WeakHashMap<>();
    private static final class Owned {
        final WeakReference<WindowManager> manager;
        final LinkedHashMap<Integer, WeakReference<Window>> windows = new LinkedHashMap<>();
        Owned(WindowManager manager) { this.manager = new WeakReference<>(manager); }
    }
    private final PacketFilter inbound = PacketAdapters.registerInbound((PlayerPacketFilter) MachineWindowLifecycle::receive);
    private boolean closed;

    static void track(PlayerRef player, Ref<EntityStore> ref, Window window) {
        if (player == null || window.getId() <= 0) return;
        var entity = ref.getStore().getComponent(ref, Player.getComponentType());
        if (entity == null) return;
        var manager = entity.getWindowManager();
        synchronized (OWNED) {
            var owned = OWNED.get(player);
            // Native world transfers retain this manager and its monotonic IDs, but replace
            // the entity Ref. Keep its closed IDs until the connection or manager changes.
            if (owned == null || owned.manager.get() != manager) { owned = new Owned(manager); OWNED.put(player, owned); }
            owned.windows.put(window.getId(), new WeakReference<>(window));
            while (owned.windows.size() > 256) owned.windows.remove(owned.windows.keySet().iterator().next());
        }
    }

    private static boolean receive(PlayerRef player, Packet packet) {
        if (MachineInventoryInput.receive(player, packet)) return true;
        if (!(packet instanceof CloseWindow close) || close.id <= 0) return false;
        final WindowManager manager;
        final WeakReference<Window> expected;
        synchronized (OWNED) {
            var owned = OWNED.get(player);
            if (owned == null || (manager = owned.manager.get()) == null) return false;
            expected = owned.windows.get(close.id);
            if (expected == null) return false;
        }
        final Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return true;
        var store = ref.getStore();
        var world = store.getExternalData().getWorld();
        Runnable apply = () -> {
            if (!ref.isValid() || ref != player.getReference()) return;
            var entity = store.getComponent(ref, Player.getComponentType());
            if (entity == null || entity.getWindowManager() != manager) return;
            var window = entity.getWindowManager().getWindow(close.id);
            if (window != null && window == expected.get()) entity.getWindowManager().closeWindow(ref, close.id, store);
        };
        if (world.isInThread()) apply.run();
        else try { world.execute(apply); } catch (RuntimeException stopped) { /* World teardown owns the windows. */ }
        return true;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        PacketAdapters.deregisterInbound(inbound);
    }
}
