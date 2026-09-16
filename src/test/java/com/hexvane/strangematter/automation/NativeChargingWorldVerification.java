package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.StrangeMatterConfig;
import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hexvane.strangematter.equipment.GadgetEnergy;
import com.hexvane.strangematter.machine.MachineService;
import com.hexvane.strangematter.research.ResearchService;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import java.nio.file.Files;
import java.util.*;
import org.joml.Vector3i;
import static com.hypixel.hytale.server.core.universe.world.SetBlockSettings.*;

/** Real native machine holders and 20 Hz routing: the dock and station share one conserved supply. */
public final class NativeChargingWorldVerification {
    private record Cell(int x,int y,int z,int id,int rotation,int filler){}
    public static void verify(World world,ResearchService research)throws Exception {
        world.debugAssertInTickingThread();var directory=Files.createTempDirectory("sm-native-charging-world-");
        var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunk(0,0));require(chunk!=null,"Charging fixture uses a loaded chunk");
        var cells=new ArrayList<Cell>();var anomalies=new AnomalyService(directory);
        try(var machines=new MachineService(directory,new StrangeMatterConfig(),research,anomalies);var factory=new FactoryService(machines,research)){
            machines.setFactory(factory);
            try{
                for(int x=2;x<=9;x++)for(int y=238;y<=244;y++)for(int z=9;z<=12;z++){
                    cells.add(new Cell(x,y,z,WorldAccess.block(chunk,x,y,z),WorldAccess.rotation(chunk,x,y,z),WorldAccess.filler(chunk,x,y,z)));
                    WorldAccess.set(chunk,x,y,z,y==239?"Rock_Stone":"Empty",NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);
                }
                var sourcePos=new Vector3i(4,240,10);var conduitPos=new Vector3i(5,240,10);var targetPos=new Vector3i(6,240,10);
                WorldAccess.set(chunk,4,240,10,"SM_Resonant_Burner");WorldAccess.set(chunk,5,240,10,"SM_Resonant_Conduit");WorldAccess.set(chunk,6,240,10,"SM_Resonant_Charging_Station");
                var burner=machines.register(world,sourcePos,"SM_Resonant_Burner");machines.register(world,conduitPos,"SM_Resonant_Conduit");var charger=machines.register(world,targetPos,"SM_Resonant_Charging_Station");
                var owner=UUID.randomUUID();factory.claim(world,burner,owner);factory.claim(world,charger,owner);
                var source=factory.component(world,burner);var target=factory.component(world,charger);
                var scanner=GadgetEnergy.withCharge(new ItemStack("SM_Field_Scanner",1),0);var gun=GadgetEnergy.withCharge(new ItemStack("SM_Warp_Gun",1),0);
                require(source.charging.setItemStackForSlot((short)0,scanner,true).succeeded()&&target.charging.setItemStackForSlot((short)0,gun,true).succeeded(),"Native docks accept powered singleton gadgets");
                require(!source.charging.setItemStackForSlot((short)0,new ItemStack("Ingredient_Charcoal",1),true).succeeded(),"Fuel cannot replace the inserted scanner in the burner dock");
                require(!source.input.setItemStackForSlot((short)0,gun,true).succeeded(),"Burner fuel routing rejects a gadget");
                GadgetDockDisplays.prepare(scanner).get(10,java.util.concurrent.TimeUnit.SECONDS);GadgetDockDisplays.prepare(gun).get(10,java.util.concurrent.TimeUnit.SECONDS);
                burner.energy=100;burner.fuelTicks=4;machines.tick(world,.05);
                int sourceCharge=GadgetEnergy.charge(source.charging.getItemStack((short)0)),targetCharge=GadgetEnergy.charge(target.charging.getItemStack((short)0));
                require(sourceCharge==10&&targetCharge==40,"Burner dock takes its bounded allowance before the charging station's faster transfer");
                require(burner.energy+charger.energy+sourceCharge+targetCharge==100+machines.config.burnerGeneration,"Generator, native docks and conduit network conserve all initial and generated RE");
                require(charger.active&&"Working".equals(world.getBlockType(6,240,10).getCurrentInteractionState()),"Charging work owns the native Working animation and audio state");
                var ports=factory.ports(world,targetPos);require(ports.size()==1&&ports.getFirst().section().equals("charging")&&ports.getFirst().acceptsInsert().test(scanner)&&!ports.getFirst().acceptsExtract().test(gun),"Charger exposes only its depleted-in, full-out tube dock");
                require(ports.getFirst().acceptsExtract().test(GadgetEnergy.withCharge(gun,GadgetEnergy.capacity(gun))),"Fully charged gadgets become extractable through the same native port");
                require(factory.ports(world,sourcePos).stream().noneMatch(p->p.inventory()==source.charging),"Burner gadget dock remains manual-only");
                NativeGadgetDockDisplayVerification.verify(world,factory,burner,source,charger,target);
                com.hexvane.strangematter.ui.NativeChargingDockInventoryVerification.verify(world,machines,factory,burner);
                charger.enabled=false;int before=charger.energy;machines.tick(world,.05);
                require(charger.energy==before&&GadgetEnergy.charge(target.charging.getItemStack((short)0))==40&&!charger.active,"Disabled station cannot receive or spend network energy");
                require(machines.hasContents(world,charger),"Inserted gadget prevents an ordinary destructive machine break");
                var removed=factory.remove(world,charger,target);require(removed.size()==1&&GadgetEnergy.charge(removed.getFirst())==40&&factory.remove(world,charger,target).isEmpty(),"Environmental recovery transfers the exact dock stack only once");
                require(factory.dockDisplays.displayed(charger)==null,"Machine recovery immediately removes its presentation entity");
                burnerFuel(world,machines,factory,burner,source,conduitPos);
                machines.removed(world,targetPos);machines.removed(world,sourcePos);machines.removed(world,conduitPos);
                System.out.println("NATIVE_CHARGING_WORLD_VERIFICATION_PASSED: real docks, fuel filters, shared generator/conduit accounting, native Working state, full-only tubes, disabled behavior and once-only occupied recovery.");
            }finally{for(var cell:cells)WorldAccess.set(chunk,cell.x,cell.y,cell.z,cell.id,com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset(cell.id),cell.rotation,cell.filler,NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);}
        }
    }
    private static void burnerFuel(World world,MachineService machines,FactoryService factory,com.hexvane.strangematter.machine.MachineState burner,FactoryComponent c,Vector3i conduit){
        WorldAccess.set(WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(conduit.x,conduit.z)),conduit.x,conduit.y,conduit.z,"Empty");machines.removed(world,conduit);
        c.charging.setItemStackForSlot((short)0,null,false);var fuel=new ItemStack("Ingredient_Charcoal",3);c.input.setItemStackForSlot((short)0,fuel,false);
        burner.energy=0;burner.fuelTicks=0;burner.queuedFuelTicks=0;burner.fuelQueue.clear();factory.changed(world,burner);
        int ticks=com.hexvane.strangematter.machine.FurnaceFuel.ticks(fuel);
        machines.tick(world,.05);require(c.input.getItemStack((short)0).getQuantity()==2&&burner.fuelTicks==ticks-1&&burner.queuedFuelTicks==0,"One fuel item ignites and the rest stay removable in the native fuel slot");
        machines.tick(world,.05);require(c.input.getItemStack((short)0).getQuantity()==2&&burner.fuelTicks==ticks-2,"Active burning never consumes additional fuel items");
        int remaining=burner.fuelTicks;burner.energy=machines.config.burnerCapacity;machines.tick(world,.05);
        require(burner.fuelTicks==remaining&&!burner.active&&c.input.getItemStack((short)0).getQuantity()==2,"A full buffer pauses the current burn without touching fuel inventory");
        burner.energy=machines.config.burnerCapacity-machines.config.burnerGeneration+1;machines.tick(world,.05);
        require(burner.fuelTicks==remaining&&!burner.active,"An incomplete generation quantum waits without truncating fuel energy");
        burner.energy=machines.config.burnerCapacity-machines.config.burnerGeneration;machines.tick(world,.05);
        require(burner.energy==machines.config.burnerCapacity&&burner.fuelTicks==remaining-1,"Exactly one quantum resumes without wasting any generated RE");
        burner.energy=0;burner.fuelTicks=1;machines.tick(world,.05);require(c.input.getItemStack((short)0).getQuantity()==2&&burner.fuelTicks==0,"Finishing the current item does not preload another");
        burner.enabled=false;machines.tick(world,.05);require(c.input.getItemStack((short)0).getQuantity()==2&&burner.fuelTicks==0,"Disabled empty burner does not ignite input");burner.enabled=true;
        burner.queuedFuelTicks=17;machines.tick(world,.05);require(burner.fuelTicks==16&&burner.queuedFuelTicks==0&&c.input.getItemStack((short)0).getQuantity()==2,"Legacy scalar-only saved fuel burns before touching new fuel");
        burner.fuelTicks=0;burner.queuedFuelTicks=23;burner.fuelQueue.add(com.hexvane.strangematter.machine.MachineState.FuelCharge.from(fuel.withQuantity(1),23));machines.tick(world,.05);
        require(burner.fuelTicks==22&&burner.queuedFuelTicks==0&&burner.fuelQueue.isEmpty()&&c.input.getItemStack((short)0).getQuantity()==2,"Legacy item queues remain valid and burn ahead of native input");
        burner.fuelTicks=0;machines.tick(world,.05);require(c.input.getItemStack((short)0).getQuantity()==1&&burner.fuelTicks==ticks-1,"The next physical fuel item ignites only after all saved fuel is exhausted");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
