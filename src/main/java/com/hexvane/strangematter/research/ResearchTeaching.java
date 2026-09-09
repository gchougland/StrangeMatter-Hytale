package com.hexvane.strangematter.research;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Full source teaching text; recipes are rendered from the current Hytale ingredient catalog. */
public final class ResearchTeaching {
    public record Page(String title, String content, String recipe) {}
    public record Recipe(String output, String details) {}
    private static final Map<String,List<Page>> PAGES = new HashMap<>();
    private static final Map<String,Recipe> RECIPES = new HashMap<>();
    static {
        try {
            JsonObject teaching = read("/Server/StrangeMatter/Research/Teaching.json").getAsJsonObject();
            teaching.entrySet().forEach(entry -> {
                var pages = new ArrayList<Page>();
                for (var value : entry.getValue().getAsJsonArray()) {
                    var page = value.getAsJsonObject();
                    pages.add(new Page(page.get("title").getAsString(), reflow(page.get("content").getAsString()), page.has("recipe") ? page.get("recipe").getAsString() : null));
                }
                PAGES.put(entry.getKey(), List.copyOf(pages));
            });
            for (var value : read("/Server/StrangeMatter/recipes.json").getAsJsonArray()) {
                var recipe = value.getAsJsonObject(); var description = new StringBuilder("Output: ")
                        .append(recipe.get("quantity").getAsInt()).append(" x ").append(label(recipe.get("output").getAsString())).append("\n");
                recipe.getAsJsonObject("ingredients").entrySet().forEach(e -> description.append(e.getValue().getAsInt()).append(" x ").append(label(e.getKey())).append("   "));
                recipe.getAsJsonObject("shards").entrySet().forEach(e -> description.append(e.getValue().getAsInt()).append(" ").append(label(e.getKey())).append(" shards   "));
                description.append("\nStation: ").append(switch (recipe.get("station").getAsString()) { case "forge" -> "Reality Forge"; case "furnace" -> "Furnace"; default -> "Laboratory Workbench"; });
                RECIPES.put(recipe.get("id").getAsString(), new Recipe(recipe.get("output").getAsString(), description.toString()));
            }
        } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    private static JsonElement read(String path) throws IOException {
        try (var stream = ResearchTeaching.class.getResourceAsStream(path)) {
            if (stream == null) throw new FileNotFoundException(path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }
    public static List<Page> pages(ResearchNode node) { return PAGES.getOrDefault(node.id(), List.of(new Page(node.name(), node.description(), null))); }
    public static Recipe recipe(String id) { return id == null ? null : RECIPES.get(id); }
    private static String label(String id) { return id.replace("resource:", "").replace("SM_", "").replace('_', ' '); }
    static String reflow(String original) {
        var paragraphs = new ArrayList<String>();
        for (String paragraph : original.split("\\n\\s*\\n")) {
            var text = new StringBuilder();
            for (String line : paragraph.split("\\n")) {
                line = line.strip(); if (line.isEmpty()) continue;
                if (!text.isEmpty()) text.append(line.startsWith("•") || line.matches("\\d+\\..*") ? '\n' : ' ');
                text.append(line);
            }
            paragraphs.add(text.toString());
        }
        return String.join("\n\n", paragraphs);
    }
    public static String hytaleNotes(String node) {
        return switch (node) {
            case "research", "field_scanner" -> "Hytale controls: hold primary on a natural anomaly for two seconds to scan. Use the tablet to buy notes; insert a note in the Research Machine, then begin stabilization. Recipes become known automatically after successful research.";
            case "anomaly_resonator" -> "Hytale controls: primary reads your tuned frequency; secondary cycles the discipline. The instrument HUD shows direction, height and range.";
            case "containment_basics" -> "Hytale controls: hold the Echo Vacuum on an anomaly for two seconds while carrying an empty capsule. Throw a filled capsule to release its original field at the impact point.";
            case "warp_gun" -> "Hytale controls: primary and secondary project the two portal endpoints. The instrument HUD shows each channel's status.";
            case "chrono_blister" -> "Hytale controls: fire at a surface to place the temporary time-dilation volume. The instrument HUD displays charge and impact feedback.";
            case "echoform_imprinter" -> "Hytale controls: hold primary on a living target for one second to imprint its appearance. Secondary or Use restores your original form.";
            case "hoverboard" -> "Hytale controls: deploy the board to enter the native mount controls. Normal movement steers it; dismount folds it away.";
            case "graviton_hammer" -> "Hytale controls: normal swings damage a 3 x 3 face using native mining rules. Charge for greater depth; crouch for a precise single-block strike.";
            default -> "Hytale recipes below use the current laboratory ingredients. Machine controls are available through the block's Use interaction. Research unlocks persist per player.";
        };
    }
    private ResearchTeaching() {}
}
