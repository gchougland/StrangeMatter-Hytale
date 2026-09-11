package com.hexvane.strangematter.research;

import java.nio.file.Files;
import java.util.*;

/** Actual availability and strict unlocks for the common transport prerequisite. */
public final class ResearchAutomationVerification {
    public static void main(String[] args)throws Exception{
        try(var research=new ResearchService(Files.createTempDirectory("sm-automation-graph-"))){verify(research);}
        System.out.println("PASS: Reality Forge unlocks Gravitic Transport, which alone unlocks all three processing branches; costs and strict gates preserved.");
    }
    static void verify(ResearchService research){
        var expected=Map.of(
            "gravitic_transport",Map.of(ResearchType.GRAVITY,15,ResearchType.SPACE,5),
            "resonant_separation",Map.of(ResearchType.ENERGY,20,ResearchType.GRAVITY,15),
            "flux_smelting",Map.of(ResearchType.ENERGY,15,ResearchType.TIME,5),
            "pattern_assembly",Map.of(ResearchType.COGNITION,25,ResearchType.ENERGY,20,ResearchType.GRAVITY,10));
        for(var entry:expected.entrySet()){
            var node=research.node(entry.getKey());
            require(node.costs().equals(entry.getValue()),"Existing point cost is unchanged: "+node.id());
            require(node.prerequisites().equals(List.of(node.id().equals("gravitic_transport")?"reality_forge":"gravitic_transport")),"Only the requested prerequisite is required: "+node.id());
        }
        var player=UUID.randomUUID();research.addPointsAll(player,50);
        require(research.availability(player,research.node("gravitic_transport")).equals("Requires Reality Forge."),"Transport remains blocked before Forge");
        research.unlock(player,"reality_forge",false);
        require(research.availability(player,research.node("gravitic_transport"))==null,"Forge alone makes transport available");
        var branches=List.of("resonant_separation","flux_smelting","pattern_assembly");
        for(var branch:branches){
            require(research.availability(player,research.node(branch)).equals("Requires Gravitic Transport."),"Processing stays blocked until transport: "+branch);
            try{research.unlock(player,branch,false);throw new AssertionError("Strict unlock bypassed transport");}
            catch(IllegalArgumentException expectedFailure){require(!research.hasUnlocked(player,branch),"Rejected strict unlock leaves the branch locked");}
        }
        research.unlock(player,"gravitic_transport",false);
        for(var branch:branches){
            require(research.availability(player,research.node(branch))==null,"Transport makes each branch independently available: "+branch);
            research.unlock(player,branch,false);
        }
        for(var unrelated:List.of("gravity_anomalies","energy_anomalies","cognitive_anomalies","containment_basics"))
            require(!research.hasUnlocked(player,unrelated),"Automation never requires or silently grants unrelated research: "+unrelated);
        for(var type:ResearchType.values())require(research.points(player,type)==50,"Checking gates and admin strict unlocks preserve research points");
        var empty=UUID.randomUUID();research.unlock(empty,"gravitic_transport",true);
        require(research.availability(empty,research.node("pattern_assembly")).startsWith("Scan more "),"The new prerequisite graph still enforces note costs");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private ResearchAutomationVerification(){}
}
