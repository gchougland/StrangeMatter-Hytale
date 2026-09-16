package com.hexvane.strangematter.research;

import java.nio.file.*;
import java.util.*;

/** Executable headless regression checks; no live Hytale client required. */
public final class ResearchVerification {
    public static void main(String[] args) throws Exception {
        require(ResearchCatalog.nodes().size() == 33, "Original research nodes, automation and three gadget discoveries are present");
        GadgetEnergyResearchVerification.verifyLedger();
        ResearchBootstrapVerification.verify();
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
        require(!idle.panel(ResearchType.COGNITION).displaying && idle.panel(ResearchType.COGNITION).cueEnded, "Failure cannot freeze the last highlighted rune forever");
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
        verifyStartingStates(six);
        verifyExactShadow();
        verifyExactTime();
        ResearchPuzzleVerification.verify();
        ResearchStabilityVerification.verify();
        verifyCatalogExtensions();
        verifyStoppedWorldReleasesReservations();
        System.out.println("Research verification passed: " + solved + " solved seeded experiments; config, failure, input validation, costs, prerequisites, scan deduplication and persistence.");
    }
    private static void verifyStartingStates(ResearchNode six) {
        for (int seed = 0; seed < 2000; seed++) {
            var game = new ResearchSession(six, seed);
            var cognition = game.panel(ResearchType.COGNITION);
            require(!cognition.displaying && cognition.inputCount == 0, "Ready notes have no frozen first memory highlight");
            var energy = game.panel(ResearchType.ENERGY);
            require(Math.abs(energy.value-energy.target)>=.49 && Math.abs(energy.secondary-energy.targetSecondary)>=.124,
                    "Both energy controls start visibly out of alignment");
            var gravity = game.panel(ResearchType.GRAVITY);
            require(gravity.target!=0 && Math.abs(gravity.position-.5)>=.39,"Gravity begins off centre with uncancelled force");
            var shadow = game.panel(ResearchType.SHADOW);
            require(Math.abs(shadow.value+180-shadow.target)>=59.99 && Math.abs(ResearchSession.shadowLength(shadow)-shadow.targetSecondary)>=11.99,
                    "Shadow begins with both a visibly incorrect angle and length");
            require(Math.abs(game.panel(ResearchType.SPACE).value)>=2 && game.panel(ResearchType.SPACE).value*game.panel(ResearchType.SPACE).secondary<0,"Space starts with opposing unsolved bends");
            require(Math.abs(game.panel(ResearchType.TIME).value-game.panel(ResearchType.TIME).target)>=.59 && game.panel(ResearchType.TIME).angle!=game.panel(ResearchType.TIME).targetAngle,
                    "Time begins with visibly different speed and hand positions");
            game.begin();for(int i=0;i<20;i++)game.tick();
            require(game.activeTypes().stream().noneMatch(type->game.panel(type).stable),"No instrument auto solves without a control input: "+seed);
        }
    }
    private static void verifyExactShadow() {
        var node = new ResearchNode("shadow_test","general","Shadow","",Map.of(ResearchType.SHADOW,1),List.of());
        for(double step:List.of(15.0,7.0,17.5,90.0))for(int seed=0;seed<500;seed++){
            var settings=new ResearchSettings();settings.shadowRotationStep=step;
            var game=new ResearchSession(node,seed,settings);game.begin();var p=game.panel(ResearchType.SHADOW);
            for(int press=0;press<200 && Math.abs(p.value+180-p.target)>1e-8;press++){
                require(game.control(ResearchType.SHADOW,"angle",p.value+180<p.target?1:-1),"Valid discrete angle control");
                for(int i=0;i<5;i++)game.tick();
            }
            for(int press=0;press<20 && Math.abs(ResearchSession.shadowLength(p)-p.targetSecondary)>1e-8;press++){
                require(game.control(ResearchType.SHADOW,"distance",ResearchSession.shadowLength(p)>p.targetSecondary?1:-1),"Valid discrete distance control");
                for(int i=0;i<5;i++)game.tick();
            }
            require(Math.abs(p.value+180-p.target)<1e-8 && Math.abs(ResearchSession.shadowLength(p)-p.targetSecondary)<1e-8,
                    "Exact shadow overlay is reachable through real buttons: "+step+"/"+seed);
            // Real drift leaves fractional angles. A corrective button still reaches the same stop.
            if(p.value>-59.9 && p.value<59.9){
                p.value+=.37;p.cooldown=0;
                require(game.control(ResearchType.SHADOW,"angle",-1) && Math.abs(p.value+180-p.target)<1e-8,
                        "Fractional drift cannot permanently offset the control grid");
            }
        }
        System.out.println("PASS: 2,000 unsolved six-instrument starts and 2,000 exactly reachable shadow targets, including non-dividing dial steps and drift recovery.");
    }
    private static void verifyExactTime() {
        var node=new ResearchNode("clock_test","general","Time","",Map.of(ResearchType.TIME,1),List.of());
        var game=new ResearchSession(node,13);game.begin();var p=game.panel(ResearchType.TIME);
        // Enter the source tolerance at 0.9x, then let a real phase difference accumulate.
        p.target=1;p.value=.8;require(game.control(ResearchType.TIME,"speed",1),"Real speed control enters the tolerance band");
        for(int i=0;i<30;i++)game.tick();
        require(p.stable&&Math.abs(p.value-.9)<1e-8&&Math.abs(p.angle-p.targetAngle)>1,"Stable does not imply an exactly matched speed or phase");
        require(game.control(ResearchType.TIME,"speed",1)&&p.value==p.target&&p.angle==p.targetAngle,
                "Selecting exact clock speed also aligns the hands even when already stable");
        for(int i=0;i<20;i++){game.tick();require(p.angle==p.targetAngle,"Exact clock alignment persists on real simulation ticks");}
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
            require(service.nodes().size()==34,"Extension keeps defaults and adds a node");
            require(service.node("hoverboard").name().equals("Experimental Board"),"Existing node name override");
            require(service.node("hoverboard").costs().equals(Map.of(ResearchType.GRAVITY,7)),"Existing costs replaced and zero-cost disciplines omitted");
            require(service.node("hoverboard").prerequisites().equals(List.of("containment_basics")),"Omitted override fields preserved");
            require(service.availability(UUID.randomUUID(),service.node("custom_laboratory")).contains("Experimental Board"),"Custom prerequisite gates use configured catalog");
            require(ResearchCatalog.nodes().size()==33&&ResearchCatalog.get("hoverboard").costs().size()==2,"Default catalog and other service instances remain unchanged");
            for(String bad:List.of(
                    "{\"id\":\"custom\",\"name\":\"Bad\",\"costs\":{\"time\":-1}}",
                    "{\"id\":\"custom\",\"name\":\"Bad\",\"costs\":{\"time\":1.5}}",
                    "{\"id\":\"custom\",\"name\":\"Bad\",\"costs\":{\"time\":1},\"prerequisites\":[\"missing\"]}",
                    "{\"id\":\"resonite\",\"prerequisites\":[\"reality_forge\"]}",
                    "{\"id\":\"hoverboard\",\"costs\":{\"time\":0,\"TIME\":1}}")){
                Files.writeString(config,"{\"nodes\":["+bad+"]}");boolean rejected=false;
                try{ResearchCatalog.load(directory);}catch(java.io.IOException expected){rejected=true;}
                require(rejected,"Invalid customization rejected atomically: "+bad);
                require(service.nodes().size()==34&&service.node("hoverboard").name().equals("Experimental Board"),"Invalid config cannot alter live catalog");
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
                    case SPACE -> ResearchPuzzleVerification.controlSpace(game);
                    case TIME -> { if (Math.abs(p.value - p.target) > .04) game.control(type, "speed", p.value < p.target ? 1 : -1); }
                }
            }
            game.tick();
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
