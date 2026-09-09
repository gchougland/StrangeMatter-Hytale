package com.hexvane.strangematter.worldgen;

import java.util.*;

/** Exact AnomalyScientistTrades offer pool; a villager selects two random offers per unlocked tier. */
public final class ScientistTrades {
    public static final String LIFE_ESSENCE="Ingredient_Life_Essence";
    public record Offer(String id,int tier,String input,int inputCount,String output,int outputCount,int maxUses,int merchantXp) {}
    public static final List<Offer> ALL=List.of(
        offer("raw_resonite",1,LIFE_ESSENCE,8,"SM_Raw_Resonite",4,12,2),
        offer("buy_raw_resonite",1,"SM_Raw_Resonite",6,LIFE_ESSENCE,1,16,2),
        offer("resonite_ingot",2,LIFE_ESSENCE,12,"SM_Resonite_Ingot",3,12,5),
        offer("buy_resonite_ingot",2,"SM_Resonite_Ingot",2,LIFE_ESSENCE,1,16,5),
        offer("resonite_nugget",2,LIFE_ESSENCE,5,"SM_Resonite_Nugget",9,16,5),
        offer("resonant_coil",3,LIFE_ESSENCE,20,"SM_Resonant_Coil",1,8,10),
        offer("gravitic_shard",3,LIFE_ESSENCE,16,"SM_Gravitic_Shard",1,4,10),
        offer("spatial_shard",3,LIFE_ESSENCE,16,"SM_Spatial_Shard",1,4,10),
        offer("stabilized_core",4,LIFE_ESSENCE,24,"SM_Stabilized_Core",1,6,15),
        offer("chrono_shard",4,LIFE_ESSENCE,18,"SM_Chrono_Shard",1,4,15),
        offer("energetic_shard",4,LIFE_ESSENCE,18,"SM_Energetic_Shard",1,4,15),
        offer("resonant_circuit",5,LIFE_ESSENCE,30,"SM_Resonant_Circuit",1,4,30),
        offer("shade_shard",5,LIFE_ESSENCE,20,"SM_Shade_Shard",1,3,30),
        offer("insight_shard",5,LIFE_ESSENCE,20,"SM_Insight_Shard",1,3,30),
        offer("resonite_block",5,LIFE_ESSENCE,48,"SM_Resonite_Block",1,2,30));
    private static Offer offer(String id,int tier,String input,int count,String output,int quantity,int stock,int xp){return new Offer(id,tier,input,count,output,quantity,stock,xp);}
    public static Offer get(String id){return ALL.stream().filter(o->o.id.equals(id)).findFirst().orElse(null);}
    public static int tier(int xp){return xp>=250?5:xp>=150?4:xp>=70?3:xp>=10?2:1;}
    public static String tierName(int tier){return List.of("Novice","Apprentice","Journeyman","Expert","Master").get(Math.clamp(tier,1,5)-1);}
    public static List<Offer> selected(UUID identity,int tier) {
        List<Offer> result=new ArrayList<>();
        for(int level=1;level<=tier;level++) {
            List<Offer> pool=new ArrayList<>();for(Offer o:ALL)if(o.tier==level)pool.add(o);
            Collections.shuffle(pool,new Random(identity.getMostSignificantBits()^identity.getLeastSignificantBits()^level*0x9e3779b97f4a7c15L));
            result.addAll(pool.subList(0,Math.min(2,pool.size())));
        }
        return List.copyOf(result);
    }
    public static int price(Offer offer,int demand){return Math.clamp(offer.inputCount+Math.max(0,(int)Math.floor(offer.inputCount*(double)demand*.05)),1,64);}
    private ScientistTrades(){}
}
