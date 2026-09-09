package com.hexvane.strangematter.ui;

import com.google.gson.JsonParser;
import com.hexvane.strangematter.research.ResearchPageData;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.protocol.packets.player.JoinWorld;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Native PageManager drops Data while ANY CustomPage acknowledgement is outstanding.
 * Animated pages therefore route only their own nonce-bound input before that gate.
 * ACK and Dismiss packets always continue through the engine, unchanged. This class
 * never clears, reflects into, or otherwise changes the engine's acknowledgement counter.
 */
public final class LivePageTransport implements AutoCloseable {
    public static final String NONCE_KEY = "SMPageNonce";
    private static final Pattern NONCE_PROPERTY = Pattern.compile("\"(?:SMPageNonce|smPageNonce)\"\\s*:");
    private static final Pattern OWNED_ACTION_PROPERTY = Pattern.compile("\"(?:Action|action)\"\\s*:\\s*\"SM:");
    private final Map<PlayerRef, Connection> connections = Collections.synchronizedMap(new WeakHashMap<>());
    private final PacketFilter inbound, outbound;
    private boolean closed;

    public LivePageTransport() {
        inbound = PacketAdapters.registerInbound((PlayerPacketFilter) this::inbound);
        outbound = PacketAdapters.registerOutbound((PlayerPacketFilter) this::outbound);
    }

    public Lease attach(PlayerRef playerRef, CustomUIPage page, Ref<EntityStore> ref,
                        Store<EntityStore> store, Consumer<ResearchPageData> handler) {
        var world = store.getExternalData().getWorld();
        BooleanSupplier current = () -> {
            if (!ref.isValid() || !ref.equals(playerRef.getReference()) || ref.getStore() != store) return false;
            Player player = store.getComponent(ref, Player.getComponentType());
            return player != null && player.getPageManager().getCustomPage() == page;
        };
        Lease lease = register(playerRef, world, current, handler);
        lease.sender = (cmd, events) -> {
            // Already on the owning world thread: do not queue InteractiveCustomUIPage.sendUpdate,
            // which can otherwise send an old page's update after a page replacement.
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) player.getPageManager().updateCustomPage(new CustomPage(
                    page.getClass().getName(), false, false, page.getLifetime(),
                    cmd.getCommands(), events == null ? UIEventBuilder.EMPTY_EVENT_BINDING_ARRAY : events.getEvents()));
        };
        return lease;
    }
    Lease register(PlayerRef player, Executor world, BooleanSupplier current, Consumer<ResearchPageData> handler) {
        Connection connection;
        synchronized (connections) {
            if (closed) throw new IllegalStateException("Page transport is closed");
            connection = connections.computeIfAbsent(player, ignored -> new Connection());
        }
        Lease lease = new Lease(connection, world, current, handler, System::nanoTime);
        connection.activate(lease); return lease;
    }

    private boolean inbound(PlayerRef player, Packet packet) {
        if (!(packet instanceof CustomPageEvent event)) return false;
        Connection connection = connections.get(player);
        if (event.type == CustomPageEventType.Acknowledge) {
            if (connection != null) connection.acknowledged();
            return false;
        }
        if (event.type == CustomPageEventType.Dismiss) {
            if (connection != null) connection.dismissed();
            return false;
        }
        if (event.type != CustomPageEventType.Data) return false;
        return route(connection, event.data);
    }

    /** Also used by the native PageManager regression harness; no ECS access on the packet thread. */
    static boolean route(Connection connection, String raw) {
        if (raw == null) return false;
        boolean ownedHint = NONCE_PROPERTY.matcher(raw).find() || OWNED_ACTION_PROPERTY.matcher(raw).find();
        if (!ownedHint) return false;
        // Reserved events, including stale or malformed ones, must never fall through to a
        // different page's handler after replacement. Unrelated native UI events pass normally.
        if (raw.length() > 1024) return ownedHint;
        ResearchPageData data = new ResearchPageData();
        try {
            var json = JsonParser.parseString(raw).getAsJsonObject();
            var action = json.has("Action") ? json.get("Action") : json.get("action");
            var nonce = json.has(NONCE_KEY) ? json.get(NONCE_KEY) : json.get("smPageNonce");
            var value = json.has("Value") ? json.get("Value") : json.get("value");
            if (action != null && action.isJsonPrimitive() && action.getAsJsonPrimitive().isString()) data.action = action.getAsString();
            boolean encoded = data.action != null && data.action.startsWith("SM:");
            if (nonce == null && !encoded) return false;
            if (connection == null) return true;
            if (nonce != null) {
                if (!nonce.isJsonPrimitive() || !nonce.getAsJsonPrimitive().isString()) return true;
                data.pageNonce = nonce.getAsString();
            }
            if (value != null && !value.isJsonNull()) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return true;
                data.value = value.getAsString();
            } else data.value = "";
            if (encoded) {
                if (data.action.length() < 41 || data.action.charAt(39) != ':') return true;
                String embeddedNonce = data.action.substring(3, 39);
                if (data.pageNonce != null && !data.pageNonce.equals(embeddedNonce)) return true;
                data.pageNonce = embeddedNonce; data.action = data.action.substring(40);
            }
            // Native UI event envelopes can carry extra fields. Only these three owned
            // strings affect a transaction; unrelated envelope fields are deliberately ignored.
            if (data.pageNonce == null || data.pageNonce.length() != 36 || data.action == null || data.action.length() > 32
                    || (data.value != null && data.value.length() > 200)) return true;
        } catch (RuntimeException ignored) { return ownedHint; }
        Lease lease;
        synchronized (connection) { lease = connection.active; }
        if (lease != null) lease.enqueue(data);
        return true;
    }

    private boolean outbound(PlayerRef player, Packet packet) {
        if (packet instanceof JoinWorld) {
            // World.onSetupPlayerJoining has JUST reset PageManager's counter before
            // sending this packet. Forget only our observation of the old world epoch.
            Connection previous = connections.remove(player);
            if (previous != null) previous.worldChanged();
            return false;
        }
        if (!(packet instanceof CustomPage) && !(packet instanceof SetPage)) return false;
        Connection connection;
        synchronized (connections) {
            if (closed) return false;
            connection = connections.computeIfAbsent(player, ignored -> new Connection());
        }
        if (packet instanceof CustomPage page) connection.sentPage(page.isInitial);
        else connection.sentSetPage();
        return false;
    }

    /** Observational flow control only; native ACK ownership remains entirely with PageManager. */
    static final class Connection {
        private int outstanding;
        private boolean customOpen, reserved;
        private Lease active;
        synchronized void activate(Lease lease) {
            if (active != null) active.close();
            active = lease;
        }
        synchronized void sentPage(boolean initial) {
            outstanding++;
            if (initial) customOpen = true;
            reserved = false;
        }
        synchronized void sentSetPage() {
            if (customOpen) outstanding++;
            customOpen = false;
        }
        synchronized void acknowledged() { if (outstanding > 0) outstanding--; }
        synchronized void dismissed() {
            customOpen = false;
            if (active != null) active.close();
        }
        synchronized void worldChanged() {
            dismissed();
            outstanding = 0;
            reserved = false;
        }
        synchronized boolean ready() { return outstanding == 0 && !reserved; }
        synchronized boolean reserve() {
            if (!ready()) return false;
            reserved = true;
            return true;
        }
        synchronized void nativeGateOpen() { outstanding = 0; reserved = false; }
    }

    @FunctionalInterface private interface FrameSender { void send(UICommandBuilder cmd, UIEventBuilder events); }

    public static final class Lease implements AutoCloseable {
        private final String nonce = UUID.randomUUID().toString();
        private final Connection connection;
        private final Executor world;
        private final BooleanSupplier current;
        private final Consumer<ResearchPageData> handler;
        private final LongSupplier clock;
        private final ArrayDeque<ResearchPageData> queue = new ArrayDeque<>();
        private FrameSender sender;
        private boolean closed, scheduled, dispatching;
        private double credit = 80;
        private long replenished;
        Lease(Connection connection, Executor world, BooleanSupplier current, Consumer<ResearchPageData> handler, LongSupplier clock) {
            this.connection = connection; this.world = world; this.current = current; this.handler = handler; this.clock = clock;
            replenished = clock.getAsLong();
        }
        public void bind(UIEventBuilder events, String selector, String action, String value) {
            events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                    EventData.of("Action", "SM:" + nonce + ":" + action).append("Value", value).append(NONCE_KEY, nonce), false);
        }
        /** Native fallback remains validated and rate limited when another adapter forwards Data. */
        public void receiveFromNative(String raw) {
            if (isClosed() || !current.getAsBoolean()) return;
            // Reaching the page via PageManager proves its native acknowledgement gate is zero.
            // Synchronize only our observation; never clear the native counter.
            connection.nativeGateOpen(); route(connection, raw);
        }
        /** True only inside a validated, bounded bridge dispatch; native fallback cannot bypass it. */
        public boolean accepts(ResearchPageData data) { return dispatching && data != null && nonce.equals(data.pageNonce) && !isClosed(); }
        public boolean ready() { return !isClosed() && connection.ready(); }
        /** Called only on the world thread, after ready(). Structural changes travel with their bindings. */
        public boolean send(UICommandBuilder commands, UIEventBuilder events) {
            if (isClosed() || !current.getAsBoolean() || !connection.reserve()) return false;
            try { sender.send(commands, events); return true; }
            catch (RuntimeException ex) { synchronized (connection) { connection.reserved = false; } throw ex; }
        }
        private synchronized boolean isClosed() { return closed; }
        private void enqueue(ResearchPageData data) {
            synchronized (this) {
                if (closed || !nonce.equals(data.pageNonce)) return;
                long now = clock.getAsLong();
                credit = Math.min(80, credit + Math.max(0, now - replenished) / 1_000_000_000.0 * 40);
                replenished = now;
                if (credit < 1 || queue.size() >= 128) return;
                credit--; queue.addLast(data);
                if (scheduled) return;
                scheduled = true;
            }
            schedule();
        }
        private void schedule() {
            try { world.execute(this::drain); }
            catch (RuntimeException ignored) { close(); }
        }
        private void drain() {
            for (int i = 0; i < 32; i++) {
                ResearchPageData data;
                synchronized (this) {
                    if (closed) { queue.clear(); scheduled = false; return; }
                    data = queue.pollFirst();
                    if (data == null) { scheduled = false; return; }
                }
                if (!current.getAsBoolean()) { close(); return; }
                try { dispatching = true; handler.accept(data); }
                catch (RuntimeException ex) {
                    close();
                    System.getLogger(LivePageTransport.class.getName()).log(System.Logger.Level.ERROR, "Live page input failed", ex);
                    return;
                }
                finally { dispatching = false; }
            }
            schedule();
        }
        @Override public void close() {
            synchronized (this) { closed = true; queue.clear(); }
            synchronized (connection) { if (connection.active == this) connection.active = null; }
        }
    }

    @Override public void close() {
        synchronized (connections) {
            if (closed) return;
            closed = true;
            for (Connection connection : connections.values()) connection.dismissed();
            connections.clear();
        }
        PacketAdapters.deregisterInbound(inbound);
        PacketAdapters.deregisterOutbound(outbound);
    }
}
