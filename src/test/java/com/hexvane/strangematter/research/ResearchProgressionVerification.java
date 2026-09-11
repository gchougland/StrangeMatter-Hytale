package com.hexvane.strangematter.research;

import com.hexvane.strangematter.StrangeMatterCommand;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import com.hypixel.hytale.protocol.packets.interface_.CustomPage;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.util.*;

public final class ResearchProgressionVerification {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("sm-progression-controls-"); UUID player = UUID.randomUUID(), untouched = UUID.randomUUID();
        try (var service = new ResearchService(directory)) {
            ResearchAutomationVerification.verify(service);
            try { service.unlock(player, "hoverboard", false); throw new AssertionError("Strict unlock must reject missing prerequisites"); }
            catch (IllegalArgumentException expected) { require(!service.hasUnlocked(player, "reality_forge"), "Rejected strict unlock changes no prerequisites"); }
            var awarded = new ArrayList<String>(); service.setCompletionHook((id, node) -> awarded.add(node.id()));
            var before = service.profile(player).points(); var changed = service.unlock(player, "hoverboard", true);
            for (var node : changed) for (String prerequisite : node.prerequisites()) require(service.hasUnlocked(player, prerequisite), "Administrative unlock preserves the prerequisite graph");
            require(service.hasUnlocked(player, "hoverboard") && service.hasUnlocked(player, "reality_forge"), "Individual unlock includes prerequisite technologies");
            require(service.profile(player).points().equals(before), "Administrative unlock neither consumes nor grants observations");
            require(service.unlock(player, "hoverboard", true).isEmpty() && awarded.size() == changed.size(), "Repeated unlock is idempotent and emits no duplicate completion hook");
            service.unlock(player, "all", true);
            require(service.nodes().stream().allMatch(n -> service.hasUnlocked(player, n.id())), "Unlock all covers the entire configured catalog");
            require(!service.hasUnlocked(untouched, "hoverboard"), "Targeted unlock does not alter another player");
            int pages = 0;
            for (var node : service.nodes()) {
                var teaching = ResearchTeaching.pages(node); pages += teaching.size();
                require(!teaching.isEmpty() && teaching.stream().allMatch(p -> !p.title().isBlank() && !p.content().isBlank()), "Every topic has substantive unlocked teaching pages");
                for (var page : teaching) if (page.recipe() != null) require(ResearchTeaching.recipe(page.recipe()) != null, "Teaching recipe uses the current Hytale ingredients: " + page.recipe());
            }
            require(pages >= 58, "The original guides and new furniture and building pages remain available");
            var buildingGuides = ResearchTeaching.pages(service.node("resonite"));
            require(buildingGuides.stream().anyMatch(p -> "resonite_chair".equals(p.recipe()))
                    && buildingGuides.stream().anyMatch(p -> "resonite_roof".equals(p.recipe())),
                    "Resonite research teaches the new furniture and roof recipes");
            var command = new StrangeMatterCommand(service, null, null, null, null);
            require(command.getSubCommands().keySet().containsAll(Set.of("help", "journal", "status", "points", "spawn", "research", "unlock")), "Native command tree exposes discoverable subcommands");
            var unlock = command.getSubCommands().get("research").getSubCommands().get("unlock");
            require("strangematter.admin".equals(unlock.getPermission()), "Administrative native command retains explicit permission");
            require(unlock.getRequiredArguments().size() == 1 && unlock.getOptionalArguments().keySet().containsAll(Set.of("player", "strict")), "Native help receives typed node, target and prerequisite arguments");
            var suggestions = new SuggestionResult(); command.researchArgument().suggest(null, "ho", 0, suggestions);
            require(suggestions.getSuggestions().equals(List.of("hoverboard")), "Native research autocomplete filters node IDs");
            ParseResult valid = new ParseResult(); require("all".equals(command.researchArgument().parse("ALL", valid)) && !valid.failed(), "Native argument parser accepts all case-insensitively");
            ParseResult invalid = new ParseResult(); require(command.researchArgument().parse("invented_node", invalid) == null && invalid.failed(), "Native argument parser rejects unknown research before execution");
            var game = new ResearchSession(service.node("cognitive_anomalies"), 113); game.begin();
            for (int i = 0; i < 1500 && game.state() == ResearchSession.State.RUNNING; i++) {
                var panel = game.panel(ResearchType.COGNITION);
                if (!panel.stable && panel.cooldown == 0) game.control(ResearchType.COGNITION, "symbol", panel.pattern[panel.inputCount]);
                game.tick();
            }
            require(game.state() == ResearchSession.State.SUCCESS && game.instability() > 0 && game.instability() < .05, "Original success threshold and balance remain unchanged");
            require(ResearchMachinePage.displayedInstability(game) == 0, "Successful experiment displays zero instead of the internal threshold");
            verifyGauge(game, 0, 1, false);
            verifyGauge(null, 50, 480, true);
            var unattended = new ResearchSession(service.node("cognitive_anomalies"), 113);
            verifyGauge(unattended, 50, 480, true);
            unattended.begin();
            for (int i = 0; i < 100; i++) unattended.tick();
            verifyGauge(unattended, 60, (int) (unattended.instability() * 960), true);
            for (int i = 0; i < 1500 && unattended.state() == ResearchSession.State.RUNNING; i++) unattended.tick();
            require(unattended.state() == ResearchSession.State.FAILURE && unattended.instability() >= .95 && unattended.instability() < 1,
                    "Unattended failure retains the existing simulation cutoff");
            verifyGauge(unattended, 100, 960, true);
        }
        try (var restored = new ResearchService(directory)) { require(restored.hasUnlocked(player, "hoverboard"), "Administrative research survives save/reload"); }
        System.out.println("PASS: targeted research unlocks, prerequisite atomicity, persistence, teaching pages, native command help and native gauge packets at zero percent success and full 100 percent failure.");
    }
    private static void verifyGauge(ResearchSession session, int percent, int width, boolean visible) {
        var commands = new UICommandBuilder();
        ResearchMachinePage.renderInstability(commands, session);
        var packet = new CustomPage(ResearchMachinePage.class.getName(), false, false,
                CustomPageLifetime.CanDismissOrCloseThroughInteraction, commands.getCommands(), new UIEventBuilder().getEvents());
        var bytes = MemorySegment.ofArray(new byte[packet.computeSize()]); packet.serialize(bytes, 0);
        var decoded = CustomPage.toObject(bytes);
        require(property(decoded, "#Instability.Text").getAsString().equals("INSTABILITY  " + percent + "%"), "Native gauge label reaches its displayed endpoint");
        require(property(decoded, "#InstabilityFill.Anchor").getAsJsonObject().get("Width").getAsInt() == width, "Native gauge fill agrees with the label");
        require(property(decoded, "#InstabilityFill.Visible").getAsBoolean() == visible, "Completed success hides the empty fill while failure keeps the full fill visible");
    }
    private static JsonElement property(CustomPage packet, String selector) {
        var command = Arrays.stream(packet.commands).filter(c -> selector.equals(c.selector)).findFirst().orElseThrow();
        return JsonParser.parseString(command.data).getAsJsonObject().get("0");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
