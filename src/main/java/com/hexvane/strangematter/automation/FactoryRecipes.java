package com.hexvane.strangematter.automation;

import com.google.gson.Gson;
import com.hexvane.strangematter.machine.*;
import com.hexvane.strangematter.util.InventoryOps;
import com.hypixel.hytale.builtin.crafting.CraftingPlugin;
import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.server.core.asset.type.item.config.*;
import com.hypixel.hytale.server.core.inventory.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Actual loaded native recipes, with explicit ore conversions and the original forge catalog. */
public final class FactoryRecipes {
    private static final Gson GSON=new Gson();
    private record Separation(String id,String ore,String bar,String concentrate) {}
    private static final List<Separation> SEPARATIONS=separations();
    private static final Map<MachineService,List<Separation>> SEPARATION_OVERRIDES=Collections.synchronizedMap(new WeakHashMap<>());
    /** Optional server definitions are read once at service creation, never from a machine tick. */
    static void loadSeparations(MachineService machines){
        var path=machines.dataDirectory().resolve("separator-recipes.json");if(!java.nio.file.Files.exists(path))return;
        try(var reader=java.nio.file.Files.newBufferedReader(path,StandardCharsets.UTF_8)){
            var additions=GSON.fromJson(reader,Separation[].class);validateSeparations(additions);
            var merged=new LinkedHashMap<String,Separation>();for(var entry:SEPARATIONS)merged.put(entry.id,entry);for(var entry:additions)merged.put(entry.id,entry);
            SEPARATION_OVERRIDES.put(machines,List.copyOf(merged.values()));
        }catch(java.io.IOException|RuntimeException failure){throw new IllegalStateException("Invalid separator recipe definitions in "+path,failure);}
    }
    private static void validateSeparations(Separation[] values){
        if(values==null)throw new IllegalStateException("Separator recipes must be an array");
        var ids=new HashSet<String>();for(var value:values)if(value==null||value.id==null||value.id.isBlank()||!ids.add(value.id)||value.ore==null||value.ore.isBlank()||value.bar==null||value.bar.isBlank()||value.concentrate==null||value.concentrate.isBlank()||value.ore.equals(value.bar)||value.ore.equals(value.concentrate)||value.bar.equals(value.concentrate))throw new IllegalStateException("Invalid or repeated separator recipe definition");
    }
    private static List<Separation> separations(){
        try(var stream=FactoryRecipes.class.getResourceAsStream("/Server/StrangeMatter/Factory/Separator.json")){
            if(stream==null)throw new IllegalStateException("Missing separator recipe mapping");
            var values=GSON.fromJson(new java.io.InputStreamReader(stream,StandardCharsets.UTF_8),Separation[].class);
            validateSeparations(values);
            return List.of(values);
        }catch(java.io.IOException failure){throw new IllegalStateException("Cannot read separator recipes",failure);}
    }
    public record Recipe(String id,String output,String category,List<MaterialQuantity> inputs,List<ItemStack> outputs,
                         double seconds,CraftingRecipe nativeRecipe,boolean forge,String fingerprint) {}
    public static List<Recipe> list(MachineService machines,String machine){
        var recipes=new ArrayList<Recipe>();
        if(machine.equals("SM_Reality_Forge")){
            for(var r:machines.recipes){var in=new ArrayList<MaterialQuantity>();r.totalCost().forEach((id,n)->in.add(new MaterialQuantity(id.startsWith("resource:")?null:id,id.startsWith("resource:")?id.substring(9):null,null,n,null)));
                recipes.add(new Recipe(r.id,r.output,"Reality Forge",List.copyOf(in),List.of(new ItemStack(r.output,r.quantity)),machines.config.forgeCraftTicks/20d,null,true,digest(GSON.toJson(r))));}
        } else if(machine.equals("SM_Resonant_Separator")){
            var nativeRecipes=CraftingPlugin.getBenchRecipes(BenchType.Processing,"Furnace");
            for(var mapping:SEPARATION_OVERRIDES.getOrDefault(machines,SEPARATIONS)){String ore=mapping.ore,bar=mapping.bar,concentrate=mapping.concentrate;
                if(Item.getAssetMap().getAsset(ore)==null||Item.getAssetMap().getAsset(bar)==null||Item.getAssetMap().getAsset(concentrate)==null)continue;
                var source=nativeRecipes.stream().filter(r->r.getInput()!=null&&r.getInput().length==1&&ore.equals(r.getInput()[0].getItemId())&&r.getOutputs().length==1&&bar.equals(r.getOutputs()[0].getItemId()))
                    .sorted(Comparator.comparing(CraftingRecipe::getId)).findFirst().orElse(null);if(source==null)continue;
                var in=List.of(source.getInput()[0].clone(source.getInput()[0].getQuantity()));int yield=source.getOutputs()[0].getQuantity()*2;
                recipes.add(new Recipe("separator:"+mapping.id,concentrate,"Ores",in,List.of(new ItemStack(concentrate,yield)),machines.config.separatorCraftTicks/20d,null,false,digest(ore+":"+in.getFirst().getQuantity()+":"+yield+":"+concentrate)));
            }
        } else {
            String bench=machine.equals("SM_Flux_Furnace")?"Furnace":"Workbench";
            for(var r:CraftingPlugin.getBenchRecipes(bench.equals("Furnace")?BenchType.Processing:BenchType.Crafting,bench)){
                if(r.getPrimaryOutput()==null||r.getPrimaryOutput().getItemId()==null)continue;
                var outputs=CraftingManager.getOutputItemStacks(r);if(outputs.isEmpty())continue;
                String category=Arrays.stream(r.getBenchRequirement()).filter(b->bench.equals(b.id)).map(b->b.categories==null||b.categories.length==0?"Other":b.categories[0]).findFirst().orElse("Other");
                recipes.add(new Recipe(r.getId(),r.getPrimaryOutput().getItemId(),category,CraftingManager.getInputMaterials(r),List.copyOf(outputs),r.getTimeSeconds(),r,false,digest(GSON.toJson(r.toPacket(r.getId())))));
            }
        }
        return recipes.stream().sorted(Comparator.comparing((Recipe r)->InventoryOps.label(r.output)).thenComparing(Recipe::id)).toList();
    }
    static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private FactoryRecipes(){}
}
