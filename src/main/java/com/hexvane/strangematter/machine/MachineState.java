package com.hexvane.strangematter.machine;

import org.joml.Vector3d;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.BsonDocument;
import java.util.*;

public final class MachineState {
    public static final int MAX_RECIPE_SELECTIONS=64;
    public String world, id;
    public int x,y,z,energy,fuelTicks,queuedFuelTicks,progress,age;
    public boolean enabled=true,ascending=true,active;
    public String recipe="", output="", lastAnomaly="";
    /** Stable recipe IDs, oldest selection first. This is independent of the active craft. */
    public Map<String,String> selectedRecipes=new LinkedHashMap<>();
    public int outputQuantity;
    public record FuelCharge(String itemId,int ticks,String metadata,Double durability) {
        public FuelCharge(String itemId,int ticks,String metadata){this(itemId,ticks,metadata,null);}
        public static FuelCharge from(ItemStack stack,int ticks){return new FuelCharge(stack.getItemId(),ticks,stack.getMetadata()==null?null:stack.getMetadata().toJson(),stack.getDurability());}
        public ItemStack toItemStack(){var stack=new ItemStack(itemId,1,metadata==null?null:BsonDocument.parse(metadata));return durability==null?stack:stack.withDurability(durability);}
    }
    public List<FuelCharge> fuelQueue=new ArrayList<>();
    /** Unburned non-fuel from older versions is quarantined for collection instead of destroyed. */
    public List<FuelCharge> recoveredFuel=new ArrayList<>();
    public record ReservedInput(String itemId,int quantity,String metadata,double durability) {
        public static ReservedInput from(ItemStack stack){return new ReservedInput(stack.getItemId(),stack.getQuantity(),stack.getMetadata()==null?null:stack.getMetadata().toJson(),stack.getDurability());}
        public ItemStack toItemStack(){return new ItemStack(itemId,quantity,metadata==null?null:BsonDocument.parse(metadata)).withDurability(durability);}
    }
    public List<ReservedInput> reservedInputs=new ArrayList<>();
    public String owner="";
    public String selectedRecipe(UUID player){return selectedRecipes==null?"":selectedRecipes.getOrDefault(player.toString(),"");}
    public void rememberRecipe(UUID player,String recipeId){
        if(selectedRecipes==null)selectedRecipes=new LinkedHashMap<>();
        String key=player.toString();selectedRecipes.remove(key);selectedRecipes.put(key,recipeId);
        while(selectedRecipes.size()>MAX_RECIPE_SELECTIONS)selectedRecipes.remove(selectedRecipes.keySet().iterator().next());
    }
    public MachineState() {}
    public MachineState(String world,Vector3i position,String id){this.world=world;this.x=position.x;this.y=position.y;this.z=position.z;this.id=id;}
    public String key(){return key(world,x,y,z);}
    public static String key(String world,int x,int y,int z){return world+":"+x+","+y+","+z;}
    public Vector3i block(){return new Vector3i(x,y,z);}
    public Vector3d center(){return new Vector3d(x+.5,y+.5,z+.5);}
    public boolean hasContents(){return outputQuantity>0||!recipe.isEmpty()||queuedFuelTicks>0||fuelTicks>0||!recoveredFuel.isEmpty();}
}
