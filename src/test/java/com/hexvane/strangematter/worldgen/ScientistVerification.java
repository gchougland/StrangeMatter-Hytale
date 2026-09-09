package com.hexvane.strangematter.worldgen;

import com.hexvane.strangematter.progression.ProgressionService;
import com.hexvane.strangematter.research.ResearchNode;
import com.hexvane.strangematter.research.ResearchType;
import java.nio.file.Files;
import java.util.*;

/** Regression checks for shared stock, seeded offer progression and the source advancement requirement semantics. */
public final class ScientistVerification {
    public static void main(String[] args)throws Exception {
        trades();advancements();
        System.out.println("PASS: scientist stock/demand/training, source OR/AND milestones and advancement persistence.");
    }
    private static void trades() {
        check(ScientistTrades.ALL.size()==15,"Original fifteen-offer pool");
        for(var offer:ScientistTrades.ALL)check("Ingredient_Life_Essence".equals(offer.input())||"Ingredient_Life_Essence".equals(offer.output()),"Every buy/sell offer uses the requested native Life Essence currency");
        check(ScientistTrades.tier(9)==1&&ScientistTrades.tier(10)==2&&ScientistTrades.tier(69)==2&&ScientistTrades.tier(70)==3&&ScientistTrades.tier(150)==4&&ScientistTrades.tier(250)==5,"Vanilla merchant experience thresholds");
        UUID identity=UUID.fromString("cddc397a-c00f-4e80-aa4a-e0131986c6ea");
        var state=new ScientistRecord(identity,"test",0,100,0);
        check(state.offers().size()==2,"The two source novice trades are always offered");
        var sell=ScientistTrades.get("raw_resonite");
        for(int i=0;i<12;i++)state.traded(sell);
        check(state.stock(sell)==0&&state.xp==24,"Shared use counter consumes exactly twelve stocks and awards merchant XP");
        check(state.offers().size()==4&&state.offers().containsAll(ScientistTrades.selected(identity,1)),"Rank keeps previous offers and adds two");
        state.restock(100);check(state.stock(sell)==0,"First clock observation does not create bonus stock");
        check(state.restock(101)&&state.stock(sell)==12,"Next workplace restock restores stock");
        check(state.price(sell)==12,"Source 0.05 demand multiplier increases exhausted offer price");
        check(!state.restock(101)&&!state.restock(99),"No repeat restock or rewind exploit");
        state.xp=250;check(state.offers().size()==10,"A master offers two selections from each original tier");
        for(var offer:ScientistTrades.ALL)check(offer.inputCount()>0&&offer.outputCount()>0&&offer.maxUses()>0,"Trades exchange positive quantities");
        check(ScientistTrades.get("resonite_block").inputCount()==48&&ScientistTrades.get("resonite_block").maxUses()==2,"Original scarce master block trade");
        check(ScientistTrades.price(sell,Integer.MAX_VALUE)==64&&ScientistTrades.price(sell,-20)==8,"Demand bounds preserve source base floor and stack price cap");
    }
    private static void advancements()throws Exception {
        var directory=Files.createTempDirectory("sm-advancements-test-");UUID player=UUID.randomUUID();
        var service=new ProgressionService(directory);
        check(service.snapshot(player).size()==13,"All thirteen original advancements imported");
        service.scanned(player,ResearchType.SHADOW);
        check(service.complete(player,"anomaly_collector"),"Source collector requirement is OR, despite all-six description");
        check(!service.complete(player,"field_researcher"),"Advancement ancestry does not invent crafting prerequisites");
        service.firstContact(player);service.firstContact(player);
        check(service.complete(player,"first_contact"),"First contact records once without currency rewards");
        service.inventoryChanged(player,List.of("SM_Research_Notes","SM_Containment_Capsule_Energetic"));
        check(service.complete(player,"the_researcher")&&service.complete(player,"anomaly_tamer"),"Original actual inventory predicates");
        for(ResearchType type:ResearchType.values()) {
            if(type==ResearchType.TIME)continue;
            service.completedResearch(player,new ResearchNode("test","general","Test","",Map.of(type,1),List.of()));
        }
        check(!service.complete(player,"knowledge_seeker"),"Five disciplines do not fulfill six AND groups");
        service.completedResearch(player,new ResearchNode("test","general","Test","",Map.of(ResearchType.TIME,1),List.of()));
        check(service.complete(player,"knowledge_seeker"),"All six researched disciplines complete source challenge");
        service.joined(player);service.save();
        var restored=new ProgressionService(directory);
        check(restored.complete(player,"root")&&restored.complete(player,"knowledge_seeker")&&restored.complete(player,"anomaly_collector"),"Criteria and completions survive reload");
        UUID other=UUID.randomUUID();check(restored.snapshot(other).stream().noneMatch(ProgressionService.Milestone::complete),"Progress belongs to its player");
        Files.delete(directory.resolve("advancements.properties"));Files.delete(directory);
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
