package com.hexvane.strangematter.machine;

import com.hexvane.strangematter.util.WorldAccess;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import java.util.Set;

/** Native block states own animation and ambient-loop lifetime; no periodic sound/UI packets. */
public final class MachineWorkEffects {
    public static final String WORKING="Working", CONDENSER_HUM="SM_Condenser_Working_Hum";
    public static final Set<String> MACHINES=Set.of("SM_Resonance_Condenser","SM_Resonant_Burner","SM_Reality_Forge","SM_Rift_Stabilizer","SM_Anomaly_Nullifier","SM_Resonant_Separator","SM_Flux_Furnace","SM_Pattern_Assembler");
    private MachineWorkEffects(){}
    public static boolean working(MachineState machine){
        if(!machine.enabled||!machine.active||!MACHINES.contains(machine.id))return false;
        if(machine.id.equals("SM_Reality_Forge"))return !machine.recipe.isEmpty();
        if(machine.id.equals("SM_Resonance_Condenser")&&machine.outputQuantity>0)
            return machine.outputQuantity<new ItemStack(machine.output,1).getItem().getMaxStack();
        return true;
    }
    public static boolean sync(World world,MachineState machine){return setWorking(world,machine,working(machine));}
    public static boolean stop(World world,MachineState machine){return setWorking(world,machine,false);}
    private static boolean setWorking(World world,MachineState machine,boolean active){
        if(!MACHINES.contains(machine.id)||!world.getName().equals(machine.world))return false;
        if(WorldAccess.inMemory(world,ChunkUtil.indexChunkFromBlock(machine.x,machine.z))==null)return false;
        var section=world.getChunkStore().getChunkSectionReferenceAtBlock(machine.x,machine.y,machine.z);
        if(section==null||!section.isValid())return false;
        var type=world.getBlockType(machine.x,machine.y,machine.z);
        if(!machine.id.equals(MachineService.baseId(type)))return false;
        String tier=(machine.id.equals("SM_Flux_Furnace")||machine.id.equals("SM_Pattern_Assembler"))&&machine.factoryTier>1?"Tier"+machine.factoryTier:"";
        String state=active?tier+WORKING:tier.isEmpty()?StateData.NULL_STATE_ID:tier;
        var desired=type.getBlockForState(state);
        if(desired==null||desired.getId().equals(type.getId()))return false;
        // The native furnace path preserves rotation, replaces tall fillers and marks section
        // replication dirty. Clients stop the previous block's ambient loop when its state changes.
        BlockOperations.setBlockInteractionState(world.getChunkStore(),section,machine.x,machine.y,machine.z,type,state,false);
        return desired.getId().equals(world.getBlockType(machine.x,machine.y,machine.z).getId());
    }
}
