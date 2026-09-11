package com.hexvane.strangematter.machine;

import com.google.gson.Gson;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import org.bson.BsonDocument;
import org.joml.Vector3i;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Invoked by the isolated server harness after real native item assets have loaded. */
public final class MachineControlsVerification {
    public static void verify(World world,MachineService machines)throws Exception{
        verifySelections(world,machines);
        verifyFuel(machines);
        System.out.println("MACHINE_CONTROLS_VERIFICATION_PASSED: stable per-player/per-forge recipe IDs and disk/page reopen, bounded selection history, active+queued capacity, atomic Load Max across native inventory containers, Load One, whole-item fit, metadata/durability refunds, sequential burn and legacy recovery.");
    }
    private static void verifySelections(World world,MachineService machines)throws Exception{
        require(machines.recipes.size()>3,"Real forge catalog available");
        UUID alice=UUID.randomUUID(),bob=UUID.randomUUID();
        var first=new MachineState(world.getName(),new Vector3i(101,10,101),"SM_Reality_Forge");
        var second=new MachineState(world.getName(),new Vector3i(102,10,101),"SM_Reality_Forge");
        String recipeA=machines.recipes.get(2).id,recipeB=machines.recipes.get(3).id;
        Path directory=Files.createTempDirectory(machines.dataDirectory(),"machine-controls-");
        Files.writeString(directory.resolve("machines.json"),new Gson().toJson(Map.of("machines",List.of(first,second))));
        var service=new MachineService(directory,machines.config,machines.research,machines.anomalies);
        first=service.get(world,first.block());second=service.get(world,second.block());
        service.selectRecipe(first,alice,recipeA);service.selectRecipe(first,bob,recipeB);service.selectRecipe(second,alice,recipeB);
        first.recipe=machines.recipes.getFirst().id;first.progress=37;
        service.save();
        var reopened=new MachineService(directory,machines.config,machines.research,machines.anomalies);
        var restored=reopened.get(world,first.block());var other=reopened.get(world,second.block());
        require(reopened.recipes.get(reopened.selectedRecipeIndex(restored,alice)).id.equals(recipeA),"First player's last recipe survives actual machines.json restart");
        require(reopened.recipes.get(reopened.selectedRecipeIndex(restored,bob)).id.equals(recipeB),"Second player has an independent selection at the same forge");
        require(reopened.recipes.get(reopened.selectedRecipeIndex(other,alice)).id.equals(recipeB),"Same player has an independent selection at a different forge");
        require(restored.recipe.equals(first.recipe)&&restored.progress==37,"Browsing never changes the recipe already coalescing");
        reopened.selectRecipe(restored,alice,"removed_recipe");
        require(restored.selectedRecipe(alice).equals(recipeA),"Unknown recipe IDs cannot replace a valid selection");
        var player=new PlayerRef(null,alice,"Machine controls fixture","en-US",null,null);
        var page=new MachinePage(player,reopened,restored);
        var index=MachinePage.class.getDeclaredField("recipeIndex");index.setAccessible(true);
        require((int)index.get(page)==2,"Actual newly opened page starts on the persisted recipe");
        // Persistence contains IDs rather than mutable catalog positions.
        String json=Files.readString(directory.resolve("machines.json"));
        require(json.contains(recipeA)&&json.contains("selectedRecipes")&&!json.contains("recipeIndex"),"Disk preference uses stable IDs");
        restored.selectedRecipes.put(alice.toString(),"removed_recipe");
        require(reopened.selectedRecipeIndex(restored,alice)==0,"Removed recipes safely fall back to the first current recipe");
        var legacy=new Gson().fromJson("{\"world\":\"test\",\"id\":\"SM_Reality_Forge\"}",MachineState.class);
        require(reopened.selectedRecipeIndex(legacy,alice)==0,"Legacy saves without preferences open normally");
        var bounded=new MachineState(world.getName(),new Vector3i(),"SM_Reality_Forge");
        UUID oldest=UUID.randomUUID(),recent=UUID.randomUUID();bounded.rememberRecipe(oldest,recipeA);bounded.rememberRecipe(recent,recipeA);
        for(int i=2;i<MachineState.MAX_RECIPE_SELECTIONS;i++)bounded.rememberRecipe(UUID.randomUUID(),recipeA);
        bounded.rememberRecipe(oldest,recipeB);bounded.rememberRecipe(UUID.randomUUID(),recipeA);
        require(bounded.selectedRecipes.size()==MachineState.MAX_RECIPE_SELECTIONS&&bounded.selectedRecipe(oldest).equals(recipeB)&&bounded.selectedRecipe(recent).isEmpty(),"Bounded map evicts the oldest selection and refreshes recently changed players");
    }
    private static void verifyFuel(MachineService machines){
        var fuel=new ItemStack("Ingredient_Charcoal",8,BsonDocument.parse("{\"OwnerMark\":\"actual-stack\",\"Nested\":{\"Value\":7}}"));
        int duration=FurnaceFuel.ticks(fuel);require(duration>0&&duration*5<FurnaceFuel.MAX_FUEL_TICKS,"Real charcoal duration fits bulk scenario");
        var pack=new SimpleItemContainer((short)3);var hotbar=new SimpleItemContainer((short)3);
        var combined=new CombinedItemContainer(pack,hotbar);
        var tool=new ItemStack("SM_Warp_Gun",1).withDurability(37);
        pack.setItemStackForSlot((short)0,tool,false);pack.setItemStackForSlot((short)1,fuel.withQuantity(2),false);hotbar.setItemStackForSlot((short)0,fuel,false);
        var burner=new MachineState("test",new Vector3i(),"SM_Resonant_Burner");
        burner.fuelTicks=FurnaceFuel.MAX_FUEL_TICKS-3*duration;
        var events=new AtomicInteger();var violation=new AtomicInteger();
        var registration=combined.registerChangeEvent(event->{
            events.incrementAndGet();
            if(FurnaceFuel.storedTicks(burner)!=FurnaceFuel.MAX_FUEL_TICKS||burner.fuelQueue.size()!=3||hotbar.getItemStack((short)0).getQuantity()!=7)violation.incrementAndGet();
        });
        String loaded=machines.fuel(combined,burner,true);registration.unregister();
        require(loaded.startsWith("Loaded 3 fuel items"),"Load Max reserves multiple whole native fuel items in one action");
        require(events.get()>0&&violation.get()==0,"Native change listeners observe the complete inventory and machine queue together");
        require(pack.getItemStack((short)0).equals(tool)&&pack.getItemStack((short)1)==null&&hotbar.getItemStack((short)0).getQuantity()==7,"Bulk transaction spans containers and preserves unrelated damaged tools");
        require(FurnaceFuel.storedTicks(burner)==FurnaceFuel.MAX_FUEL_TICKS&&burner.queuedFuelTicks==3*duration,"Maximum includes the currently burning fuel");
        machines.fuel(combined,burner,true);machines.fuel(combined,burner,false);
        require(hotbar.getItemStack((short)0).getQuantity()==7,"Full capacity consumes nothing through either action");
        var persisted=new Gson().fromJson(new Gson().toJson(burner),MachineState.class);
        require(persisted.fuelQueue.size()==3&&persisted.fuelQueue.stream().allMatch(c->com.hexvane.strangematter.util.StackData.metadata(c.toItemStack()).equals(com.hexvane.strangematter.util.StackData.metadata(fuel))),"Every queued fuel refund preserves nested BSON through save/load");
        var single=new MachineState("test",new Vector3i(),"SM_Resonant_Burner");machines.fuel(combined,single,false);
        require(single.fuelQueue.size()==1&&hotbar.getItemStack((short)0).getQuantity()==6,"Load One continues consuming exactly one item");
        var tight=new MachineState("test",new Vector3i(),"SM_Resonant_Burner");tight.fuelTicks=FurnaceFuel.MAX_FUEL_TICKS-duration+1;
        machines.fuel(combined,tight,true);require(tight.fuelQueue.isEmpty()&&hotbar.getItemStack((short)0).getQuantity()==6,"Unfittable fuel is never partially consumed");
        var legacyOverfull=new MachineState("test",new Vector3i(),"SM_Resonant_Burner");legacyOverfull.fuelTicks=100;legacyOverfull.queuedFuelTicks=FurnaceFuel.MAX_FUEL_TICKS;
        machines.fuel(combined,legacyOverfull,true);
        require(legacyOverfull.queuedFuelTicks==FurnaceFuel.MAX_FUEL_TICKS&&hotbar.getItemStack((short)0).getQuantity()==6,"Old over-capacity saves retain their fuel but reject new loading");
        var planned=new SimpleItemContainer((short)2);planned.setItemStackForSlot((short)0,fuel,false);planned.setItemStackForSlot((short)1,fuel,false);
        try{FurnaceFuel.takeUpTo(planned,FurnaceFuel.MAX_FUEL_TICKS,4,charges->{throw new IllegalStateException("reservation failure");});throw new AssertionError("Expected publisher failure");}catch(IllegalStateException expected){}
        require(planned.getItemStack((short)0).equals(fuel)&&planned.getItemStack((short)1).equals(fuel),"Failure preparing machine reservation leaves every native inventory slot intact");
        var sequential=new MachineState("test",new Vector3i(),"SM_Resonant_Burner");sequential.fuelTicks=2;sequential.queuedFuelTicks=5;
        sequential.fuelQueue.add(MachineState.FuelCharge.from(fuel,2));sequential.fuelQueue.add(MachineState.FuelCharge.from(fuel,3));
        require(MachineService.consumeFuelTick(sequential)&&MachineService.consumeFuelTick(sequential)&&sequential.fuelQueue.size()==2,"Active fuel finishes before any queued item begins");
        require(MachineService.consumeFuelTick(sequential)&&sequential.fuelTicks==1&&sequential.queuedFuelTicks==3&&sequential.fuelQueue.size()==1,"First queued item starts with its own exact duration");
        for(int i=0;i<4;i++)require(MachineService.consumeFuelTick(sequential),"Remaining queued duration burns sequentially");
        require(!MachineService.consumeFuelTick(sequential)&&sequential.fuelTicks==0&&sequential.queuedFuelTicks==0&&sequential.fuelQueue.isEmpty(),"Queue produces exactly the reserved number of powered ticks");
        var scalar=new MachineState("test",new Vector3i(),"SM_Resonant_Burner");scalar.queuedFuelTicks=2;
        require(MachineService.consumeFuelTick(scalar)&&MachineService.consumeFuelTick(scalar)&&!MachineService.consumeFuelTick(scalar),"Legacy scalar-only fuel is preserved and consumed once");
        var recovery=new MachineState("test",new Vector3i(),"SM_Resonant_Burner");
        var markedTool=tool.withMetadata("OwnerMark",com.hypixel.hytale.codec.Codec.STRING,"recoverable");
        recovery.fuelQueue.add(MachineState.FuelCharge.from(markedTool,200));recovery.queuedFuelTicks=200;
        machines.recoverInvalidFuel(recovery);machines.recoverInvalidFuel(recovery);
        var recovered=new Gson().fromJson(new Gson().toJson(recovery),MachineState.class).recoveredFuel.getFirst().toItemStack();
        require(recovery.recoveredFuel.size()==1&&recovery.queuedFuelTicks==0&&recovered.equals(markedTool),"Legacy non-fuel recovery remains idempotent and preserves metadata and durability");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
