package com.hexvane.strangematter.equipment;

import com.google.gson.Gson;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hexvane.strangematter.ui.gadget.GadgetHudService;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Native collision/hostility/damage and durable block ownership, including crash boundaries. */
public final class NativeAdvancedGadgetsVerification {
    public static void verify(World world)throws Exception{
        verifyGeometry();var store=world.getEntityStore().getStore();
        var directory=Files.createTempDirectory("sm-gravitic-transport-");var saved=new CompletableFuture<Void>();
        var journal=new GraviticBlockJournal(directory,w->saved);var origin=new Vector3i(23,112,23);
        for(int x=18;x<=29;x++)for(int z=18;z<=29;z++)for(int y=111;y<=119;y++)world.setBlock(x,y,z,"Empty");
        world.setBlock(origin.x,origin.y,origin.z,"Rock_Stone");
        require(GraviticBlockJournal.eligible(world,origin),"Ordinary native stone is transportable");
        var id=journal.begin(world,UUID.randomUUID(),origin);require(id!=null,"Native block removal creates durable receipt");
        require(world.getBlock(origin.x,origin.y,origin.z)==0&&!journal.ready(id)&&journal.reserved(world,origin),"Removed source remains reserved until its checkpoint succeeds");
        var visual=journal.visual(world,id);require(visual!=null&&visual.isValid()&&store.getComponent(visual,BlockEntity.getComponentType())!=null,"Transport uses a native moving block representation");
        require(store.getComponent(visual,com.hypixel.hytale.server.core.universe.world.storage.EntityStore.REGISTRY.getNonSerializedComponentType())!=null,"Presentation cannot persist independently and duplicate terrain");
        var step=new Vector3d(4,0,0);world.setBlock(25,112,23,"Rock_Stone");
        double fraction=AdvancedGadgetTargeting.sweep(store,GraviticBlockJournal.BOX,new Vector3d(23.5,112.5,23.5),step);
        require(fraction>0&&fraction<.26,"Full native swept block collider stops before a wall");world.setBlock(25,112,23,"Empty");
        saved.complete(null);journal.tick(world);require(journal.ready(id)&&!journal.reserved(world,origin),"Source checkpoint releases source and enables relocation");
        // Recovery without a live owner may undo only its original authorized removal.
        journal.detach(id);journal.tick(world);require(world.getBlockType(origin.x,origin.y,origin.z).getId().equals("Rock_Stone"),"Unattached block recovers its original source");journal.tick(world);require(journal.receipt(id)==null,"Placement checkpoint retires ownership");store.removeEntity(visual,RemoveReason.REMOVE);
        var persisted=Files.readString(directory.resolve("gravitic-blocks.json"));require(!persisted.contains(id.toString()),"Retirement reaches the journal on disk");

        // Acknowledged held block and occupied source: never overwrite construction or duplicate elsewhere.
        var crash=new GraviticBlockJournal.Receipt(UUID.randomUUID(),UUID.randomUUID(),world.getName(),"Rock_Stone",0,new GraviticBlockJournal.Cell(23,112,23),null,GraviticBlockJournal.Phase.HELD);
        Files.writeString(directory.resolve("gravitic-blocks.json"),new Gson().toJson(new GraviticBlockJournal.Saved(1,List.of(crash))));world.setBlock(23,112,23,"Wood_Hardwood_Planks");
        var restored=new GraviticBlockJournal(directory,w->CompletableFuture.completedFuture(null));restored.tick(world);
        require(restored.receipt(crash.id())!=null&&"Wood_Hardwood_Planks".equals(world.getBlockType(23,112,23).getId()),"Restart retains receipt and occupied return cell");
        world.setBlock(23,112,23,"Empty");restored.tick(world);restored.tick(world);require(restored.receipt(crash.id())==null&&"Rock_Stone".equals(world.getBlockType(23,112,23).getId()),"Restart restores exactly once after return becomes empty");

        try(var player=NativePlayerFixture.create(world,"ArcVerification",new Vector3d(20.5,116,20.5))){
            var first=NPCPlugin.get().spawnNPC(store,"Zombie",null,new Vector3d(20.5,116,24),new Rotation3f()).first();
            var second=NPCPlugin.get().spawnNPC(store,"Zombie",null,new Vector3d(23,116,24),new Rotation3f()).first();
            var neutral=NPCPlugin.get().spawnNPC(store,"Cow",null,new Vector3d(24.5,116,26),new Rotation3f()).first();
            try{
                store.tick(.05f); // Publish newly spawned NPC bodies to the native spatial indexes.
                require(ArcProjector.eligible(player.ref(),first,store,true),"Native hostile attitude enables an automatic chain");
                require(!ArcProjector.eligible(player.ref(),neutral,store,true),"Neutral native animals are excluded from incidental chains");
                require(!ArcProjector.eligible(first,player.ref(),store,true),"Automatic arc never selects players");
                var center=AdvancedGadgetTargeting.center(first,store);var eye=new Vector3d(center).add(0,0,-3.5);var aim=new AdvancedGadgetTargeting.Aim(eye,new Vector3d(0,0,1),10,null);
                var chain=ArcProjector.chain(player.ref(),store,aim);require(chain.size()==2&&chain.getFirst().equals(first)&&chain.get(1).equals(second),"Actual chain targets nearest aimed hostile then visible hostile, skips neutral: found="+chain.size()+", first="+chain.contains(first)+", second="+chain.contains(second)+", neutral="+chain.contains(neutral));
                int healthIndex=DefaultEntityStatTypes.getHealth();var health=store.getComponent(first,EntityStatMap.getComponentType());float before=health.get(healthIndex).get();
                ArcProjector.fire(world,player.ref(),aim,5);require(health.get(healthIndex).get()<before,"Arc pulse executes native player-attributed damage");
                for(int y=115;y<=119;y++)world.setBlock(22,y,24,"Rock_Stone");
                require(!AdvancedGadgetTargeting.visible(store,center,AdvancedGadgetTargeting.center(second,store)),"Hop line of sight respects native solid terrain");
                chain=ArcProjector.chain(player.ref(),store,aim);require(chain.size()==1,"Obstructed enemy is excluded from chain");
                // Real NPC identity is held with a finite owned effect and restored on a free release.
                var holdHud=new GadgetHudService();var gravity=new GraviticManipulator(Files.createTempDirectory("sm-native-portable-hold-"),holdHud);
                try{
                    var playerEye=new Vector3d(store.getComponent(player.ref(),TransformComponent.getComponentType()).getPosition()).add(0,ModelComponent.getEyeHeight(player.ref(),store),0);
                    store.getComponent(player.ref(),HeadRotation.getComponentType()).setRotation(Rotation3f.lookAt(playerEye,AdvancedGadgetTargeting.center(first,store)));
                    var hotbar=store.getComponent(player.ref(),InventoryComponent.Hotbar.getComponentType());hotbar.setActiveSlot((byte)0,player.ref(),store);
                    var tool=new ItemStack("SM_Gravitic_Manipulator",1);player.hotbar().setItemStackForSlot((short)0,tool,false);
                    gravity.interact(player.owner(),store,player.hotbar(),(short)0,tool,"gravity_grab");
                    int holdIndex=EntityEffect.getAssetMap().getIndex("SM_Gravitic_Hold");var effects=store.getComponent(first,EffectControllerComponent.getComponentType());
                    require(effects.getActiveEffects().containsKey(holdIndex),"Actual NPC receives portable stasis effect");
                    var victimHealth=store.getComponent(neutral,EntityStatMap.getComponentType());float beforeHeldAttack=victimHealth.get(healthIndex).get();
                    int physical=com.hypixel.hytale.server.core.modules.entity.damage.DamageCause.getAssetMap().getIndex("Physical");
                    com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems.executeDamage(neutral,store,new com.hypixel.hytale.server.core.modules.entity.damage.Damage(new com.hypixel.hytale.server.core.modules.entity.damage.Damage.EntitySource(first),physical,1));
                    require(victimHealth.get(healthIndex).get()==beforeHeldAttack,"Native damage pipeline suppresses held creature attacks");
                    var beforeCarry=new Vector3d(store.getComponent(first,TransformComponent.getComponentType()).getPosition());
                    for(int tick=0;tick<8;tick++){
                        gravity.tick(world,.05);var heldGoal=new Vector3d(store.getComponent(first,TransformComponent.getComponentType()).getPosition());store.tick(.05f);
                        var afterNative=store.getComponent(first,TransformComponent.getComponentType()).getPosition();
                        require(afterNative.distance(heldGoal)<.05,"Native NPC movement and gravity cannot move away from the held goal: "+afterNative.distance(heldGoal));
                    }
                    require(first.isValid()&&store.getComponent(first,TransformComponent.getComponentType()).getPosition().distance(beforeCarry)<3,"Native held creature retains identity and remains within bounded carry motion");
                    var spent=player.hotbar().getItemStack((short)0);require(GadgetEnergy.charge(spent)<GadgetEnergy.capacity(spent)-GadgetEnergy.cost("gravity_grab"),"Sustained hold drains real gadget energy");
                    var depleted=GadgetEnergy.withCharge(spent,0);player.hotbar().setItemStackForSlot((short)0,depleted,false);gravity.interact(player.owner(),store,player.hotbar(),(short)0,depleted,"gravity_grab");
                    require(!effects.getActiveEffects().containsKey(holdIndex)&&first.isValid(),"Zero-energy release removes only owned hold and preserves original NPC");
                    require(store.getComponent(first,com.hypixel.hytale.server.core.entity.Frozen.getComponentType())==null,"Portable stasis never leaves a serialized Frozen flag");
                    beforeHeldAttack=victimHealth.get(healthIndex).get();
                    com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems.executeDamage(neutral,store,new com.hypixel.hytale.server.core.modules.entity.damage.Damage(new com.hypixel.hytale.server.core.modules.entity.damage.Damage.EntitySource(first),physical,1));
                    require(victimHealth.get(healthIndex).get()<beforeHeldAttack,"Releasing stasis immediately restores normal native attack damage");
                }finally{gravity.cleanup(world);holdHud.close();}

                var move=new GraviticBlockJournal(Files.createTempDirectory("sm-native-relocation-"),w->CompletableFuture.completedFuture(null));
                var moveId=move.begin(world,player.owner().getUuid(),origin);move.tick(world);
                require(moveId!=null&&move.ready(moveId)&&move.place(world,moveId,player.ref(),new Vector3d(26.5,112.5,26.5)),"Authorized native placement relocates acknowledged block");
                var destination=move.receipt(moveId).destination();require(move.reserved(world,destination.pos())&&world.getBlock(origin.x,origin.y,origin.z)==0,"Relocation reserves new destination and leaves original source empty");
                move.tick(world);require(move.receipt(moveId)==null&&"Rock_Stone".equals(world.getBlockType(destination.x(),destination.y(),destination.z()).getId()),"Relocated destination survives durable ownership retirement");
            }finally{if(first.isValid())store.removeEntity(first,RemoveReason.REMOVE);if(second.isValid())store.removeEntity(second,RemoveReason.REMOVE);if(neutral.isValid())store.removeEntity(neutral,RemoveReason.REMOVE);}
        }
        for(int x=18;x<=29;x++)for(int z=18;z<=29;z++)for(int y=111;y<=119;y++)world.setBlock(x,y,z,"Empty");
        verifyCreatureTransport(world);
        NativeGraviticChestVerification.verify(world);
        System.out.println("NATIVE_ADVANCED_GADGETS_VERIFICATION_PASSED: native block ownership/checkpoints/recovery, swept collision, hostile-only chains, wall rejection, attributed arc damage, grounded creature lift and matched creature/block throws.");
    }
    private static void verifyCreatureTransport(World world)throws Exception{
        var store=world.getEntityStore().getStore();var directory=Files.createTempDirectory("sm-native-creature-flight-");var hud=new GadgetHudService();
        var gravity=new GraviticManipulator(new GraviticBlockJournal(directory,w->CompletableFuture.completedFuture(null)),hud);
        for(int x=8;x<=28;x++)for(int z=8;z<=28;z++)for(int y=139;y<=150;y++)world.setBlock(x,y,z,y==139?"Rock_Stone":"Empty");
        try(var player=NativePlayerFixture.create(world,"CreatureFlightVerification",new Vector3d(12.5,140,12.5))){
            var cosmetics=com.hypixel.hytale.server.core.cosmetics.CosmeticsModule.get();
            var skin=cosmetics.generateRandomSkin(new Random(1729));cosmetics.validateSkin(skin);
            store.putComponent(player.ref(),ModelComponent.getComponentType(),new ModelComponent(cosmetics.createModel(skin)));
            var target=NPCPlugin.get().spawnNPC(store,"Zombie",null,new Vector3d(12.5,140,16.5),new Rotation3f()).first();
            var hotbar=store.getComponent(player.ref(),InventoryComponent.Hotbar.getComponentType());hotbar.setActiveSlot((byte)0,player.ref(),store);
            var eye=new Vector3d(store.getComponent(player.ref(),TransformComponent.getComponentType()).getPosition()).add(0,ModelComponent.getEyeHeight(player.ref(),store),0);
            var direction=new Vector3d(.6,.5,.62).normalize();var goal=new Vector3d(eye).fma(3,direction);
            Vector3d creatureDisplacement;
            try{
                for(int i=0;i<4;i++)store.tick(.05f);
                var transform=store.getComponent(target,TransformComponent.getComponentType());var box=store.getComponent(target,BoundingBox.getComponentType()).getBoundingBox();
                double groundedY=transform.getPosition().y;
                require(groundedY<140.2,"Creature starts grounded on a native collision floor: "+groundedY);
                require(AdvancedGadgetTargeting.sweep(store,box,transform.getPosition(),new Vector3d(0,1,0))>.99,"A native floor contact permits separating upward movement");
                store.getComponent(player.ref(),HeadRotation.getComponentType()).setRotation(Rotation3f.lookAt(eye,AdvancedGadgetTargeting.center(target,store)));
                var tool=new ItemStack(GraviticManipulator.ITEM,1);player.hotbar().setItemStackForSlot((short)0,tool,false);
                gravity.interact(player.owner(),store,player.hotbar(),(short)0,tool,"gravity_grab");
                int index=EntityEffect.getAssetMap().getIndex(GraviticManipulator.EFFECT);var effects=store.getComponent(target,EffectControllerComponent.getComponentType());
                require(effects.getActiveEffects().containsKey(index),"Grounded creature is acquired by the actual interaction");
                store.getComponent(player.ref(),HeadRotation.getComponentType()).setRotation(Rotation3f.lookAt(eye,goal));
                for(int i=0;i<20;i++){
                    gravity.tick(world,.05);var beforeNative=new Vector3d(transform.getPosition());store.tick(.05f);
                    require(transform.getPosition().distance(beforeNative)<.05,"Native ticks preserve the full airborne hold position: "+transform.getPosition().distance(beforeNative));
                }
                require(transform.getPosition().y>groundedY+1,"Looking upward lifts a grounded creature off the floor");
                require(AdvancedGadgetTargeting.center(target,store).distance(goal)<.08,"Creature follows the elevated aim point in all three axes");
                var actualDirection=AdvancedGadgetTargeting.aim(player.ref(),store,8).direction();
                // Native HeadRotation uses a 4096-entry sine/cosine lookup, including truncated
                // negative angle indexes; its direction is intentionally approximate.
                require(actualDirection.distance(direction)<.003,"Flight fixture aims in the requested upward diagonal: "+actualDirection+" vs "+direction);
                var start=new Vector3d(transform.getPosition());
                gravity.interact(player.owner(),store,player.hotbar(),(short)0,player.hotbar().getItemStack((short)0),"gravity_launch");
                require(store.getComponent(target,com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent.getComponentType())==null,"Creature launch cannot enter amplified native knockback");
                for(int i=0;i<7;i++){
                    var previous=new Vector3d(transform.getPosition());gravity.tick(world,.05);store.tick(.05f);
                    require(transform.getPosition().distance(previous)<1.1,"Creature launch speed stays within the block launch bound per native tick");
                }
                creatureDisplacement=new Vector3d(transform.getPosition()).sub(start);
                require(creatureDisplacement.x>3&&creatureDisplacement.y>1&&creatureDisplacement.z>3&&creatureDisplacement.length()<7.1,"Creature throw follows upward diagonal at a controlled range: "+creatureDisplacement);
                gravity.cleanup(world);
                require(!effects.getActiveEffects().containsKey(index)&&store.getComponent(target,com.hypixel.hytale.server.core.entity.Frozen.getComponentType())==null,"World cleanup restores flight without a persistent freeze or lease");
                var npc=store.getComponent(target,com.hypixel.hytale.server.npc.entities.NPCEntity.getComponentType());
                require(npc.getRole().getActiveMotionController().getCombinedExternalVelocityLength()<.01,"Release clears any forced-flight impulse before restoring native gravity");
                double releasedY=transform.getPosition().y;for(int i=0;i<8;i++)store.tick(.05f);
                require(transform.getPosition().y<releasedY-.2,"Released creature resumes native falling instead of remaining suspended");
            }finally{gravity.cleanup(world);if(target.isValid())store.removeEntity(target,RemoveReason.REMOVE);}

            // Exercise the working block path through identical inputs and compare actual displacement.
            var source=new Vector3i(12,142,16);world.setBlock(source.x,source.y,source.z,"Rock_Stone");
            store.getComponent(player.ref(),HeadRotation.getComponentType()).setRotation(Rotation3f.lookAt(eye,new Vector3d(12.5,142.5,16.5)));
            var tool=new ItemStack(GraviticManipulator.ITEM,1);player.hotbar().setItemStackForSlot((short)0,tool,false);
            gravity.interact(player.owner(),store,player.hotbar(),(short)0,tool,"gravity_grab");
            var saved=new Gson().fromJson(Files.readString(directory.resolve("gravitic-blocks.json")),GraviticBlockJournal.Saved.class);
            require(saved.receipts().size()==1,"Block comparison acquires exactly one journaled cube");
            var visual=world.getEntityStore().getRefFromUUID(saved.receipts().getFirst().id());require(visual!=null&&visual.isValid(),"Block comparison resolves the original moving presentation");
            store.getComponent(player.ref(),HeadRotation.getComponentType()).setRotation(Rotation3f.lookAt(eye,goal));
            for(int i=0;i<20;i++){gravity.tick(world,.05);store.tick(.05f);}
            var transform=store.getComponent(visual,TransformComponent.getComponentType());var start=new Vector3d(transform.getPosition());
            gravity.interact(player.owner(),store,player.hotbar(),(short)0,player.hotbar().getItemStack((short)0),"gravity_launch");
            for(int i=0;i<7;i++){gravity.tick(world,.05);store.tick(.05f);}
            require(visual.isValid(),"Block remains in unobstructed flight for the same observation period");
            var blockDisplacement=new Vector3d(transform.getPosition()).sub(start);
            require(blockDisplacement.distance(creatureDisplacement)<.08,"Actual creature and block throws have matching bounded trajectories: creature="+creatureDisplacement+", block="+blockDisplacement);
            gravity.cleanup(world);
            gravity.blocks.tick(world);
            require("Rock_Stone".equals(world.getBlockType(source.x,source.y,source.z).getId()),"Block comparison cleanup restores the transported cube exactly once");
            world.setBlock(source.x,source.y,source.z,"Empty"); // The restored cube would be directly above the next creature's head.
            verifyCreatureReleaseBoundaries(world,player,gravity,eye,goal);
            verifyDroppedItemCarry(world,player,gravity,eye,goal);
            verifyBirdCarry(world,player,gravity,eye,goal);
        }finally{
            gravity.cleanup(world);hud.close();
            for(int x=8;x<=28;x++)for(int z=8;z<=28;z++)for(int y=139;y<=150;y++)world.setBlock(x,y,z,"Empty");
        }
    }
    private static void verifyBirdCarry(World world,NativePlayerFixture player,GraviticManipulator gravity,Vector3d eye,Vector3d goal)throws Exception{
        var store=world.getEntityStore().getStore();var supportType=com.hypixel.hytale.server.npc.role.support.WorldSupport.getComponentType();
        // Observe the actual passive role's missing cache, without manufacturing a broken NPC.
        // These birds have no attitude-filter sensor, so normal native ticks never allocate it.
        var cacheField=com.hypixel.hytale.server.npc.role.support.WorldSupport.class.getDeclaredField("attitudeCache");cacheField.setAccessible(true);
        for(String role:List.of("Sparrow","Crow")){
            var target=NPCPlugin.get().spawnNPC(store,role,null,new Vector3d(12.5,141,16.5),new Rotation3f()).first();
            try{
                store.tick(.01f);var support=store.getComponent(target,supportType);
                require(support!=null&&cacheField.get(support)==null,"Natural "+role+" role reproduces an absent attitude cache after native initialization");
                var npc=store.getComponent(target,com.hypixel.hytale.server.npc.entities.NPCEntity.getComponentType());var motion=npc.getRole().getActiveMotionController();
                double nativeGravity=motion.getGravity();require(nativeGravity>0,"Bird fixture uses native flight gravity");
                if(role.equals("Crow")){
                    // Arc's first query must also be safe when gravity never initialized this NPC.
                    var attitude=store.getResource(com.hypixel.hytale.server.npc.blackboard.Blackboard.getResourceType())
                            .getView(com.hypixel.hytale.server.npc.blackboard.view.attitude.AttitudeView.class,target,store).getAttitude(target,npc.getRoleIndex(),player.ref(),store);
                    boolean directlyEligible=attitude!=com.hypixel.hytale.server.core.asset.type.attitude.Attitude.FRIENDLY&&attitude!=com.hypixel.hytale.server.core.asset.type.attitude.Attitude.REVERED&&attitude!=com.hypixel.hytale.server.core.asset.type.attitude.Attitude.IGNORE;
                    require(ArcProjector.eligible(player.ref(),target,store,false)==directlyEligible,"Arc safely uses the bird's real native relationship on its first query");
                    require(ArcProjector.eligible(player.ref(),target,store,true)==(attitude==com.hypixel.hytale.server.core.asset.type.attitude.Attitude.HOSTILE),"Arc chaining still follows actual hostile-only attitude rules");
                }
                var tool=new ItemStack(GraviticManipulator.ITEM,1);player.hotbar().setItemStackForSlot((short)0,tool,false);
                store.getComponent(player.ref(),HeadRotation.getComponentType()).setRotation(Rotation3f.lookAt(eye,AdvancedGadgetTargeting.center(target,store)));
                gravity.interact(player.owner(),store,player.hotbar(),(short)0,tool,"gravity_grab");
                int hold=EntityEffect.getAssetMap().getIndex(GraviticManipulator.EFFECT);var effects=store.getComponent(target,EffectControllerComponent.getComponentType());
                require(effects.getActiveEffects().containsKey(hold)&&cacheField.get(support)!=null,"Actual "+role+" pickup initializes the native query dependency and acquires stasis");
                store.getComponent(player.ref(),HeadRotation.getComponentType()).setRotation(Rotation3f.lookAt(eye,goal));
                var transform=store.getComponent(target,TransformComponent.getComponentType());
                for(int i=0;i<16;i++){gravity.tick(world,.05);var before=new Vector3d(transform.getPosition());store.tick(.05f);require(transform.getPosition().distance(before)<.05,"Native bird flight respects the held position: "+transform.getPosition().distance(before));require(motion.getGravity()==nativeGravity,"Every native steering pass restores the bird's exact configured gravity");}
                require(AdvancedGadgetTargeting.center(target,store).distance(goal)<.08,"The original "+role+" follows the aimed carry point");
                var interruptedPass=new GraviticFlightSuspension.Before();interruptedPass.tick(.05f,0,store);
                require(motion.getGravity()==0,"Owned bird lease scopes gravity suppression to steering");
                store.tick(.05f);require(motion.getGravity()==nativeGravity,"A subsequent native tick repairs an interrupted pass and restores its new pass");
                interruptedPass.tick(.05f,0,store); // Release also repairs a pending pass immediately.
                gravity.interact(player.owner(),store,player.hotbar(),(short)0,player.hotbar().getItemStack((short)0),"gravity_grab");
                require(target.isValid()&&!effects.getActiveEffects().containsKey(hold)&&npc.getRole().getActiveMotionController()==motion&&motion.getCombinedExternalVelocityLength()<.01&&motion.getGravity()==nativeGravity,"Bird release clears the lease and returns its original flight controller and gravity");
                require(store.getComponent(target,com.hypixel.hytale.server.core.entity.Frozen.getComponentType())==null,"Bird release leaves no persistent freeze flag");
                var released=new Vector3d(transform.getPosition());for(int i=0;i<12;i++)store.tick(.05f);
                require(transform.getPosition().isFinite()&&transform.getPosition().distance(released)>.05,"Released bird resumes native free flight");
                transform.setPosition(new Vector3d(12.5,141,16.5));store.tick(.01f);
                store.getComponent(player.ref(),HeadRotation.getComponentType()).setRotation(Rotation3f.lookAt(eye,AdvancedGadgetTargeting.center(target,store)));
                gravity.interact(player.owner(),store,player.hotbar(),(short)0,player.hotbar().getItemStack((short)0),"gravity_grab");
                require(effects.getActiveEffects().containsKey(hold),"Bird can be acquired again for missed-refresh expiry");
                var beforeExpiry=new Vector3d(transform.getPosition());for(int i=0;i<14;i++)store.tick(.05f);
                require(!effects.getActiveEffects().containsKey(hold)&&motion.getGravity()==nativeGravity&&motion.getCombinedExternalVelocityLength()<.01,"Missing gadget refresh expires both the bird effect and its forced steering mode");
                require(transform.getPosition().distance(beforeExpiry)>.05,"Expired suspension resumes native free flight without a gadget cleanup callback");
                gravity.interact(player.owner(),store,player.hotbar(),(short)0,player.hotbar().getItemStack((short)0),"gravity_grab");
                // Real spawn lineage overrides a role's default attitude. Initializing a cache
                // must not replace the native relationship providers with a guessed default.
                String lineage=UUID.randomUUID().toString();var lineageType=com.hypixel.hytale.server.spawning.SpawnLineage.getComponentType();
                store.putComponent(player.ref(),lineageType,new com.hypixel.hytale.server.spawning.SpawnLineage(lineage));
                store.putComponent(target,lineageType,new com.hypixel.hytale.server.spawning.SpawnLineage(lineage));support.tick(.11f);
                require(AdvancedGadgetTargeting.attitude(target,player.ref(),store)==com.hypixel.hytale.server.core.asset.type.attitude.Attitude.FRIENDLY,"Native shared lineage retains its friendly relationship override");
                require(!ArcProjector.eligible(player.ref(),target,store,false)&&!ArcProjector.eligible(player.ref(),target,store,true),"Friendly birds stay excluded from both arc selection modes");
                transform.setPosition(new Vector3d(12.5,141,16.5));store.tick(.01f);
                store.getComponent(player.ref(),HeadRotation.getComponentType()).setRotation(Rotation3f.lookAt(eye,AdvancedGadgetTargeting.center(target,store)));
                var beforeCharge=GadgetEnergy.charge(player.hotbar().getItemStack((short)0));
                gravity.interact(player.owner(),store,player.hotbar(),(short)0,player.hotbar().getItemStack((short)0),"gravity_grab");
                require(!effects.getActiveEffects().containsKey(hold)&&GadgetEnergy.charge(player.hotbar().getItemStack((short)0))==beforeCharge,"Friendly bird pickup remains rejected without an energy debit");
            }finally{
                gravity.cleanup(world);store.tryRemoveComponent(player.ref(),com.hypixel.hytale.server.spawning.SpawnLineage.getComponentType());
                if(target.isValid())store.removeEntity(target,RemoveReason.REMOVE);
            }
        }
        System.out.println("NATIVE_GRAVITIC_BIRD_VERIFICATION_PASSED: naturally absent Sparrow/Crow attitude caches, independent arc query, actual carry/native flight ticks/release, real lineage friendship and zero-cost protected-target rejection.");
    }
    private static void verifyDroppedItemCarry(World world,NativePlayerFixture player,GraviticManipulator gravity,Vector3d eye,Vector3d goal){
        var store=world.getEntityStore().getStore();var payload=new ItemStack("SM_Resonite_Ingot",7).withMetadata("GraviticProof",com.hypixel.hytale.codec.Codec.STRING,"original collectible stack");
        var droppedType=com.hypixel.hytale.server.core.modules.entity.item.ItemComponent.getComponentType();
        var dropped=store.addEntity(com.hypixel.hytale.server.core.modules.entity.item.ItemComponent.generateItemDrop(store,payload,new Vector3d(12.5,141,16.5),new Rotation3f(),0,0,0),com.hypixel.hytale.component.AddReason.SPAWN);
        try{
            store.tick(.01f);
            require(store.getComponent(dropped,Intangible.getComponentType())!=null&&store.getComponent(dropped,com.hypixel.hytale.server.core.modules.entity.item.PreventPickup.getComponentType())==null,"Native collectible item is Intangible but allows pickup");
            var tool=new ItemStack(GraviticManipulator.ITEM,1);player.hotbar().setItemStackForSlot((short)0,tool,false);
            store.getComponent(player.ref(),HeadRotation.getComponentType()).setRotation(Rotation3f.lookAt(eye,AdvancedGadgetTargeting.center(dropped,store)));
            gravity.interact(player.owner(),store,player.hotbar(),(short)0,tool,"gravity_grab");
            store.getComponent(player.ref(),HeadRotation.getComponentType()).setRotation(Rotation3f.lookAt(eye,goal));
            for(int i=0;i<20;i++){gravity.tick(world,.05);store.tick(.05f);}gravity.tick(world,.05);
            require(dropped.isValid()&&AdvancedGadgetTargeting.center(dropped,store).distance(goal)<.08,"Gravitic Manipulator acquires the original native collectible despite its Intangible marker");
            gravity.interact(player.owner(),store,player.hotbar(),(short)0,player.hotbar().getItemStack((short)0),"gravity_grab");
            require(store.getComponent(dropped,droppedType).getItemStack().equals(payload),"Portable item carry/release preserves original identity, quantity and metadata");
        }finally{gravity.cleanup(world);if(dropped.isValid())store.removeEntity(dropped,RemoveReason.REMOVE);}
        var displayHolder=com.hypixel.hytale.server.core.modules.entity.item.ItemComponent.generateItemDrop(store,payload,new Vector3d(12.5,143,16.5),new Rotation3f(),0,0,0);
        displayHolder.ensureComponent(com.hypixel.hytale.server.core.modules.entity.item.PreventPickup.getComponentType());
        var display=store.addEntity(displayHolder,com.hypixel.hytale.component.AddReason.SPAWN);
        try{
            store.tick(.01f);var tool=new ItemStack(GraviticManipulator.ITEM,1);player.hotbar().setItemStackForSlot((short)0,tool,false);
            store.getComponent(player.ref(),HeadRotation.getComponentType()).setRotation(Rotation3f.lookAt(eye,AdvancedGadgetTargeting.center(display,store)));
            gravity.interact(player.owner(),store,player.hotbar(),(short)0,tool,"gravity_grab");
            require(GadgetEnergy.charge(player.hotbar().getItemStack((short)0))==GadgetEnergy.charge(tool),"PreventPickup display remains excluded without spending acquisition energy");
            var untouched=new Vector3d(store.getComponent(display,TransformComponent.getComponentType()).getPosition());
            store.getComponent(player.ref(),HeadRotation.getComponentType()).setRotation(Rotation3f.lookAt(eye,goal));gravity.tick(world,.1);
            require(store.getComponent(display,TransformComponent.getComponentType()).getPosition().equals(untouched),"Protected display cannot follow a gadget's carry motion");
        }finally{gravity.cleanup(world);if(display.isValid())store.removeEntity(display,RemoveReason.REMOVE);}
    }
    private static void verifyCreatureReleaseBoundaries(World world,NativePlayerFixture player,GraviticManipulator gravity,Vector3d eye,Vector3d goal){
        var store=world.getEntityStore().getStore();var target=NPCPlugin.get().spawnNPC(store,"Zombie",null,new Vector3d(12.5,140,16.5),new Rotation3f()).first();
        int index=EntityEffect.getAssetMap().getIndex(GraviticManipulator.EFFECT);
        try{
            store.tick(.05f);var tool=new ItemStack(GraviticManipulator.ITEM,1);player.hotbar().setItemStackForSlot((short)0,tool,false);
            store.getComponent(player.ref(),HeadRotation.getComponentType()).setRotation(Rotation3f.lookAt(eye,AdvancedGadgetTargeting.center(target,store)));
            gravity.interact(player.owner(),store,player.hotbar(),(short)0,tool,"gravity_grab");
            var effects=store.getComponent(target,EffectControllerComponent.getComponentType());require(effects.getActiveEffects().containsKey(index),"Unload fixture acquires an actual creature");
            store.getComponent(player.ref(),HeadRotation.getComponentType()).setRotation(Rotation3f.lookAt(eye,goal));
            for(int i=0;i<12;i++){gravity.tick(world,.05);store.tick(.05f);}
            require(store.getComponent(target,TransformComponent.getComponentType()).getPosition().y>141.25,"Unload fixture first lifts its creature well clear of the floor");
            var holder=store.removeEntity(target,RemoveReason.UNLOAD);gravity.tick(world,.05);
            var registry=com.hypixel.hytale.server.core.universe.world.storage.EntityStore.REGISTRY;
            // Chunk reload decodes a new NPC/Role. Reusing the removed holder would retain the
            // old role's cached references and take RoleBuilderSystem's partial-load shortcut.
            var decoded=registry.deserialize(registry.serialize(holder));
            require(decoded!=null&&decoded.getComponent(com.hypixel.hytale.server.npc.entities.NPCEntity.getComponentType()).getRole()==null,"Real native entity codec reconstructs the NPC rather than retaining an unloaded cached role");
            target=store.addEntity(decoded,com.hypixel.hytale.component.AddReason.LOAD);
            require(target!=null&&target.isValid(),"Native creature can reload after its carrying world reference unloads");
            effects=store.getComponent(target,EffectControllerComponent.getComponentType());double unloadedY=store.getComponent(target,TransformComponent.getComponentType()).getPosition().y;
            for(int i=0;i<10;i++)store.tick(.05f);
            require(!effects.getActiveEffects().containsKey(index)&&store.getComponent(target,com.hypixel.hytale.server.core.entity.Frozen.getComponentType())==null,"Unloaded creature's finite lease expires after reload without a persistent freeze");
            require(store.getComponent(target,TransformComponent.getComponentType()).getPosition().y<unloadedY-.2,"Reloaded creature returns to native falling without a gadget refresh: before="+unloadedY+", after="+store.getComponent(target,TransformComponent.getComponentType()).getPosition()+", velocity="+store.getComponent(target,com.hypixel.hytale.server.npc.entities.NPCEntity.getComponentType()).getRole().getActiveMotionController().getExternalVelocity());
            store.getComponent(player.ref(),HeadRotation.getComponentType()).setRotation(Rotation3f.lookAt(eye,AdvancedGadgetTargeting.center(target,store)));
            tool=new ItemStack(GraviticManipulator.ITEM,1);player.hotbar().setItemStackForSlot((short)0,tool,false);gravity.interact(player.owner(),store,player.hotbar(),(short)0,tool,"gravity_grab");
            require(effects.getActiveEffects().containsKey(index),"Creature can be acquired again after its unload lease expired");
            int physical=com.hypixel.hytale.server.core.modules.entity.damage.DamageCause.getAssetMap().getIndex("Physical");
            com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems.executeDamage(target,store,new com.hypixel.hytale.server.core.modules.entity.damage.Damage(new com.hypixel.hytale.server.core.modules.entity.damage.Damage.EntitySource(player.ref()),physical,10000));
            require(store.getComponent(target,com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent.getComponentType())!=null,"Held creature remains vulnerable to native lethal damage");
            gravity.tick(world,.05);
            require(!effects.getActiveEffects().containsKey(index),"Death immediately retires the portable suspension lease");
        }finally{gravity.cleanup(world);if(target!=null&&target.isValid())store.removeEntity(target,RemoveReason.REMOVE);}
    }
    static void verifyGeometry(){
        var box=new Box(-.5,0,-.5,.5,2,.5);var origin=new Vector3d(0,1,0);var direction=new Vector3d(0,0,1);
        require(Math.abs(AdvancedGadgetTargeting.rayBody(origin,direction,new Vector3d(0,0,5),box,18)-4.5)<1e-8,"Ray uses creature body surface");
        require(Double.isInfinite(AdvancedGadgetTargeting.rayBody(origin,direction,new Vector3d(2,0,5),box,18)),"Parallel ray outside body misses");
        require(Double.isInfinite(AdvancedGadgetTargeting.rayBody(origin,direction,new Vector3d(0,0,5),box,4)),"Nearer wall caps target range");
        require(ArcProjector.damage(0)==8&&ArcProjector.damage(1)==6&&ArcProjector.damage(2)==4.5&&ArcProjector.damage(3)==3.375,"Four-hop damage diminishes without amplification");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
