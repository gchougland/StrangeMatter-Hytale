package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.protocol.ParticleRotationInfluence;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSpawner;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;
import java.util.List;
import java.util.UUID;

/** Real armor swaps must not leave cached protection behind. */
public final class NativeRiftHatVerification {
    public static void verify(World world)throws Exception{
        var effects=new HytaleAnomalyEffects();
        try(var player=NativePlayerFixture.create(world,"RiftHatVerification",new Vector3d(26,35,26))){
            // Model a ready player after native spawn protection has elapsed.
            player.player().handleClientReady(false);
            player.player().setLastSpawnTimeNanos(System.nanoTime()-java.util.concurrent.TimeUnit.SECONDS.toNanos(30));
            require(!player.player().hasSpawnProtection(),"Native ready player is outside spawn protection");
            var store=player.store();var ref=player.ref();
            var field=new AnomalyRecord(UUID.randomUUID(),AnomalyType.ENERGETIC_RIFT,world.getName(),new Vector3d(26,35,25),true);
            var stats=store.getComponent(ref,EntityStatMap.getComponentType());int health=DefaultEntityStatTypes.getHealth();
            require(stats!=null&&stats.get(health)!=null,"Native player health initialized");
            short head=(short)ItemArmorSlot.Head.ordinal();var hat=new ItemStack("SM_Tinfoil_Hat",1);
            var armor=store.getComponent(ref,InventoryComponent.Armor.getComponentType()).getInventory();
            require(!effects.protectedBy(world,ref,"SM_Tinfoil_Hat"),"Bare head has no hat protection");
            require(armor.setItemStackForSlot(head,hat,false).succeeded(),"Hat equips in native head slot");
            float before=stats.get(health).get();
            require(effects.protectedBy(world,ref,"SM_Tinfoil_Hat")&&!effects.zapTarget(world,field,ref)&&stats.get(health).get()==before,"Equipped hat blocks rift damage");
            require(armor.setItemStackForSlot(head,ItemStack.EMPTY,false).succeeded(),"Hat removed from native head slot");
            player.hotbar().setItemStackForSlot((short)0,hat,false);
            require(!effects.protectedBy(world,ref,"SM_Tinfoil_Hat")&&effects.zapTarget(world,field,ref),"A removed hat carried in inventory no longer protects");
            require(Math.abs(stats.get(health).get()-(before-5))<.001,"Rift damage resumes after normal unequip: "+before+" -> "+stats.get(health).get());
            armor.setItemStackForSlot(head,hat,false);
            require(effects.protectedBy(world,ref,"SM_Tinfoil_Hat"),"Re-equipping restores protection");
            store.putComponent(ref,InventoryComponent.Armor.getComponentType(),new InventoryComponent.Armor(InventoryComponent.DEFAULT_ARMOR_CAPACITY));
            require(hat.equals(armor.getItemStack(head)),"Old armor snapshot still contains the hat, reproducing stale-reference hazard");
            require(!effects.protectedBy(world,ref,"SM_Tinfoil_Hat")&&effects.zapTarget(world,field,ref),"Replacing armor cannot leave phantom protection");
            require(Math.abs(stats.get(health).get()-(before-10))<.001,"Native player takes damage after armor component replacement");
        }
        for(String id:List.of("SM_Rift_Arc_Trace","SM_Rift_Hit_Lightning")){
            var spawner=ParticleSpawner.getAssetMap().getAsset(id);require(spawner!=null,"Native zap spawner loaded");
            var rotation=spawner.getParticle().getInitialAnimationFrame().toPacket().rotation;
            require(spawner.getParticleRotationInfluence()==ParticleRotationInfluence.Billboard&&rotation!=null&&rotation.z.min==90&&rotation.z.max==90,"Billboard-compatible90-degree Z roll reaches native particle packet for "+id);
        }
        System.out.println("NATIVE_RIFT_HAT_VERIFICATION_PASSED: actual player equip/unequip/re-equip/component-replacement health deltas and horizontal zap particle roll.");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
