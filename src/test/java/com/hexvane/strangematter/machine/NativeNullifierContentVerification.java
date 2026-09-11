package com.hexvane.strangematter.machine;

import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hexvane.strangematter.research.ResearchService;
import com.hexvane.strangematter.research.ResearchTeaching;
import com.hexvane.strangematter.util.InventoryOps;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.nio.file.Files;
import java.util.*;
import static com.hypixel.hytale.server.core.universe.world.SetBlockSettings.*;

/** Real native items, inventory and forge lifecycle; does not simulate anomaly suppression. */
public final class NativeNullifierContentVerification {
    public static void verify(World world,MachineService existing)throws Exception {
        var directory=Files.createTempDirectory(existing.dataDirectory(),"nullifier-content-");
        var position=new Vector3i(28,230,4);
        var chunk=world.getChunkIfInMemory(com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(position.x,position.z));
        require(chunk!=null,"Acquisition fixture uses an already loaded column");
        int[][] saved=new int[3][3];
        for(int y=0;y<saved.length;y++)saved[y]=new int[]{chunk.getBlock(position.x,position.y+y,position.z),chunk.getRotationIndex(position.x,position.y+y,position.z),chunk.getFiller(position.x,position.y+y,position.z)};
        try(var research=new ResearchService(directory);
            var machines=new MachineService(directory,existing.config,research,existing.anomalies);
            var player=NativePlayerFixture.create(world,"Nullifier recipe",new Vector3d(27.5,230,4.5))){
            var recipe=machines.recipes.stream().filter(r->r.id.equals("anomaly_nullifier")).findFirst().orElseThrow();
            require(recipe.output.equals("SM_Anomaly_Nullifier")&&recipe.quantity==1&&recipe.totalCost().size()==8,
                    "One obtainable nullifier has eight visible material rows");
            require(research.nodes().size()==26&&"rift_stabilizer".equals(research.requiredResearchForItem(recipe.output)),
                    "Existing Rift Stabilizer research gates the new device without another tree node");
            require(ResearchTeaching.pages(research.node("rift_stabilizer")).stream().anyMatch(p->"anomaly_nullifier".equals(p.recipe())&&p.content().contains("12 blocks")),
                    "Unlocked field guide exposes the real nullifier recipe and range");
            var item=new ItemStack(recipe.output,1).getItem();
            require(item!=null&&item.getMaxStack()==25,"New output resolves through the native loaded Item registry");
            var forge=BlockType.getAssetMap().getAsset("SM_Reality_Forge");
            chunk.setBlock(position.x,position.y,position.z,BlockType.getAssetMap().getIndex(forge.getId()),forge,0,0,
                    NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);
            var state=machines.register(world,position,forge.getId());
            for(var cost:recipe.totalCost().entrySet())require(InventoryOps.give(player.inventory(),new ItemStack(cost.getKey(),cost.getValue())),"Every native ingredient fits");
            var ready=machines.readiness(player.owner().getUuid(),player.player(),recipe);
            require(!ready.researched()&&ready.materials().stream().allMatch(m->m.missing()==0),"Complete material inventory cannot bypass missing research");
            machines.craft(player.owner(),player.player(),state,recipe.id);
            require(state.recipe.isEmpty()&&state.reservedInputs.isEmpty(),"Locked craft creates no reservation");
            for(var cost:recipe.totalCost().entrySet())require(InventoryOps.count(player.inventory(),cost.getKey())==cost.getValue(),"Rejected craft preserves exact materials");
            research.unlock(player.owner().getUuid(),"rift_stabilizer",true);
            try(var restored=new ResearchService(directory)){
                require(restored.hasUnlocked(player.owner().getUuid(),restored.requiredResearchForItem(recipe.output)),"An existing saved Rift Stabilizer completion unlocks the new recipe on reload");
            }
            require(machines.readiness(player.owner().getUuid(),player.player(),recipe).ready(),"Research unlock makes the same material inventory ready");
            machines.craft(player.owner(),player.player(),state,recipe.id);
            require(state.recipe.equals(recipe.id)&&state.reservedInputs.size()==8,"Real forge reserves every component and shard type");
            for(var cost:recipe.totalCost().entrySet())require(InventoryOps.count(player.inventory(),cost.getKey())==0,"Accepted craft consumes each material exactly once");
            for(int i=0;i<existing.config.forgeCraftTicks+1;i++)machines.tick(world,.05);
            require(state.recipe.isEmpty()&&state.reservedInputs.isEmpty()&&state.output.equals(recipe.output)&&state.outputQuantity==1,"Normal forge duration produces exactly one native nullifier");
            machines.collect(player.player(),state);
            require(state.outputQuantity==0&&InventoryOps.count(player.inventory(),recipe.output)==1,"Player can collect the crafted device");
            machines.collect(player.player(),state);
            require(InventoryOps.count(player.inventory(),recipe.output)==1,"Repeated collection cannot duplicate the output");
            machines.removed(world,position);
        }finally{
            for(int y=0;y<saved.length;y++){
                int[] old=saved[y];chunk.setBlock(position.x,position.y+y,position.z,old[0],BlockType.getAssetMap().getAsset(old[0]),old[1],old[2],
                        NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);
            }
        }
        System.out.println("NATIVE_NULLIFIER_CONTENT_VERIFICATION_PASSED: actual native item, eight visible costs, research rejection and saved unlock, all six shards, forge reservation, production and exact collection.");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
