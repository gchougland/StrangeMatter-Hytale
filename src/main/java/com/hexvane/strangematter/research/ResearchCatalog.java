package com.hexvane.strangematter.research;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.*;
import java.util.*;

/** Costs and graph transcribed from the Minecraft ResearchNodeRegistry. */
public final class ResearchCatalog {
    private static final LinkedHashMap<String, ResearchNode> NODES = new LinkedHashMap<>();
    private static final Properties TEXT = new Properties();
    static {
        try (var stream = ResearchCatalog.class.getResourceAsStream("/Server/StrangeMatter/Research/Tablet.properties")) {
            if (stream != null) TEXT.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
        add("research", "general", "", "");
        add("field_scanner", "general", "", "research");
        add("anomaly_shards", "general", "", "research");
        add("anomaly_types", "general", "", "research");
        add("resonite", "general", "", "research");
        add("resonant_energy", "general", "", "research");
        add("tinfoil_hat", "general", "ENERGY:10,COGNITION:10", "resonite");
        add("anomaly_resonator", "general", "ENERGY:10,SPACE:10", "field_scanner");
        add("reality_forge", "general", "ENERGY:5,SPACE:5,TIME:5", "resonite");
        add("gravity_anomalies", "general", "GRAVITY:5", "anomaly_types");
        add("temporal_anomalies", "general", "TIME:5", "anomaly_types");
        add("spatial_anomalies", "general", "SPACE:5", "anomaly_types");
        add("energy_anomalies", "general", "ENERGY:5", "anomaly_types");
        add("shadow_anomalies", "general", "SHADOW:5", "anomaly_types");
        add("cognitive_anomalies", "general", "COGNITION:5", "anomaly_types");
        // This is the original category proxy; it unlocks with reality_forge, without a second charge.
        add("reality_forge_category", "reality_forge", "ENERGY:5,SPACE:5,TIME:5", "reality_forge");
        add("resonance_condenser", "reality_forge", "ENERGY:25,SPACE:15", "reality_forge_category");
        add("containment_basics", "reality_forge", "ENERGY:20,SHADOW:15", "reality_forge_category");
        add("echoform_imprinter", "reality_forge", "SHADOW:25,COGNITION:20", "containment_basics");
        add("warp_gun", "reality_forge", "SPACE:15,ENERGY:10", "containment_basics");
        add("chrono_blister", "reality_forge", "TIME:15,ENERGY:10", "containment_basics");
        add("graviton_hammer", "reality_forge", "GRAVITY:20,ENERGY:5", "containment_basics");
        add("stasis_projector", "reality_forge", "GRAVITY:5,TIME:5", "reality_forge_category");
        add("rift_stabilizer", "reality_forge", "ENERGY:20,SPACE:10", "reality_forge_category");
        add("levitation_pad", "reality_forge", "GRAVITY:15,ENERGY:10", "reality_forge_category");
        add("hoverboard", "reality_forge", "GRAVITY:10,ENERGY:15", "containment_basics");
        add("resonant_separation", "reality_forge", "ENERGY:20,GRAVITY:15", "gravitic_transport");
        add("flux_smelting", "reality_forge", "ENERGY:15,TIME:5", "gravitic_transport");
        add("gravitic_transport", "reality_forge", "GRAVITY:15,SPACE:5", "reality_forge");
        add("pattern_assembly", "reality_forge", "COGNITION:25,ENERGY:20,GRAVITY:10", "gravitic_transport");
    }
    private static void add(String id, String category, String costs, String prereq) {
        var map = new EnumMap<ResearchType, Integer>(ResearchType.class);
        if (!costs.isEmpty()) for (String cost : costs.split(",")) {
            String[] pair = cost.split(":"); map.put(ResearchType.valueOf(pair[0]), Integer.parseInt(pair[1]));
        }
        String name = TEXT.getProperty(id + ".name", Arrays.stream(id.split("_")).map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1)).collect(java.util.stream.Collectors.joining(" ")));
        NODES.put(id, new ResearchNode(id, category, name, TEXT.getProperty(id + ".description", ""), map, prereq.isEmpty() ? List.of() : List.of(prereq.split(","))));
    }
    public static ResearchNode get(String id) { return NODES.get(id); }
    public static List<ResearchNode> nodes() { return List.copyOf(NODES.values()); }
    /** Builds and validates a complete immutable catalog before exposing any customization. */
    static Map<String,ResearchNode> load(Path directory) throws IOException {
        var candidate=new LinkedHashMap<>(NODES);Path path=directory.resolve("research-catalog.json");
        if(!Files.exists(path))return Collections.unmodifiableMap(candidate);
        try {
            JsonObject root=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            rejectUnknown(root,Set.of("nodes"));
            if(!root.has("nodes")||!root.get("nodes").isJsonArray())throw new IllegalArgumentException("nodes must be an array");
            Set<String> supplied=new HashSet<>();
            for(JsonElement entry:root.getAsJsonArray("nodes")) {
                JsonObject json=entry.getAsJsonObject();rejectUnknown(json,Set.of("id","category","name","description","costs","prerequisites"));
                String id=string(json,"id",null,128);
                if(!id.matches("[a-z][a-z0-9_]*")||!supplied.add(id))throw new IllegalArgumentException("Invalid or duplicate node id: "+id);
                var previous=candidate.get(id);
                String category=string(json,"category",previous==null?"general":previous.category(),128);
                if(!Set.of("general","reality_forge").contains(category))throw new IllegalArgumentException("Unsupported tablet category: "+category);
                String name=string(json,"name",previous==null?null:previous.name(),128);
                String description=string(json,"description",previous==null?"":previous.description(),16384);
                Map<ResearchType,Integer> costs=previous==null?Map.of():previous.costs();
                if(json.has("costs")) {
                    var parsed=new EnumMap<ResearchType,Integer>(ResearchType.class);
                    var disciplines=EnumSet.noneOf(ResearchType.class);
                    for(var cost:json.getAsJsonObject("costs").entrySet()) {
                        var type=ResearchType.fromName(cost.getKey());
                        if(type==null||!disciplines.add(type))throw new IllegalArgumentException("Invalid/duplicate discipline: "+cost.getKey());
                        if(!cost.getValue().isJsonPrimitive()||!cost.getValue().getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Research costs must be integers");
                        int amount=cost.getValue().getAsBigDecimal().intValueExact();
                        if(amount<0||amount>10000)throw new IllegalArgumentException("Research costs must be 0..10000");
                        if(amount>0)parsed.put(type,amount);
                    }
                    costs=parsed;
                } else if(previous==null)throw new IllegalArgumentException("New node requires explicit costs: "+id);
                List<String> prerequisites=previous==null?List.of():previous.prerequisites();
                if(json.has("prerequisites")) {
                    var parsed=new ArrayList<String>();
                    for(var pre:json.getAsJsonArray("prerequisites")) {
                        if(!pre.isJsonPrimitive()||!pre.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Prerequisites must be node IDs");
                        String value=pre.getAsString();if(parsed.contains(value))throw new IllegalArgumentException("Duplicate prerequisite: "+value);parsed.add(value);
                    }
                    prerequisites=parsed;
                }
                candidate.put(id,new ResearchNode(id,category,name,description,costs,prerequisites));
            }
            var visiting=new HashSet<String>();var visited=new HashSet<String>();
            for(String id:candidate.keySet())visit(id,candidate,visiting,visited);
            return Collections.unmodifiableMap(candidate);
        }catch(RuntimeException ex){throw new IOException("Invalid research-catalog.json: "+ex.getMessage(),ex);}
    }
    private static String string(JsonObject json,String key,String fallback,int limit) {
        if(!json.has(key)){if(fallback==null)throw new IllegalArgumentException("Missing "+key);return fallback;}
        var value=json.get(key);
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException(key+" must be a string");
        String text=value.getAsString();if(text.length()>limit||(!key.equals("description")&&text.isBlank()))throw new IllegalArgumentException("Invalid "+key);return text;
    }
    private static void rejectUnknown(JsonObject json,Set<String> keys){for(String key:json.keySet())if(!keys.contains(key))throw new IllegalArgumentException("Unknown property: "+key);}
    private static void visit(String id,Map<String,ResearchNode> nodes,Set<String> visiting,Set<String> visited){
        if(visited.contains(id))return;
        if(!visiting.add(id))throw new IllegalArgumentException("Research prerequisite cycle at "+id);
        var node=nodes.get(id);if(node==null)throw new IllegalArgumentException("Unknown prerequisite: "+id);
        for(String pre:node.prerequisites())visit(pre,nodes,visiting,visited);
        visiting.remove(id);visited.add(id);
    }
    private ResearchCatalog() {}
}
