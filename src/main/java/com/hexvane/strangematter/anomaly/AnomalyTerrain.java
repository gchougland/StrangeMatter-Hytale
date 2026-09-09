package com.hexvane.strangematter.anomaly;

import java.util.Set;

/** Native terrain families; this classifier is only used behind the fresh-chunk generation guard. */
final class AnomalyTerrain {
    private static final Set<String> SHAPED=Set.of("Brick","Bricks","Cobble","Stairs","Half","Quarter","ThreeQuarter","Wall","Fence","Roof","Beam","Pillar","Tile","Tiles","Smooth","Ornate","Decorative","Path","Pathway","Tilled","Farmland","Cultivated","Polished","Carved","Chiseled","Stalactite","Stalactites","Stalagmite","Icicles","Pipe","Processed");
    static boolean soil(String id){return id!=null&&id.startsWith("Soil_")&&!shaped(id);}
    static boolean rock(String id){
        if(id==null||!id.startsWith("Rock_")||shaped(id))return false;
        for(String part:id.split("_"))if(part.equals("Bedrock")||part.equals("Ore")||part.equals("Gem")||part.equals("Crystal"))return false;
        return true;
    }
    private static boolean shaped(String id){for(String part:id.split("_"))if(SHAPED.contains(part))return true;return false;}
    private AnomalyTerrain(){}
}
