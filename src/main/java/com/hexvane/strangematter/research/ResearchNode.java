package com.hexvane.strangematter.research;

import java.util.List;
import java.util.Map;

public record ResearchNode(String id, String category, String name, String description,
                           Map<ResearchType, Integer> costs, List<String> prerequisites) {
    public ResearchNode { costs = Map.copyOf(costs); prerequisites = List.copyOf(prerequisites); }
    public boolean defaultUnlocked() { return costs.isEmpty(); }
    public String costSummary() {
        return java.util.Arrays.stream(ResearchType.values()).filter(costs::containsKey)
                .map(t -> t.displayName() + " " + costs.get(t)).collect(java.util.stream.Collectors.joining("  /  "));
    }
}
