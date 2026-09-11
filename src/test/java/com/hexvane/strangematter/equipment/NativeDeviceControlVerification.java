package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.StrangeMatterConfig;
import com.hexvane.strangematter.anomaly.*;
import com.hexvane.strangematter.machine.*;
import com.hexvane.strangematter.research.ResearchService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.packets.interface_.CustomPage;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.nio.file.Files;
import java.util.*;

/** Native direct-use controls, item stasis, loaded-block suppression, persistence and working presentation. */
public final class NativeDeviceControlVerification {
    public static void verify(World world)throws Exception {
        var directory=Files.createTempDirectory("sm-native-device-controls-");
        var anomalies=new AnomalyService(directory);anomalies.naturalGeneration=false;
        try(var research=new ResearchService(directory);var machines=new MachineService(directory,new StrangeMatterConfig(),research,anomalies)){
            var store=world.getEntityStore().getStore();var fields=new LaboratoryFields(machines);
            var projector=new Vector3i(5,220,5);var nullifier=new Vector3i(12,220,5);var second=new Vector3i(15,220,5);var conduit=new Vector3i(8,220,9);
            var ids=new ArrayList<UUID>();Ref<EntityStore> item=null;
            try(var player=NativePlayerFixture.create(world,"NativeDirectDeviceControls",new Vector3d(9.5,220,6.5))){
                world.setBlock(projector.x,projector.y,projector.z,"SM_Stasis_Projector");
                var stasis=machines.register(world,projector,"SM_Stasis_Projector");
                var payload=new ItemStack("SM_Resonite_Ingot",7).withMetadata("StasisProof",Codec.STRING,"keep specimen");
                item=store.addEntity(ItemComponent.generateItemDrop(store,payload,new Vector3d(5.5,220.75,5.5),new Rotation3f(),0,0,0),AddReason.SPAWN);
                store.tick(.01f);fields.tick(world,.25);
                var transform=store.getComponent(item,TransformComponent.getComponentType());
                require(transform.getPosition().distance(new Vector3d(5.5,220.75+Math.sin(.5)*.1,5.5))<.001,"Enabled projector actually holds its native dropped specimen above the pad");
                int pages=player.packets().ofType(CustomPage.class).size();
                machines.open(player.owner(),store,projector);
                require(!stasis.enabled&&player.player().getPageManager().getCustomPage()==null&&player.packets().ofType(CustomPage.class).size()==pages,"Using projector toggles off without creating or updating a GUI");
                fields.tick(world,.25);var released=new Vector3d(5.65,220.8,5.5);transform.setPosition(released);
                fields.tick(world,.25);require(transform.getPosition().equals(released),"Disabled projector releases its stored specimen instead of continuing to pin it");
                machines.open(player.owner(),store,projector);store.tick(.001f);fields.tick(world,.25);
                require(stasis.enabled&&transform.getPosition().distance(new Vector3d(5.5,220.75+Math.sin(2)*.1,5.5))<.001,"Next direct use enables stasis and reacquires the nearby specimen");
                require(store.getComponent(item,ItemComponent.getComponentType()).getItemStack().equals(payload),"Stasis on/off preserves the exact item, quantity and metadata");

                world.setBlock(nullifier.x,nullifier.y,nullifier.z,"SM_Anomaly_Nullifier");
                var suppressor=machines.register(world,nullifier,"SM_Anomaly_Nullifier");
                var center=suppressor.center();
                for(var type:AnomalyType.values()){
                    require(machines.suppressed(world,new Vector3d(center).add(12,0,0),type),"All six disciplines are suppressed at the inclusive twelve-block boundary: "+type);
                    require(!machines.suppressed(world,new Vector3d(center).add(12.01,0,0),type),"Anomaly just outside configured range stays active: "+type);
                    require(!machines.suppressed(world,new Vector3d(center).add(9,9,0),type),"Range is a sphere rather than a cube: "+type);
                }
                machines.tick(world,.05);require(!suppressor.active,"Enabled nullifier is quiet when no anomaly is nearby");
                var anomaly=anomalies.spawn(AnomalyType.THOUGHTWELL,world,new Vector3d(center).add(2,0,0),true);ids.add(anomaly.id);
                machines.tick(world,.05);
                require(suppressor.active&&MachineWorkEffects.WORKING.equals(world.getBlockType(nullifier.x,nullifier.y,nullifier.z).getCurrentInteractionState()),"Nearby anomaly starts the real native working model and hum");
                require(machines.valid(world,suppressor)&&machines.suppressed(world,anomaly.position(),anomaly.type),"Working variant retains suppression and machine identity");
                machines.open(player.owner(),store,nullifier);
                require(!suppressor.enabled&&!machines.suppressed(world,anomaly.position(),anomaly.type)&&!suppressor.active,"Direct use immediately disables suppression and working presentation");
                require(player.packets().ofType(CustomPage.class).size()==pages&&player.player().getPageManager().getCustomPage()==null,"Nullifier also uses direct controls without any GUI");
                var reloaded=new MachineService(directory,machines.config,research,anomalies);
                require(!reloaded.get(world,nullifier).enabled&&!reloaded.suppressed(world,anomaly.position(),anomaly.type),"Disabled nullifier survives actual machine save and reload");
                machines.open(player.owner(),store,nullifier);
                require(suppressor.enabled&&machines.suppressed(world,anomaly.position(),anomaly.type),"Next use re-enables suppression without fuel or a cable");
                world.setBlock(second.x,second.y,second.z,"SM_Anomaly_Nullifier");
                var other=machines.register(world,second,"SM_Anomaly_Nullifier");
                machines.toggle(suppressor);require(machines.suppressed(world,anomaly.position(),anomaly.type),"An overlapping enabled nullifier keeps the field suppressed");
                machines.toggle(other);require(!machines.suppressed(world,anomaly.position(),anomaly.type),"Last nullifier disabled releases the field");
                machines.toggle(other);world.setBlock(second.x,second.y,second.z,"Rock_Stone");
                require(!machines.suppressed(world,anomaly.position(),anomaly.type),"Replacing a nullifier block defeats a stale saved registration immediately");
                var remote=new Vector3i(1_000_000,220,1_000_000);machines.register(world,remote,"SM_Anomaly_Nullifier");
                require(!machines.suppressed(world,new Vector3d(remote).add(.5,.5,.5),AnomalyType.GRAVITY),"Registry entries in unloaded chunks never generate terrain or suppress fields");
                machines.removed(world,remote);
                store.getComponent(player.ref(),TransformComponent.getComponentType()).setPosition(new Vector3d(28.5,220,25.5));
                machines.open(player.owner(),store,nullifier);require(!suppressor.enabled,"A distant player cannot toggle a device");
                world.setBlock(conduit.x,conduit.y,conduit.z,"SM_Resonant_Conduit");
                var wire=machines.register(world,conduit,"SM_Resonant_Conduit");wire.enabled=false;machines.save();
                var legacy=new MachineService(directory,machines.config,research,anomalies);
                require(legacy.get(world,conduit).enabled,"Previously disabled saved conduits are restored as passive conductors");
                machines.tick(world,.05);require(wire.enabled,"Passive conduit work also repairs a stale disabled live state");
                store.getComponent(player.ref(),TransformComponent.getComponentType()).setPosition(new Vector3d(9.5,220,6.5));
                int beforeUse=player.packets().packets.size();machines.open(player.owner(),store,conduit);
                require(wire.enabled&&player.packets().packets.size()==beforeUse,"Server ignores conduit use without GUI, sound or power switching");
                // Item.processConfig merges native unarmed Use into every held block. Retain that
                // fallback so a player carrying conduit can still use other blocks, without SM_Use.
                var defaults=com.hypixel.hytale.server.core.modules.interaction.interaction.UnarmedInteractions.getAssetMap();
                String use=defaults.getAsset("Block").getInteractions().getOrDefault(InteractionType.Use,
                    defaults.getAsset(com.hypixel.hytale.server.core.modules.interaction.interaction.UnarmedInteractions.DEFAULT_UNARMED_ID).getInteractions().get(InteractionType.Use));
                require(Objects.equals(use,Item.getAssetMap().getAsset("SM_Resonant_Conduit").getInteractions().get(InteractionType.Use)),"Held conduit retains only native use of other blocks, with no laboratory interaction");
                var base=BlockType.getAssetMap().getAsset("SM_Resonant_Conduit");
                for(int mask=-1;mask<64;mask++){
                    var variant=mask<0?base:base.getBlockForState(String.format(Locale.ROOT,"Connection%02d",mask));
                    require(variant!=null&&(variant.getInteractions()==null||!variant.getInteractions().containsKey(InteractionType.Use))
                            &&(variant.getInteractionHint()==null||variant.getInteractionHint().isEmpty()),"All conduit connection shapes omit native use bindings and hints: "+mask);
                }
            }finally{
                if(item!=null&&item.isValid())store.removeEntity(item,RemoveReason.REMOVE);
                for(UUID id:ids)anomalies.remove(id);
                for(var position:List.of(projector,nullifier,second,conduit)){world.setBlock(position.x,position.y,position.z,"Empty");machines.removed(world,position);}
                fields.cleanup(world);anomalies.stopWorld(world);
            }
        }
        System.out.println("NATIVE_DEVICE_CONTROL_VERIFICATION_PASSED: direct stasis/nullifier use without UI, exact item hold/release/reacquisition, range and all disciplines, overlap, working state, persistence, replacement, unloaded registration, use reach and all65 passive conduit shapes with legacy power restored.");
    }
    private static void require(boolean value,String description){if(!value)throw new AssertionError(description);}
}
