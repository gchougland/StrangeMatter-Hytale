package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.StrangeMatterConfig;
import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hexvane.strangematter.machine.MachineService;
import com.hexvane.strangematter.research.ResearchService;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Full scheduled field exposure: actual spatial index, player damage pipeline and enabled machine hook. */
public final class NativeRiftExposureVerification {
    public static void verify(World world)throws Exception {
        var directory=Files.createTempDirectory("sm-native-rift-exposure-");
        var service=new AnomalyService(directory);service.naturalGeneration=false;
        var fixture=NativePlayerFixture.create(world,"RiftExposureVerification",new Vector3d(20,45,20));
        var stabilizerPosition=new Vector3i(23,45,21);
        try(var research=new ResearchService(directory);
            var machines=new MachineService(directory,new StrangeMatterConfig(),research,service)) {
            service.setGroundingHook(machines::grounded);
            var discharges=new AtomicInteger();service.setDischargeHook((w,p)->discharges.incrementAndGet());
            var store=fixture.store();var ref=fixture.ref();
            fixture.player().handleClientReady(false);
            fixture.player().setLastSpawnTimeNanos(System.nanoTime()-TimeUnit.SECONDS.toNanos(30));
            require(fixture.player().getGameMode()==GameMode.Adventure&&!fixture.player().hasSpawnProtection(),"Actual ready Adventure player is outside native spawn protection");
            require(world.getPlayerRefs().contains(fixture.owner()),"Native player lifecycle populates the service's nearby-player activation query");
            require(world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(20,21))!=null,"Field activation chunk is loaded");
            // Update the real PlayerSpatialSystem/KD tree, not a fabricated target list.
            store.tick(.05f);
            var record=service.spawn(AnomalyType.ENERGETIC_RIFT,world,new Vector3d(20,46,21),true);
            require(new HytaleAnomalyEffects().entities(world,record).contains(ref),"Native sphere query finds the real player");
            float baseline=health(fixture);long sparks=sparks(fixture);
            service.tick(world,.05);
            delta(fixture,baseline,5,"First scheduled ungrounded exposure damages a bare-headed Adventure player");
            require(sparks(fixture)>sparks,"Scheduled exposure sends real targeted arc/hit packets");
            baseline=health(fixture);advance(service,world,1);
            delta(fixture,baseline,0,"Field cooldown prevents a second early hit");
            advance(service,world,1.1);delta(fixture,baseline,5,"Scheduled exposure repeats after the original two-second cooldown");

            Player.setGameMode(ref,GameMode.Creative,store);
            baseline=health(fixture);sparks=sparks(fixture);advance(service,world,2.1);
            delta(fixture,baseline,0,"Creative receives no damage through the complete field service");
            require(sparks(fixture)>sparks,"Creative contact has visible feedback despite immunity");
            Player.setGameMode(ref,GameMode.Adventure,store);
            var armor=store.getComponent(ref,InventoryComponent.Armor.getComponentType()).getInventory();
            short head=(short)ItemArmorSlot.Head.ordinal();
            armor.setItemStackForSlot(head,new ItemStack("SM_Tinfoil_Hat",1),false);
            baseline=health(fixture);sparks=sparks(fixture);advance(service,world,2.1);
            delta(fixture,baseline,0,"A currently equipped hat still protects through scheduled exposure");
            require(sparks(fixture)>sparks,"Hat protection retains the original visual contact");
            armor.setItemStackForSlot(head,ItemStack.EMPTY,false);

            world.setBlock(stabilizerPosition.x,stabilizerPosition.y,stabilizerPosition.z,"SM_Rift_Stabilizer");
            var machine=machines.register(world,stabilizerPosition,"SM_Rift_Stabilizer");
            require(machine!=null&&machine.enabled&&machines.grounded(world,record.position(),8),"Real enabled stabilizer resolves the production grounding hook");
            baseline=health(fixture);sparks=sparks(fixture);advance(service,world,2.1);
            delta(fixture,baseline,0,"Grounded Adventure exposure cannot damage a nearby player");
            require(sparks(fixture)==sparks&&discharges.get()==1,"Grounded rift redirects its discharge instead of targeting entities");
            int previousDischarges=discharges.get();advance(service,world,8.5);
            require(discharges.get()==previousDischarges,"Grounded discharge cannot repeat before its ten-second cooldown");
            advance(service,world,2);require(discharges.get()==previousDischarges+1,"Grounded discharge repeats after ten seconds");

            machines.toggle(machine);require(!machines.grounded(world,record.position(),8),"Disabled stabilizer no longer grounds");
            baseline=health(fixture);advance(service,world,.35);
            delta(fixture,baseline,5,"Turning off the stabilizer restores scheduled entity damage");
            machines.toggle(machine);advance(service,world,2.1);
            // A stale enabled registry entry must also stop grounding when its physical block is removed.
            world.setBlock(stabilizerPosition.x,stabilizerPosition.y,stabilizerPosition.z,"Empty");
            require(!machines.grounded(world,record.position(),8),"Removed physical stabilizer cannot ground through a stale saved registration");
            baseline=health(fixture);advance(service,world,.35);
            delta(fixture,baseline,5,"Removing the stabilizer restores damage through the normal field tick");
            machines.removed(world,stabilizerPosition);

            store.putComponent(ref,Invulnerable.getComponentType(),Invulnerable.INSTANCE);
            baseline=health(fixture);advance(service,world,2.1);
            delta(fixture,baseline,0,"Native Invulnerable remains authoritative");
            store.tryRemoveComponent(ref,Invulnerable.getComponentType());
            fixture.player().setLastSpawnTimeNanos(System.nanoTime());
            require(fixture.player().hasSpawnProtection(),"Fixture activates genuine native spawn protection");
            baseline=health(fixture);advance(service,world,2.1);
            delta(fixture,baseline,0,"Native spawn protection cancels scheduled damage");
            fixture.player().setLastSpawnTimeNanos(System.nanoTime()-TimeUnit.SECONDS.toNanos(30));

            // Save and reconstruct the real registry: timers must restart, not persist a permanently inert rift.
            service.stopWorld(world);
            var restored=new AnomalyService(directory);restored.naturalGeneration=false;restored.setGroundingHook(machines::grounded);
            try {
                var loaded=restored.get(record.id).orElseThrow();
                require(loaded.active()&&loaded.world.equals(world.getName()),"Persisted enabled/uncontained identity reactivates in its owning world");
                baseline=health(fixture);restored.tick(world,.05);
                delta(fixture,baseline,5,"Reloaded identity still damages through activation, spatial query and native pipeline");
                store.getComponent(ref,TransformComponent.getComponentType()).setPosition(new Vector3d(27,45,20));store.tick(.05f);
                baseline=health(fixture);sparks=sparks(fixture);advance(restored,world,2.1);
                delta(fixture,baseline,0,"Player outside the six-block sphere is not zapped");
                require(sparks(fixture)==sparks,"Out-of-range player receives no targeted contact");
                var capsule=restored.capture(loaded.id).orElseThrow();
                store.getComponent(ref,TransformComponent.getComponentType()).setPosition(new Vector3d(20,45,20));store.tick(.05f);
                baseline=health(fixture);advance(restored,world,2.1);delta(fixture,baseline,0,"Contained identity is inactive");
                require(restored.release(capsule.token(),world,record.position()).isPresent(),"Contained identity releases with its valid nonce");
                advance(restored,world,2.1);delta(fixture,baseline,5,"Capsule-released field regains scheduled exposure");
            } finally {restored.stopWorld(world);}
        } finally {
            service.stopWorld(world);
            world.setBlock(stabilizerPosition.x,stabilizerPosition.y,stabilizerPosition.z,"Empty");
            // Native game-mode changes enqueue world-map packets. Finish those before detaching.
            world.execute(fixture::close);
        }
        System.out.println("NATIVE_RIFT_EXPOSURE_VERIFICATION_PASSED: full AnomalyService.tick activation/spatial query, repeated Adventure damage, Creative/hat feedback without damage, actual enabled/off/removed stabilizer, ten-second discharge, native invulnerability/spawn protection, save/reload, range and capture/release.");
    }
    private static void advance(AnomalyService service,World world,double seconds){for(int i=0;i<(int)Math.ceil(seconds/.05);i++)service.tick(world,.05);}
    private static float health(NativePlayerFixture player){return player.store().getComponent(player.ref(),EntityStatMap.getComponentType()).get(DefaultEntityStatTypes.getHealth()).get();}
    private static long sparks(NativePlayerFixture player){return player.packets().ofType(SpawnParticleSystem.class).stream().filter(p->"SM_Rift_Hit".equals(p.particleSystemId)).count();}
    private static void delta(NativePlayerFixture player,float before,float expected,String message){float actual=before-health(player);require(Math.abs(actual-expected)<.001,message+": health delta="+actual+", expected="+expected);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
