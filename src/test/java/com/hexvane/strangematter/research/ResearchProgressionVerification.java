package com.hexvane.strangematter.research;

import com.hexvane.strangematter.StrangeMatterCommand;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import java.nio.file.Files;
import java.util.*;

public final class ResearchProgressionVerification {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("sm-progression-controls-"); UUID player = UUID.randomUUID(), untouched = UUID.randomUUID();
        try (var service = new ResearchService(directory)) {
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
            require(pages == 56, "All 56 original teaching pages are retained across 26 topics");
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
        }
        try (var restored = new ResearchService(directory)) { require(restored.hasUnlocked(player, "hoverboard"), "Administrative research survives save/reload"); }
        System.out.println("PASS: individual/all targeted research unlocks, prerequisite atomicity, persistence, original 56 teaching pages, native command help/autocomplete/permissions and balanced zero-percent completion display.");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
