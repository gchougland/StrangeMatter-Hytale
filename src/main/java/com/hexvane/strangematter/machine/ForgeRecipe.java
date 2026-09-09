package com.hexvane.strangematter.machine;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ForgeRecipe {
    public String id,output,research,station,source;
    public int quantity;
    public double seconds;
    public Map<String,Integer> ingredients=Map.of(),shards=Map.of();
    public Map<String,Integer> totalCost(){
        Map<String,Integer> result=new LinkedHashMap<>(ingredients);
        // This catalog substitution represents the source's minecraft:planks tag.
        // Resolve the native resource family, as the workbench recipes do.
        Integer planks=result.remove("Wood_Softwood_Planks");
        if(planks!=null)result.merge("resource:Wood_Planks",planks,Integer::sum);
        shards.forEach((k,v)->result.merge("SM_"+Character.toUpperCase(k.charAt(0))+k.substring(1)+"_Shard",v,Integer::sum));
        return result;
    }
    public static List<ForgeRecipe> load() throws IOException {
        try(var stream=ForgeRecipe.class.getResourceAsStream("/Server/StrangeMatter/recipes.json")) {
            if(stream==null)throw new IOException("Missing recipe catalog");
            var data=new Gson().fromJson(new InputStreamReader(stream,StandardCharsets.UTF_8),ForgeRecipe[].class);
            return Arrays.stream(data).filter(r->"forge".equals(r.station)).toList();
        }
    }
}
