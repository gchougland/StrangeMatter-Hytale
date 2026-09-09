package com.hexvane.strangematter.anomaly;

import java.util.Map;
import java.util.Optional;

/** Roles, not model IDs: this preserves juvenile behavior, health/drop configuration, and taming variants. */
public final class TemporalRoles {
    private static final Map<String,String> PAIRS=Map.ofEntries(
        Map.entry("Cow","Cow_Calf"),Map.entry("Sheep","Sheep_Lamb"),Map.entry("Pig","Pig_Piglet"),
        Map.entry("Pig_Wild","Pig_Wild_Piglet"),Map.entry("Chicken","Chicken_Chick"),
        Map.entry("Chicken_Desert","Chicken_Desert_Chick"),Map.entry("Horse","Horse_Foal"),
        Map.entry("Bison","Bison_Calf"),Map.entry("Camel","Camel_Calf"),Map.entry("Boar","Boar_Piglet"),
        Map.entry("Warthog","Warthog_Piglet"),Map.entry("Turkey","Turkey_Chick"),
        Map.entry("Skrill","Skrill_Chick"),Map.entry("Ram","Ram_Lamb"),Map.entry("Mouflon","Mouflon_Lamb")
    );
    public record Transition(String adult,String juvenile,boolean becomingYoung) {
        public String target() {return becomingYoung?juvenile:adult;}
    }
    public static Optional<Transition> transition(String role) {
        if(role==null)return Optional.empty();
        String prefix=role.startsWith("Tamed_")?"Tamed_":"",base=role.substring(prefix.length());
        if(PAIRS.containsKey(base))return Optional.of(new Transition(role,prefix+PAIRS.get(base),true));
        for(var pair:PAIRS.entrySet())if(pair.getValue().equals(base))return Optional.of(new Transition(prefix+pair.getKey(),role,false));
        return Optional.empty();
    }
}
