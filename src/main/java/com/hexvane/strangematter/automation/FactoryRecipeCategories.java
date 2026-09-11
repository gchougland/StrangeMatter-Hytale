package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.util.InventoryOps;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.CraftingBench;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import java.util.*;

/** Native Workbench tab order and membership, including recipes declared in more than one category. */
public final class FactoryRecipeCategories {
    public record Tab(String id,String label) {}
    public static List<Tab> tabs(List<FactoryRecipes.Recipe> known,String language){
        var result=new LinkedHashMap<String,Tab>();result.put("All",new Tab("All","All recipes"));
        var block=BlockType.getAssetMap().getAsset("Bench_WorkBench");
        if(block!=null&&block.getBench() instanceof CraftingBench bench)for(var category:bench.getCategories())
            result.put(category.getId(),new Tab(category.getId(),translated(category.getName(),language,friendly(category.getId()))));
        for(var recipe:known)for(var id:categories(recipe))result.putIfAbsent(id,new Tab(id,friendly(id)));
        return List.copyOf(result.values());
    }
    public static List<String> categories(FactoryRecipes.Recipe recipe){
        var result=new LinkedHashSet<String>();var nativeRecipe=recipe.nativeRecipe();
        if(nativeRecipe!=null)for(var requirement:nativeRecipe.getBenchRequirement())if(requirement.type==BenchType.Crafting&&"Workbench".equals(requirement.id)&&requirement.categories!=null)Collections.addAll(result,requirement.categories);
        if(result.isEmpty())result.add(recipe.category());return List.copyOf(result);
    }
    public static boolean matches(FactoryRecipes.Recipe recipe,String category){return "All".equals(category)||categories(recipe).contains(category);}
    public static String itemName(String id,String language){var item=Item.getAssetMap().getAsset(id);return item==null?InventoryOps.label(id):translated(item.getTranslationKey(),language,InventoryOps.label(id));}
    private static String friendly(String id){String value=InventoryOps.label(id.replaceFirst("^Workbench_",""));return value.isBlank()?"Other":value;}
    private static String translated(String key,String language,String fallback){if(key==null)return fallback;var i18n=I18nModule.get();String value=i18n==null?null:i18n.getMessage(language,key);return value==null||value.isBlank()||value.equals(key)?fallback:value;}
    private FactoryRecipeCategories(){}
}
