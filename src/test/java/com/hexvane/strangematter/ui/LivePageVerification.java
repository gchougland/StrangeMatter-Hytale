package com.hexvane.strangematter.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.*;

/** Exercises the real native gate, not a replacement PageManager implementation. */
public final class LivePageVerification {
    private static final class WorldQueue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.addLast(command); }
        void drain() { for (int n = 0; !tasks.isEmpty(); n++) { require(n < 100, "Bounded world callback queue"); tasks.removeFirst().run(); } }
    }
    private static final class TestPage extends CustomUIPage {
        final List<String> inputs = new ArrayList<>();
        java.util.function.Consumer<String> fallback;
        TestPage() { super(null, CustomPageLifetime.CanDismissOrCloseThroughInteraction); }
        @Override public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {}
        @Override public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String raw) { if (fallback != null) fallback.accept(raw); else inputs.add(raw); }
    }
    public static void main(String[] args) throws Exception {
        actualNativeAdapterEvents();
        PageManager manager = new PageManager();
        TestPage page = new TestPage();
        // Reflection is confined to this test: a socket/player is unnecessary to reproduce
        // the native input gate, but its actual private outstanding counter must be nonzero.
        var pageField = PageManager.class.getDeclaredField("customPage"); pageField.setAccessible(true); pageField.set(manager, page);
        var counterField = PageManager.class.getDeclaredField("customPageRequiredAcknowledgments"); counterField.setAccessible(true);
        AtomicInteger nativeOutstanding = (AtomicInteger) counterField.get(manager);
        var connection = new LivePageTransport.Connection();
        var world = new WorldQueue();
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicLong clock = new AtomicLong();
        AtomicReference<LivePageTransport.Lease> dispatch = new AtomicReference<>();
        var lease = new LivePageTransport.Lease(connection, world, current::get,
                data -> { require(dispatch.get().accepts(data), "Page accepts only an active bridge dispatch"); page.handleDataEvent(null, null, data.value); }, clock::get);
        dispatch.set(lease);
        connection.activate(lease);
        UIEventBuilder bindings = new UIEventBuilder(); lease.bind(bindings, "#Rune0", "control", "COGNITION:symbol:0");
        var event = bindings.getEvents()[0];
        require(!event.locksInterface, "Native client control binding never locks the interface");
        String raw = event.data;
        var forged = new com.hexvane.strangematter.research.ResearchPageData();
        forged.pageNonce = com.google.gson.JsonParser.parseString(raw).getAsJsonObject().get(LivePageTransport.NONCE_KEY).getAsString();
        require(!lease.accepts(forged), "Even the correct nonce cannot bypass the bridge via native fallback");
        connection.sentPage(true); nativeOutstanding.incrementAndGet();
        manager.handleEvent(null, null, new CustomPageEvent(CustomPageEventType.Data, raw));
        require(page.inputs.isEmpty(), "Reproduces real PageManager silently dropping Data while ACK is pending");
        require(LivePageTransport.route(connection, raw), "Own nonce Data intercepted before the gate");
        require(page.inputs.isEmpty() && world.tasks.size() == 1, "Packet thread only queues work");
        world.drain();
        require(page.inputs.equals(List.of("COGNITION:symbol:0")), "Input applies on world thread despite unacknowledged frame");
        require(nativeOutstanding.get() == 1 && !lease.ready(), "Input neither clears nor acknowledges the native frame");
        ack(connection, manager);
        require(nativeOutstanding.get() == 0 && lease.ready(), "Original ACK drains normally without an unexpected acknowledgement");

        // At 200ms pulses and 1.2s RTT the unmodified native gate would remain closed.
        // Alternate steady and burst deliveries; two controls per pulse remain ordered.
        int expected = page.inputs.size(), frames = 0, deadline = -1;
        for (int pulse = 0; pulse < 600; pulse++) {
            clock.addAndGet(200_000_000L);
            if (pulse == deadline) ack(connection, manager);
            if (lease.ready()) {
                require(connection.reserve(), "Only one visual frame can be reserved");
                require(!connection.reserve(), "No second frame before native outbound observation");
                connection.sentPage(false); nativeOutstanding.incrementAndGet(); frames++;
                deadline = pulse + 6;
            }
            require(nativeOutstanding.get() == 1 && !lease.ready(), "Animation never creates an ACK backlog at high RTT");
            for (int input = 0; input < 2; input++) {
                String value = Integer.toString(expected++);
                String click = raw.replace("COGNITION:symbol:0", value);
                manager.handleEvent(null, null, new CustomPageEvent(CustomPageEventType.Data, click));
                require(LivePageTransport.route(connection, click), "Every control uses the bridge");
            }
            if (pulse % 3 == 0) world.drain();
        }
        world.drain(); ack(connection, manager);
        require(page.inputs.size() == expected && frames == 100 && nativeOutstanding.get() == 0,
                "1,200 controls survive 100 delayed frames and ACKs without drops or duplicate dispatch");
        for (int i = 1; i < page.inputs.size(); i++) require(page.inputs.get(i).equals(Integer.toString(i)), "Bursty input preserves order");

        // Outstanding traffic from the previous page and its close must drain before a new
        // page animates. None of these ACKs is consumed by the custom input bridge.
        connection.sentPage(false); nativeOutstanding.incrementAndGet();
        connection.sentSetPage(); nativeOutstanding.incrementAndGet();
        connection.sentPage(true); nativeOutstanding.incrementAndGet();
        require(!lease.ready(), "Page replacement waits for previous frame, close, and initial frame");
        for (int i = 0; i < 3; i++) ack(connection, manager);
        require(lease.ready() && nativeOutstanding.get() == 0, "Bursty native ACKs retain exact ownership");

        int before = page.inputs.size();
        require(!LivePageTransport.route(connection, "{\"Action\":\"native\"}"), "Unrelated page data follows native handling");
        require(!LivePageTransport.route(connection, "{\"Action\":\"native\",\"Value\":\"SMPageNonce\"}"), "Ordinary string mentioning nonce key passes native routing");
        require(!LivePageTransport.route(null, "{\"Action\":\"SMPageNonce\"}"), "Nonce mentioned as a value passes even without a tracked connection");
        require(!LivePageTransport.route(connection, "{\"Action\":\"native\",\"Value\":\"SMPageNonce\",\"Text\":\""+"x".repeat(2000)+"\"}"), "Oversized unrelated UI payload is not owned by this transport");
        require(!LivePageTransport.route(connection, "{\"Action\":\"native\",\"Value\":\"SM:"+"x".repeat(2000)+"\"}"), "Unrelated oversized text containing the action prefix remains on native routing");
        require(LivePageTransport.route(connection, raw.replace("SMPageNonce", "SMPageNonce\":[] , \"junk")), "Malformed owned event cancelled");
        require(LivePageTransport.route(connection, raw.replace("COGNITION:symbol:0", "x".repeat(2000))), "Oversized owned event cancelled");
        LivePageTransport.route(connection, raw); current.set(false); world.drain();
        require(page.inputs.size() == before, "Queued input cannot reach a page replaced before world execution");
        current.set(true);
        var replacement = new LivePageTransport.Lease(connection, world, current::get,
                data -> page.handleDataEvent(null, null, data.value), clock::get);
        connection.activate(replacement);
        LivePageTransport.route(connection, raw); world.drain();
        require(page.inputs.size() == before, "Previous page nonce rejected after reopening the same page class");
        UIEventBuilder nextBindings = new UIEventBuilder(); replacement.bind(nextBindings, "#Begin", "begin", "begin");
        String nextRaw = nextBindings.getEvents()[0].data;
        for (int i = 0; i < 1000; i++) LivePageTransport.route(connection, nextRaw);
        require(world.tasks.size() == 1, "Input spam cannot enqueue unbounded world callbacks");
        world.drain();
        require(page.inputs.size() == before + 80, "Token bucket bounds a malicious burst at 80 inputs");
        clock.addAndGet(1_000_000_000L); LivePageTransport.route(connection, nextRaw); connection.dismissed(); world.drain();
        require(page.inputs.size() == before + 80, "Dismiss invalidates even already queued input");
        connection.sentPage(true); nativeOutstanding.incrementAndGet();
        // The real World.onSetupPlayerJoining resets PageManager immediately before JoinWorld.
        // The transport observes that boundary; it never performs this engine reset itself.
        manager.clearCustomPageAcknowledgements(); connection.worldChanged();
        require(nativeOutstanding.get() == 0 && connection.ready(), "World transfer discards the obsolete observational ACK window");
        var transferred = new LivePageTransport.Lease(connection, world, current::get,
                data -> page.handleDataEvent(null, null, data.value), clock::get);
        connection.activate(transferred);
        connection.sentPage(true); nativeOutstanding.incrementAndGet(); ack(connection, manager);
        require(transferred.ready(), "A new world's initial ACK cannot remain blocked by an abandoned old-world frame");
        LivePageTransport.route(connection, nextRaw); world.drain();
        require(page.inputs.size() == before + 80, "Old-world nonce cannot control the new world's page");
        transferred.close();
        realCognitionUnderDelayedAck();
        System.out.println("PASS: native ACK gate, 1,200 ordered controls at 1.2s RTT, real cognition at 250ms debounce, world-transfer ACK reset, unrelated/stale input rejection and bounded queues.");
    }
    /** Constructor-free network handler fixture; no unsafe/reflection appears in production. */
    private static final class RecordingGameHandler extends com.hypixel.hytale.server.core.io.handlers.game.GamePacketHandler {
        java.util.List<com.hypixel.hytale.protocol.ToClientPacket> sent;
        private RecordingGameHandler() { super(null, null, null); }
        @Override public void write(com.hypixel.hytale.protocol.ToClientPacket packet) {
            if (!com.hypixel.hytale.server.core.io.adapter.PacketAdapters.__handleOutbound(this, packet)) sent.add(packet);
        }
        @Override public void writeNoCache(com.hypixel.hytale.protocol.ToClientPacket packet) { write(packet); }
    }
    private static void actualNativeAdapterEvents() throws Exception {
        var unsafeClass=Class.forName("sun.misc.Unsafe");var field=unsafeClass.getDeclaredField("theUnsafe");field.setAccessible(true);
        var socket=(RecordingGameHandler)unsafeClass.getMethod("allocateInstance",Class.class).invoke(field.get(null),RecordingGameHandler.class);
        socket.sent=new ArrayList<>();
        var player=new com.hypixel.hytale.server.core.universe.PlayerRef(null,UUID.randomUUID(),"Native UI Test","en-US",socket,null);
        socket.setPlayerRef(player);
        var manager=new PageManager();manager.init(player,null);
        var world=new WorldQueue();var received=new ArrayList<String>();
        try(var transport=new LivePageTransport()){
            TestPage page=new TestPage();var pageField=PageManager.class.getDeclaredField("customPage");pageField.setAccessible(true);pageField.set(manager,page);
            var lease=transport.register(player,world,()->manager.getCustomPage()==page,data->{
                received.add(data.action+":"+data.value);
                if(data.action.equals("close"))manager.setPage(null,null,Page.None);
            });
            UIEventBuilder events=new UIEventBuilder();lease.bind(events,"#InsertNote","insert","real-note-token");lease.bind(events,"#Close","close","");
            manager.updateCustomPage(new CustomPage(TestPage.class.getName(),true,true,CustomPageLifetime.CanDismissOrCloseThroughInteraction,new UICommandBuilder().getCommands(),events.getEvents()));
            for(var binding:events.getEvents()){
                var payload=com.google.gson.JsonParser.parseString(binding.data).getAsJsonObject();
                payload.addProperty("Type","Activating");payload.addProperty("Button",0);
                if(binding.selector.equals("#Close")){payload.remove(LivePageTransport.NONCE_KEY);payload.add("Value",com.google.gson.JsonNull.INSTANCE);}
                var event=new CustomPageEvent(CustomPageEventType.Data,payload.toString());
                var segment=java.lang.foreign.MemorySegment.ofArray(new byte[event.computeSize()]);event.serialize(segment,0);
                var decoded=CustomPageEvent.toObject(segment);
                require(com.hypixel.hytale.server.core.io.adapter.PacketAdapters.__handleInbound(socket,decoded),"Actual GamePacketHandler adapter intercepts a wire-decoded native control event");
                world.drain();
            }
            require(received.equals(List.of("insert:real-note-token","close:")),"Insert/Close survive native envelope fields and absent optional values");
            require(manager.getCustomPage()==null&&socket.sent.stream().anyMatch(p->p instanceof SetPage),"Close actually dismisses the native page even with an outstanding frame ACK");
            for(int i=0;i<2;i++){
                var ack=new CustomPageEvent(CustomPageEventType.Acknowledge,null);
                require(!com.hypixel.hytale.server.core.io.adapter.PacketAdapters.__handleInbound(socket,ack),"Native acknowledgements are forwarded unchanged");manager.handleEvent(null,null,ack);
            }
            lease.close();
            TestPage nativePage = new TestPage(); pageField.set(manager, nativePage);
            var nativeLease = transport.register(player, world, () -> manager.getCustomPage() == nativePage,
                    data -> received.add("fallback:" + data.action));
            nativePage.fallback = nativeLease::receiveFromNative;
            UIEventBuilder fallbackEvents = new UIEventBuilder(); nativeLease.bind(fallbackEvents, "#Begin", "begin", "");
            manager.handleEvent(null, null, new CustomPageEvent(CustomPageEventType.Data, fallbackEvents.getEvents()[0].data));
            world.drain();
            require(received.getLast().equals("fallback:begin"), "Actual native PageManager raw callback still routes a validated control when an adapter forwards it");
            nativeLease.close(); pageField.set(manager, null);
        }
        com.hexvane.strangematter.ui.gadget.GadgetHudVerification.verify(player, socket.sent);
        System.out.println("PASS: actual native adapter + wire-decoded Insert/Close events, extra client fields, encoded page identity, and native Close under pending ACK.");
    }
    private static void realCognitionUnderDelayedAck() throws Exception {
        var game = new com.hexvane.strangematter.research.ResearchSession(
                com.hexvane.strangematter.research.ResearchCatalog.get("cognitive_anomalies"), 109);
        var cognition = com.hexvane.strangematter.research.ResearchType.COGNITION;
        game.begin();
        var connection = new LivePageTransport.Connection();
        var world = new WorldQueue();
        AtomicLong clock = new AtomicLong(); game.advanceInputClock(clock.get());
        AtomicInteger accepted = new AtomicInteger();
        var lease = new LivePageTransport.Lease(connection, world, () -> true, data -> {
            game.advanceInputClock(clock.get());
            if (game.control(cognition, "symbol", Integer.parseInt(data.value))) accepted.incrementAndGet();
        }, clock::get);
        connection.activate(lease); connection.sentPage(true);
        long ticks = game.ticks(); double instability = game.instability();
        int displayTicks = game.panel(cognition).displayTicks;
        for (int symbol : game.panel(cognition).pattern) {
            UIEventBuilder events = new UIEventBuilder(); lease.bind(events, "#Rune"+symbol, "control", Integer.toString(symbol));
            LivePageTransport.route(connection, events.getEvents()[0].data); world.drain();
            require(!lease.ready(), "Real cognition inputs do not require the delayed visual acknowledgement");
            require(game.panel(cognition).cooldown == 5, "Original five-tick debounce retained");
            require(!game.control(cognition, "symbol", symbol), "Immediate duplicate remains rejected by source debounce");
            clock.addAndGet(250_000_000L);
        }
        require(accepted.get() == 3 && game.panel(cognition).stable,
                "A real three-symbol cognition sequence at 250ms spacing succeeds during one outstanding 1.2s frame");
        require(game.ticks() == ticks && game.instability() == instability && game.panel(cognition).displayTicks == displayTicks,
                "Input debounce advances without advancing unseen memory cues, physics or instability");
        clock.set(1_200_000_000L); game.advanceInputClock(clock.get()); connection.acknowledged();
        require(lease.ready(), "Visual simulation can resume after the actual frame acknowledgement");
        for (int i = 0; i < 4; i++) game.tick();
        require(game.ticks() == ticks + 4, "Acknowledged frame resumes four original physics ticks");
        lease.close();
    }
    private static void ack(LivePageTransport.Connection connection, PageManager manager) {
        connection.acknowledged();
        manager.handleEvent(null, null, new CustomPageEvent(CustomPageEventType.Acknowledge, null));
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
