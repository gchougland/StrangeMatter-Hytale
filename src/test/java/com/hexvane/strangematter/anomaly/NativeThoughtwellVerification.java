package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.protocol.packets.world.PlaySoundEvent3D;
import com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem;
import com.hypixel.hytale.protocol.packets.entities.EntityUpdates;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ActiveAnimationComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;
import java.nio.file.Files;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

/** Actual native field/effect and recipient-specific packets; no model or input simulation bypass. */
public final class NativeThoughtwellVerification {
    public static void verify(World world)throws Exception {
        var service=new AnomalyService(Files.createTempDirectory("sm-native-thoughtwell-"));service.naturalGeneration=false;
        try(var subject=NativePlayerFixture.create(world,"ThoughtwellSubject",new Vector3d(24,55,24));
            var observer=NativePlayerFixture.create(world,"ThoughtwellObserver",new Vector3d(25,55,24))) {
            var store=subject.store();var ref=subject.ref();
            short head=(short)ItemArmorSlot.Head.ordinal();
            observer.store().getComponent(observer.ref(),InventoryComponent.Armor.getComponentType()).getInventory()
                    .setItemStackForSlot(head,new ItemStack("SM_Tinfoil_Hat",1),false);
            store.tick(.05f);
            var model=store.getComponent(ref,ModelComponent.getComponentType());
            var persistent=store.getComponent(ref,PersistentModel.getComponentType());
            var skin=store.getComponent(ref,PlayerSkinComponent.getComponentType());
            var field=service.spawn(AnomalyType.THOUGHTWELL,world,new Vector3d(24,56,25),true);
            service.tick(world,.05);
            var effect=EntityEffect.getAssetMap().getAsset("SM_Cognitive_Dissonance");
            var controller=store.getComponent(ref,EffectControllerComponent.getComponentType());
            require(effect!=null&&controller.hasEffect(effect),"Normal field application retains the blue cognitive haze");
            require(!store.getComponent(observer.ref(),EffectControllerComponent.getComponentType()).hasEffect(effect),"Worn hat prevents observer exposure");
            advance(service,world,.6);
            var active=phantoms(subject);require(active.size()==1,"Exposure produces one recognizable native creature model");
            var phantom=active.get(0);
            int phantomId=store.getComponent(phantom,NetworkId.getComponentType()).getId();
            require(phantomId!=store.getComponent(ref,NetworkId.getComponentType()).getId()
                    &&phantomId!=store.getComponent(observer.ref(),NetworkId.getComponentType()).getId(),"Native allocator gives the phantom its own entity ID");
            require(store.getComponent(phantom,ModelComponent.getComponentType()).getModel().getModelAssetId().equals(ThoughtwellHallucinations.MODELS[0]),"First apparition is a recognizable native wolf");
            require("Run".equals(store.getComponent(phantom,ActiveAnimationComponent.getComponentType()).getActiveAnimations()[AnimationSlot.Movement.ordinal()]),"Creature uses its native running animation");
            require(store.getComponent(phantom,NPCEntity.getComponentType())==null
                    &&store.getComponent(phantom,Intangible.getComponentType())!=null
                    &&store.getArchetype(phantom).contains(EntityStore.REGISTRY.getNonSerializedComponentType()),"Phantom has no NPC AI, no collision and no saved state");
            require(store.getComponent(ref,EntityTrackerSystems.EntityViewer.getComponentType()).sent.containsKey(phantom),"Native tracker sends the phantom to its owner");
            require(!store.getComponent(observer.ref(),EntityTrackerSystems.EntityViewer.getComponentType()).sent.containsKey(phantom),"Native tracker hides the phantom from observers before spawn");
            require(updated(subject,phantomId)&&!updated(observer,phantomId),"Actual native entity update packets are recipient isolated");
            var initialPosition=new Vector3d(store.getComponent(phantom,TransformComponent.getComponentType()).getPosition());
            advance(service,world,.8);
            var approached=store.getComponent(phantom,TransformComponent.getComponentType()).getPosition();
            var playerPosition=store.getComponent(ref,TransformComponent.getComponentType()).getPosition();
            require(approached.distance(playerPosition)<initialPosition.distance(playerPosition)-.7,"Apparition visibly approaches from the peripheral view");
            advance(service,world,1.2);
            require(!phantom.isValid()&&removed(subject,phantomId),"Finite apparition disappears through native entity removal");
            require(subject.packets().ofType(SpawnParticleSystem.class).stream().anyMatch(p->ThoughtwellConfusion.VANISH.equals(p.particleSystemId)),"Dissolving creature leaves a visible private cloud");
            require(observer.packets().ofType(SpawnParticleSystem.class).stream().noneMatch(p->ThoughtwellConfusion.VANISH.equals(p.particleSystemId)),"Dissolve particles remain private");
            advance(service,world,.65);
            active=phantoms(subject);require(active.size()==1,"A later finite apparition can start after the previous creature vanishes");
            require(!store.getComponent(active.get(0),ModelComponent.getComponentType()).getModel().getModelAssetId().equals(ThoughtwellHallucinations.MODELS[0]),"Repeated exposure varies the recognizable creature");
            var first=echoes(subject);require(first.size()>=2,"Thoughtwell produces spaced, repeated sensory echoes");
            require(first.get(0).position.x!=first.get(1).position.x||first.get(0).position.z!=first.get(1).position.z,"Peripheral glyph changes sides between cues");
            require(sounds(subject)>=2,"False directional footsteps/chimes reach the affected player");
            require(echoes(observer).isEmpty()&&sounds(observer)==0,"Nearby protected observer receives none of another player's subjective sounds or glyphs");
            require(store.getComponent(ref,ModelComponent.getComponentType())==model
                    &&store.getComponent(ref,PersistentModel.getComponentType())==persistent
                    &&store.getComponent(ref,PlayerSkinComponent.getComponentType())==skin,"Thought confusion never replaces avatar, saved model or skin");

            var armor=store.getComponent(ref,InventoryComponent.Armor.getComponentType()).getInventory();
            armor.setItemStackForSlot(head,new ItemStack("SM_Tinfoil_Hat",1),false);
            int count=echoes(subject).size();long soundCount=sounds(subject);advance(service,world,5.5);
            require(echoes(subject).size()==count&&sounds(subject)==soundCount,"Equipping a hat stops new hallucination cues immediately and prevents refresh");
            require(phantoms(subject).isEmpty(),"A hat also removes an already visible false creature");
            armor.setItemStackForSlot(head,ItemStack.EMPTY,false);
            advance(service,world,5.5);require(echoes(subject).size()>count,"Unprotected exposure can start a fresh finite sequence");
            // Turning off the source prevents refresh while the original 5–10 second haze runs out.
            service.setEnabled(field.id,false);advance(service,world,11);
            count=echoes(subject).size();soundCount=sounds(subject);advance(service,world,4);
            require(echoes(subject).size()==count&&sounds(subject)==soundCount,"No new sensory effects survive exposure expiry");
            require(phantoms(subject).isEmpty(),"No phantom entity survives exposure expiry");

            service.setEnabled(field.id,true);advance(service,world,5.5);
            require(echoes(subject).size()>count,"Fresh field exposure resumes after old sequence expires");
            int effectIndex=EntityEffect.getAssetMap().getIndex(effect.getId());
            controller.removeEffect(ref,effectIndex,store);
            var removedEffect=controller.getActiveEffects().get(effectIndex);
            require(controller.hasEffect(effectIndex)&&removedEffect!=null&&!removedEffect.isInfinite()
                    &&removedEffect.getRemainingDuration()==0,"Native DURATION removal leaves a zero-duration entry until its effect tick");
            service.setEnabled(field.id,false);count=echoes(subject).size();soundCount=sounds(subject);advance(service,world,3);
            require(echoes(subject).size()==count&&sounds(subject)==soundCount,"Native effect removal cancels remaining private cues");
            require(phantoms(subject).isEmpty(),"Cleansing removes the false creature without leaving tracked entities");
            service.setEnabled(field.id,true);
            for(int i=0;i<150&&phantoms(subject).isEmpty();i++)advance(service,world,.05);
            require(!phantoms(subject).isEmpty(),"World cleanup test begins with a live apparition");
            service.stopWorld(world);count=echoes(subject).size();advance(service,world,3);
            require(echoes(subject).size()==count,"World cleanup clears temporary exposure state");
            require(phantoms(subject).isEmpty(),"World cleanup removes native hallucination entities");
            var disconnected=new ThoughtwellConfusion();
            controller.addEffect(ref,effect,5,OverlapBehavior.OVERWRITE,store);
            disconnected.expose(world,ref,5,1);disconnected.tick(world,.4);store.tick(.05f);
            var orphan=phantoms(subject);require(orphan.size()==1,"Disconnect test starts with one active private creature");
            subject.close();disconnected.tick(world,.05);store.tick(.05f);
            require(!orphan.get(0).isValid(),"Owner removal cleans the transient phantom from the native store");
            disconnected.clear(world);
        } finally {service.stopWorld(world);}
        System.out.println("NATIVE_THOUGHTWELL_VERIFICATION_PASSED: recognizable animated native creatures approach and vanish, real owner-only entity tracking and packets, native ID/removal, no NPC AI or persistence, private dissolve, blue haze retained, hat/expiry/cleanse/world cleanup and unchanged avatar/skin.");
    }
    private static void advance(AnomalyService service,World world,double seconds){for(int i=0;i<(int)Math.ceil(seconds/.05);i++){service.tick(world,.05);world.getEntityStore().getStore().tick(.05f);}}
    private static List<Ref<EntityStore>> phantoms(NativePlayerFixture player){
        var result=new ArrayList<Ref<EntityStore>>();var type=ThoughtwellHallucinations.componentType();
        require(type!=null,"Hallucination visibility helper was registered before world startup");
        player.store().forEachChunk(type,(chunk,commands)->{for(int i=0;i<chunk.size();i++)if(chunk.getComponent(i,type).owner==player.ref())result.add(chunk.getReferenceTo(i));});
        return result;
    }
    private static boolean updated(NativePlayerFixture player,int id){return player.packets().ofType(EntityUpdates.class).stream().anyMatch(p->p.updates!=null&&Arrays.stream(p.updates).anyMatch(u->u.networkId==id));}
    private static boolean removed(NativePlayerFixture player,int id){return player.packets().ofType(EntityUpdates.class).stream().anyMatch(p->p.removed!=null&&Arrays.stream(p.removed).anyMatch(n->n==id));}
    private static List<SpawnParticleSystem> echoes(NativePlayerFixture player){return player.packets().ofType(SpawnParticleSystem.class).stream().filter(p->ThoughtwellConfusion.ECHO.equals(p.particleSystemId)).toList();}
    private static long sounds(NativePlayerFixture player){
        int step=SoundEvent.getAssetMap().getIndex(ThoughtwellConfusion.STEP),chime=SoundEvent.getAssetMap().getIndex(ThoughtwellConfusion.CHIME);
        require(step!=SoundEvent.EMPTY_ID&&chime!=SoundEvent.EMPTY_ID,"Native thoughtwell sound assets are loaded");
        return player.packets().ofType(PlaySoundEvent3D.class).stream().filter(p->p.soundEventIndex==step||p.soundEventIndex==chime).count();
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
