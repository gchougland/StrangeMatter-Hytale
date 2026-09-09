package com.hexvane.strangematter.machine;

import com.google.gson.Gson;
import com.hexvane.strangematter.research.ResearchService;
import com.hexvane.strangematter.effects.MachineLinks;
import com.hexvane.strangematter.anomaly.AnomalyType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import org.joml.Vector3d;
import java.util.*;

/** Regression scenarios use real loaded item assets and native inventory transactions. */
public final class NativeMachineVerification {
    public static void verify(MachineService machines,ResearchService research){
        var inventory=new SimpleItemContainer((short)12);
        var gun=new ItemStack("SM_Warp_Gun",1).withDurability(37);
        var coil=new ItemStack("SM_Resonant_Coil",3);
        var fuel=new ItemStack("Ingredient_Charcoal",2);
        inventory.setItemStackForSlot((short)0,gun,false);inventory.setItemStackForSlot((short)1,coil,false);inventory.setItemStackForSlot((short)2,fuel,false);
        require(gun.getItem().getFuelQuality()>0&&FurnaceFuel.ticks(gun)==0,"Positive native default fuel quality must not make a warp gun combustible");
        var charge=FurnaceFuel.takeFirst(inventory);
        require(charge!=null&&charge.stack().getItemId().equals(fuel.getItemId())&&charge.stack().getQuantity()==1,"Burner selects a real furnace fuel after skipping tools/components");
        require(inventory.getItemStack((short)0).equals(gun)&&inventory.getItemStack((short)1).equals(coil),"Burner preserves preceding gun, its durability and resonant coils");
        require(inventory.getItemStack((short)2).getQuantity()==1,"Burner consumes exactly one native fuel item");
        require(FurnaceFuel.takeFirst(inventory,charge.ticks()-1)==null&&inventory.getItemStack((short)2).getQuantity()==1,"Full fuel magazine never removes an item it cannot fit");
        FurnaceFuel.takeFirst(inventory);require(FurnaceFuel.takeFirst(inventory)==null,"No eligible fuel leaves remaining inventory intact");
        int fuels=0;
        for(var item:Item.getAssetMap().getAssetMap().values()){
            var resource=ItemContainer.getMatchingResourceType(item,"Fuel");
            if(resource==null||resource.quantity<=0||item.getFuelQuality()<=0||!Double.isFinite(item.getFuelQuality()))continue;
            fuels++;require(FurnaceFuel.ticks(new ItemStack(item.getId(),1))>0,"Every usable native furnace fuel is accepted: "+item.getId());
        }
        require(fuels>5,"Native furnace fuel families were loaded");
        var queue=new MachineState("test",new org.joml.Vector3i(),"SM_Resonant_Burner");
        queue.fuelQueue.add(new MachineState.FuelCharge(gun.getItemId(),200,null));queue.fuelQueue.add(new MachineState.FuelCharge(fuel.getItemId(),120,null));queue.queuedFuelTicks=320;
        machines.recoverInvalidFuel(queue);machines.recoverInvalidFuel(queue);
        require(queue.recoveredFuel.size()==1&&queue.fuelQueue.size()==1&&queue.queuedFuelTicks==120,"Old non-fuel is recoverable exactly once and supplies no energy");
        var persisted=new Gson().fromJson(new Gson().toJson(queue),MachineState.class);
        require(persisted.recoveredFuel.size()==1&&persisted.hasContents(),"Recovered item survives a save and prevents destructive dismantling");
        var player=UUID.randomUUID();
        var recipe=machines.recipes.stream().filter(r->r.totalCost().containsKey("resource:Wood_Planks")).findFirst().orElseThrow();
        var empty=new SimpleItemContainer((short)12);var missing=machines.readiness(player,empty,recipe,false);
        require(!missing.ready()&&!missing.researched()&&missing.message().contains("Research:")&&missing.message().contains("Any wood planks"),"Forge lists missing named research and material families together");
        int needed=recipe.totalCost().get("resource:Wood_Planks");
        empty.setItemStackForSlot((short)0,new ItemStack("Wood_Goldenwood_Planks",needed),false);
        empty.setItemStackForSlot((short)1,new ItemStack("Wood_Hardwood_Planks",1),false);
        var prepared=machines.readiness(player,empty,recipe,false);var planks=prepared.materials().stream().filter(m->m.id().equals("resource:Wood_Planks")).findFirst().orElseThrow();
        require(planks.available()==needed+1&&planks.missing()==0&&planks.icon().equals("Wood_Goldenwood_Planks"),"Forge counts mixed plank families and displays an actual available plank icon");
        for(var type:AnomalyType.values())require(com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem.getAssetMap().getAsset(MachineLinks.stream(type))!=null,"Condenser stream asset resolves for "+type);
        var from=new Vector3d(0,1,0);var to=new Vector3d(16,2,0);var path=MachineLinks.arc(from,to,17);
        require(path.size()<=57&&path.getFirst().distance(from)<.00001&&path.getLast().distance(to)<.00001,"Bounded electrical path actually joins anomaly and machine endpoints");
        require(MachineLinks.flowPoint(from,to,0,1).distance(from)<.00001&&MachineLinks.flowPoint(from,to,1,1).distance(to)<.00001,"Condenser flow ends at receiver instead of hovering around anomaly");
        System.out.println("NATIVE_MACHINE_VERIFICATION_PASSED: "+fuels+" furnace fuel assets, exact inventory consumption, old queue recovery, named missing research/materials, mixed plank icons, all6 transfer streams and endpoint geometry.");
    }
    public static void verifyGrounding(com.hypixel.hytale.server.core.universe.world.World world,MachineService machines){
        var position=new org.joml.Vector3i(19,10,19);world.setBlock(position.x,position.y,position.z,"SM_Rift_Stabilizer");
        var state=machines.register(world,position,"SM_Rift_Stabilizer");
        require(machines.grounded(world,state.center(),8),"Enabled registered stabilizer grounds its nearby rift");
        machines.toggle(state);require(!machines.grounded(world,state.center(),8),"Disabled stabilizer immediately stops grounding");
        machines.toggle(state);require(machines.grounded(world,state.center(),8),"Re-enabled stabilizer resumes grounding");
        world.setBlock(position.x,position.y,position.z,"Empty");require(!machines.grounded(world,state.center(),8),"Missing physical block cannot ground even before registry cleanup");
        machines.removed(world,position);
        System.out.println("NATIVE_GROUNDING_VERIFICATION_PASSED: enabled/disabled/restored/missing physical stabilizer with lock-free anomaly hook.");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
