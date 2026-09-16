package com.hexvane.strangematter.research;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Proves the starter crafting graph closes before completing any research experiment. */
public final class ResearchBootstrapVerification {
    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final List<String> STARTERS = List.of("SM_Laboratory_Bench", "SM_Research_Tablet", "SM_Field_Scanner",
            "SM_Research_Machine", "SM_Resonant_Coil", "SM_Resonant_Circuit", "SM_Stabilized_Core");
    private static final List<String> POWER = List.of("SM_Resonant_Burner", "SM_Resonant_Charging_Station",
            "SM_Resonant_Conduit", "SM_Resonant_Energy_Storage");
    private record Recipe(String output, JsonObject data) {}

    public static void main(String[] args) throws Exception { verify(); }

    public static void verify() throws Exception {
        Map<String,JsonObject> items = new LinkedHashMap<>();
        var recipes = new ArrayList<Recipe>();
        try (var paths = Files.walk(RESOURCES.resolve("Server/Item/Items/StrangeMatter"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String id = path.getFileName().toString().replace(".json", "");
                var item = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                items.put(id, item);
                if (item.has("Recipe")) recipes.add(new Recipe(id, item.getAsJsonObject("Recipe")));
            }
        }
        try (var paths = Files.walk(RESOURCES.resolve("Server/Item/Recipes/StrangeMatter"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                var recipe = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                recipes.add(new Recipe(recipe.getAsJsonObject("PrimaryOutput").get("ItemId").getAsString(), recipe));
            }
        }
        var tablet = items.get("SM_Research_Tablet").getAsJsonObject("Recipe").getAsJsonArray("Input");
        require(tablet.size() == 1 && tablet.get(0).getAsJsonObject().get("ItemId").getAsString().equals("SM_Resonite_Ingot")
                && tablet.get(0).getAsJsonObject().get("Quantity").getAsInt() == 4, "Tablet only requires four resonite ingots");
        var catalog = JsonParser.parseString(Files.readString(RESOURCES.resolve("Server/StrangeMatter/recipes.json"))).getAsJsonArray();
        for (var row : catalog) if (row.getAsJsonObject().get("id").getAsString().equals("research_tablet")) {
            var ingredients = row.getAsJsonObject().getAsJsonObject("ingredients");
            require(ingredients.size() == 1 && ingredients.get("SM_Resonite_Ingot").getAsInt() == 4,
                    "Tablet teaching/catalog recipe agrees with the actual native recipe");
        }
        var directory = Files.createTempDirectory("sm-bootstrap-graph-");
        var player = UUID.randomUUID();
        try (var research = new ResearchService(directory)) {
            assertStarters(reachable(research, player, items, recipes, ResearchRecipeBridge::requirement), "fresh player");
            // Regression control: any one of the original component gates breaks the desk.
            for (String component : List.of("SM_Resonant_Coil", "SM_Resonant_Circuit", "SM_Stabilized_Core")) {
                var blocked = reachable(research, player, items, recipes,
                        id -> id.equals(component) ? "resonant_energy" : ResearchRecipeBridge.requirement(id));
                require(!blocked.contains("SM_Research_Machine"), "Detect paid-research cycle through " + component);
            }
            // A misplaced laboratory recipe must also be caught: components alone are insufficient.
            var circularBench = new ArrayList<>(recipes);
            circularBench.replaceAll(recipe -> {
                if (!recipe.output.equals("SM_Laboratory_Bench")) return recipe;
                var data = recipe.data.deepCopy();
                data.getAsJsonArray("BenchRequirement").get(0).getAsJsonObject().addProperty("Id", "SM_Laboratory");
                return new Recipe(recipe.output, data);
            });
            require(!reachable(research, player, items, circularBench, ResearchRecipeBridge::requirement).contains("SM_Research_Machine"),
                    "Detect laboratory bench requiring itself");
            research.unlock(player, "resonant_energy", true);
            var powered = reachable(research, player, items, recipes, ResearchRecipeBridge::requirement);
            for (String id : POWER) require(powered.contains(id), "First discovery makes complete charging chain attainable: " + id);
            research.reset(player);
            assertStarters(reachable(research, player, items, recipes, ResearchRecipeBridge::requirement), "research reset");
        }
        try (var research = new ResearchService(directory)) {
            assertStarters(reachable(research, player, items, recipes, ResearchRecipeBridge::requirement), "save/reload after reset");
        }
        System.out.println("PASS: starter dependency graph, ingredient/resource alternatives, obtainable benches, locked power chain, component/bench cycle controls, reset and reload.");
    }

    private static void assertStarters(Set<String> reachable, String context) {
        for (String id : STARTERS) require(reachable.contains(id), context + " cannot bootstrap " + id);
        for (String id : POWER) require(!reachable.contains(id), context + " must still research power infrastructure: " + id);
        require(!reachable.contains("SM_Reality_Forge"), context + " does not bypass advanced research");
    }

    private static Set<String> reachable(ResearchService research, UUID player, Map<String,JsonObject> items,
                                         List<Recipe> recipes, Function<String,String> requirements) {
        // Ordinary vanilla mining/crafting is assumed; no mod machines or component items are seeded.
        // Shards and raw resonite are mined directly beneath anomalies without using a gadget.
        Set<String> available = new HashSet<>(Set.of("Bench_WorkBench", "Bench_Furnace", "Ingredient_Bar_Iron",
                "Ingredient_Bar_Copper", "Ingredient_Stick", "Rock_Crystal_Red_Block", "SM_Raw_Resonite"));
        for (String family : List.of("Gravitic", "Chrono", "Spatial", "Shade", "Insight", "Energetic"))
            available.add("SM_" + family + "_Shard");
        Set<String> nativeResources = Set.of("Wood_Planks");
        Map<String,JsonObject> benches = new HashMap<>();
        benches.put("Workbench", JsonParser.parseString("{\"Id\":\"Workbench\",\"Type\":\"Crafting\",\"Categories\":[{\"Id\":\"Workbench_Crafting\"}]}").getAsJsonObject());
        benches.put("Furnace", JsonParser.parseString("{\"Id\":\"Furnace\",\"Type\":\"Processing\"}").getAsJsonObject());
        boolean changed;
        do {
            changed = false;
            for (var recipe : recipes) {
                String node = requirements.apply(recipe.output);
                if (available.contains(recipe.output) || node != null && !research.hasUnlocked(player, node)) continue;
                if (recipe.data.has("KnowledgeRequired") && recipe.data.get("KnowledgeRequired").getAsBoolean() && node == null) continue;
                var requiredBenches = recipe.data.getAsJsonArray("BenchRequirement");
                if (requiredBenches != null && !requiredBenches.isEmpty()
                        && !asObjects(requiredBenches).stream().anyMatch(b -> benchMatches(b, benches.get(b.get("Id").getAsString())))) continue;
                if (!asObjects(recipe.data.getAsJsonArray("Input")).stream().allMatch(input -> {
                    if (input.has("ItemId")) return available.contains(input.get("ItemId").getAsString());
                    if (!input.has("ResourceTypeId")) return false;
                    String resource = input.get("ResourceTypeId").getAsString();
                    return nativeResources.contains(resource) || available.stream().map(items::get).filter(Objects::nonNull)
                            .anyMatch(item -> asObjects(item.getAsJsonArray("ResourceTypes")).stream()
                                    .anyMatch(member -> resource.equals(member.get("Id").getAsString())));
                })) continue;
                available.add(recipe.output);
                var item = items.get(recipe.output);
                if (item != null && item.has("BlockType") && item.getAsJsonObject("BlockType").has("Bench")) {
                    var bench = item.getAsJsonObject("BlockType").getAsJsonObject("Bench");
                    benches.put(bench.get("Id").getAsString(), bench);
                }
                changed = true;
            }
        } while (changed);
        return available;
    }

    private static boolean benchMatches(JsonObject required, JsonObject bench) {
        if (bench == null || !bench.get("Type").equals(required.get("Type"))) return false;
        if (required.has("RequiredTierLevel") && required.get("RequiredTierLevel").getAsInt() > 1) return false;
        Set<String> categories = new HashSet<>();
        for (var category : asObjects(bench.getAsJsonArray("Categories"))) categories.add(category.get("Id").getAsString());
        if (required.has("Categories")) for (var category : required.getAsJsonArray("Categories"))
            if (!categories.contains(category.getAsString())) return false;
        return true;
    }
    private static List<JsonObject> asObjects(JsonArray array) {
        if (array == null) return List.of();
        var objects = new ArrayList<JsonObject>();
        for (var entry : array) objects.add(entry.getAsJsonObject());
        return objects;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private ResearchBootstrapVerification() {}
}
