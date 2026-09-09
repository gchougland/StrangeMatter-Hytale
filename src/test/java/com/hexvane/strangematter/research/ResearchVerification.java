package com.hexvane.strangematter.research;

import java.nio.file.*;
import java.util.*;

/** Executable headless regression checks; no live Hytale client required. */
public final class ResearchVerification {
    public static void main(String[] args) throws Exception {
        require(ResearchCatalog.nodes().size() == 26, "All original research nodes are present");
        require(ResearchCatalog.get("reality_forge").costs().equals(Map.of(ResearchType.ENERGY, 5, ResearchType.SPACE, 5, ResearchType.TIME, 5)), "Forge's original multi-discipline cost");
        require(ResearchCatalog.get("hoverboard").prerequisites().equals(List.of("containment_basics")), "Hoverboard prerequisite");
        for (ResearchNode node : ResearchCatalog.nodes()) for (String id : node.prerequisites()) require(ResearchCatalog.get(id) != null, "Resolvable prerequisite " + id);
        int solved = 0;
        for (ResearchNode node : ResearchCatalog.nodes()) if (!node.defaultUnlocked()) for (int seed = 0; seed < 50; seed++) {
            ResearchSession game = new ResearchSession(node, seed);
            require(game.state() == ResearchSession.State.READY, "Notes require explicit begin");
            game.tick(); require(game.ticks() == 0, "Ready session cannot advance");
            game.begin();
            solve(game, 4000);
            require(game.state() == ResearchSession.State.SUCCESS, "Solvable original research: " + node.id() + "/" + seed + "/" + game.instability());
            long ticks = game.ticks(); game.tick(); require(game.ticks() == ticks, "Completed simulation is immutable to ticking");
            solved++;
        }
        Map<ResearchType, Integer> all = new EnumMap<>(ResearchType.class);
        for (ResearchType type : ResearchType.values()) all.put(type, 1);
        ResearchNode six = new ResearchNode("verification", "general", "Six disciplines", "", all, List.of());
        for (int seed = 0; seed < 50; seed++) {
            ResearchSession game = new ResearchSession(six, seed); game.begin(); solve(game, 6000);
            require(game.state() == ResearchSession.State.SUCCESS, "All six simultaneous panels remain solvable " + seed);
            solved++;
        }
        ResearchSession idle = new ResearchSession(ResearchCatalog.get("cognitive_anomalies"), 90); idle.begin();
        for (int i = 0; i < 500; i++) idle.tick();
        require(idle.state() == ResearchSession.State.FAILURE, "Unattended experiments fail at original upper threshold");
        ResearchSession unsafe = new ResearchSession(six, 3); unsafe.begin();
        require(!unsafe.control(ResearchType.COGNITION, "symbol", 99), "Reject out-of-range rune events");
        require(!unsafe.control(ResearchType.GRAVITY, "force", Integer.MAX_VALUE), "Reject out-of-range force events");
        require(!unsafe.control(ResearchType.SPACE, "complete", 1), "Reject fabricated completion controls");
        require(Arrays.stream(unsafe.panel(ResearchType.COGNITION).pattern).distinct().count() == 3, "Cognition chooses unique symbols");
        ResearchSettings settings = new ResearchSettings(); settings.cognitionDifficulty = 8;
        ResearchSession hard = new ResearchSession(six, 42, settings); hard.begin(); solve(hard, 6000);
        require(hard.state() == ResearchSession.State.SUCCESS, "Maximum cognition difficulty is solvable");
        settings = new ResearchSettings(); settings.enableMinigames = false;
        ResearchSession disabled = new ResearchSession(six, 1, settings); disabled.begin();
        require(disabled.state() == ResearchSession.State.SUCCESS, "Optional minigame bypass is server configured");
        Path directory = Files.createTempDirectory("strangematter-research-test-");
        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        try (ResearchService service = new ResearchService(directory)) {
            int[] scanNotifications={0};service.setScanHook((id,type)->{require(service.hasScanned(id,"natural-anomaly-01"),"Hook runs after ledger commit");scanNotifications[0]++;});
            require(service.hasUnlocked(player, "field_scanner"), "Six starting researches survive first login");
            require(!service.hasUnlocked(player, "reality_forge"), "Advanced crafting starts locked");
            require(service.scan(player, "natural-anomaly-01", ResearchType.ENERGY, 5), "First scan awards points");
            require(!service.scan(player, "natural-anomaly-01", ResearchType.ENERGY, 5), "Repeated UUID scan cannot farm points");
            require(service.scan(other, "natural-anomaly-01", ResearchType.ENERGY, 5), "Each researcher can observe the anomaly");
            require(!service.scan(player, "invalid", ResearchType.ENERGY, -20), "Reject negative awards");
            require(scanNotifications[0]==2,"Only fresh scans trigger progression hooks");
        }
        try (ResearchService service = new ResearchService(directory)) {
            require(service.points(player, ResearchType.ENERGY) == 5, "Currency persists across service restart");
            require(service.profile(player).scannedCount() == 1, "Scan ledger persists across restart");
            require(!service.scan(player, "natural-anomaly-01", ResearchType.ENERGY, 5), "Deduplication survives restart");
        }
        verifyCatalogExtensions();
        verifyStoppedWorldReleasesReservations();
        System.out.println("Research verification passed: " + solved + " solved seeded experiments; config, failure, input validation, costs, prerequisites, scan deduplication and persistence.");
    }
    private static void verifyStoppedWorldReleasesReservations() throws Exception {
        try (var service = new ResearchService(Files.createTempDirectory("strangematter-page-stop-"))) {
            var player = new com.hypixel.hytale.server.core.universe.PlayerRef(null, UUID.randomUUID(), "UI Test", "en-US", null, null);
            var page = new ResearchMachinePage(player, service, new org.joml.Vector3i());
            require(page.reserve("stopped:0,0,0"), "Experiment acquires machine and researcher");
            require(!service.acquire("stopped:other", player.getUuid()), "Researcher cannot start two experiments");
            page.queuePulse(task -> { throw new java.util.concurrent.RejectedExecutionException("World stopped accepting tasks"); },
                    () -> { throw new AssertionError("Stopped world must not execute simulation"); });
            var reopened = new ResearchMachinePage(player, service, new org.joml.Vector3i());
            require(reopened.reserve("stopped:0,0,0"), "Rejected world dispatch releases both machine and researcher reservations");
            reopened.onDismiss(null, null);
            require(service.acquire("stopped:0,0,0", player.getUuid()), "Dismiss also releases reservations without an ECS access");
            service.release("stopped:0,0,0", player.getUuid());
            page.queuePulse(task -> { throw new AssertionError("Disposed page cannot queue another timer callback"); }, () -> {});
        }
        System.out.println("PASS: stopped-world timer rejection and dismissal release machine/researcher reservations.");
    }
    private static void verifyCatalogExtensions() throws Exception {
        Path directory=Files.createTempDirectory("strangematter-catalog-test-");Path config=directory.resolve("research-catalog.json");
        Files.writeString(config,"""
            {"nodes":[
              {"id":"hoverboard","name":"Experimental Board","costs":{"gravity":7,"energy":0}},
              {"id":"custom_laboratory","name":"Private Laboratory","description":"A pack research entry.","category":"general","costs":{"time":3},"prerequisites":["hoverboard"]}
            ]}
            """);
        try(var service=new ResearchService(directory)){
            require(service.nodes().size()==27,"Extension keeps defaults and adds a node");
            require(service.node("hoverboard").name().equals("Experimental Board"),"Existing node name override");
            require(service.node("hoverboard").costs().equals(Map.of(ResearchType.GRAVITY,7)),"Existing costs replaced and zero-cost disciplines omitted");
            require(service.node("hoverboard").prerequisites().equals(List.of("containment_basics")),"Omitted override fields preserved");
            require(service.availability(UUID.randomUUID(),service.node("custom_laboratory")).contains("Experimental Board"),"Custom prerequisite gates use configured catalog");
            require(ResearchCatalog.nodes().size()==26&&ResearchCatalog.get("hoverboard").costs().size()==2,"Default catalog and other service instances remain unchanged");
            for(String bad:List.of(
                    "{\"id\":\"custom\",\"name\":\"Bad\",\"costs\":{\"time\":-1}}",
                    "{\"id\":\"custom\",\"name\":\"Bad\",\"costs\":{\"time\":1.5}}",
                    "{\"id\":\"custom\",\"name\":\"Bad\",\"costs\":{\"time\":1},\"prerequisites\":[\"missing\"]}",
                    "{\"id\":\"resonite\",\"prerequisites\":[\"reality_forge\"]}",
                    "{\"id\":\"hoverboard\",\"costs\":{\"time\":0,\"TIME\":1}}")){
                Files.writeString(config,"{\"nodes\":["+bad+"]}");boolean rejected=false;
                try{ResearchCatalog.load(directory);}catch(java.io.IOException expected){rejected=true;}
                require(rejected,"Invalid customization rejected atomically: "+bad);
                require(service.nodes().size()==27&&service.node("hoverboard").name().equals("Experimental Board"),"Invalid config cannot alter live catalog");
            }
        }
    }
    private static void solve(ResearchSession game, int limit) {
        for (int i = 0; i < limit && game.state() == ResearchSession.State.RUNNING; i++) {
            for (ResearchType type : game.activeTypes()) {
                ResearchSession.Panel p = game.panel(type);
                if (p.cooldown > 0) continue;
                switch (type) {
                    case COGNITION -> { if (!p.stable) game.control(type, "symbol", p.pattern[p.inputCount]); }
                    case ENERGY -> {
                        if (Math.abs(p.value - p.target) >= .03) game.control(type, "amplitude", p.value < p.target ? 1 : -1);
                        else if (Math.abs(p.secondary - p.targetSecondary) >= .03) game.control(type, "period", p.secondary < p.targetSecondary ? 1 : -1);
                    }
                    case GRAVITY -> { if (p.value != -p.target) game.control(type, "force", (int) -p.target); }
                    case SHADOW -> {
                        if (Math.abs(p.value + 180 - p.target) > 7.5) game.control(type, "angle", p.value + 180 < p.target ? 1 : -1);
                        else if (Math.abs(ResearchSession.shadowLength(p) - p.targetSecondary) > 2) game.control(type, "distance", ResearchSession.shadowLength(p) > p.targetSecondary ? 1 : -1);
                    }
                    case SPACE -> { if (p.value >= .02) game.control(type, "warp", -1); }
                    case TIME -> { if (Math.abs(p.value - p.target) > .04) game.control(type, "speed", p.value < p.target ? 1 : -1); }
                }
            }
            game.tick();
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
