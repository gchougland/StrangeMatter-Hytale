package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.anomaly.*;
import com.hexvane.strangematter.machine.MachineService;
import com.hexvane.strangematter.research.ResearchService;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.ToServerPacket;
import com.hypixel.hytale.protocol.packets.interaction.MountNPC;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.ProtocolVersion;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.joml.Vector3d;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Requires the actual loaded server/world; records native packets without opening a socket. */
public final class NativeEquipmentVerification {
    public static void verify(World world,ResearchService research,AnomalyService anomalies,MachineService machines,Path directory)throws Exception {
        NativeGadgetEnergyVerification.verify(world,directory);
        NativeAdvancedGadgetsVerification.verify(world);
        verifyHeldInteractions();
        var holder=EntityStore.REGISTRY.newHolder();var player=new Player();holder.addComponent(Player.getComponentType(),player);
        var packets=new RecordingPackets();var owner=new PlayerRef(holder,UUID.randomUUID(),"EquipmentVerification","en-US",packets,null);
        var mount=new NPCMountComponent();int role=NPCPlugin.get().getIndex("SM_Hoverboard_Mount");
        require(role>=0,"Hoverboard native NPC role loaded");mount.setOwnerPlayerRef(owner);mount.setOriginalRoleIndex(role);mount.setAnchor(0,MobilityTools.BOARD_ANCHOR_Y,0);
        var model=ModelAsset.getAssetMap().getAsset("SM_Hoverboard_Mount");require(model!=null,"Hoverboard native mount model loaded");
        var store=world.getEntityStore().getStore();
        var spawned=NPCPlugin.get().spawnEntity(store,role,new Vector3d(24,18,24),new Rotation3f(),MobilityTools.boardModel(model),
                (npc,board,entityStore)->MobilityTools.prepareBoard(board,mount),null);
        require(spawned!=null&&spawned.first()!=null,"Real hoverboard NPC spawned");
        var board=spawned.first();int network=store.getComponent(board,NetworkId.getComponentType()).getId();
        require(player.getMountEntityId()==network,"Owned holder triggers native OnAdd and assigns rider mount ID");
        MountNPC sent=packets.packets.stream().filter(p->p instanceof MountNPC).map(p->(MountNPC)p).findFirst().orElseThrow(()->new AssertionError("Native mount packet was not emitted"));
        var bytes=MemorySegment.ofArray(new byte[sent.computeSize()]);require(sent.serialize(bytes,0)==bytes.byteSize(),"Mount packet exact wire size");
        require(sent.equals(MountNPC.toObject(bytes)),"Native MountNPC wire roundtrip retains board ID and anchor");
        var mountModel=store.getComponent(board,com.hypixel.hytale.server.core.modules.entity.component.ModelComponent.getComponentType()).getModel();
        require(mountModel.getScale()==2&&Math.abs(mountModel.getBoundingBox().min.y-model.getBoundingBox().min.y*2)<.001,"Native board model and collider are twice the original entity scale");
        require(sent.anchorY==MobilityTools.BOARD_ANCHOR_Y,"Native rider anchor follows doubled board");
        var npc=store.getComponent(board,com.hypixel.hytale.server.npc.entities.NPCEntity.getComponentType());
        require(npc.getRole().getActiveMotionController()!=null&&npc.getRole().getActiveMotionController().getClass().getSimpleName().contains("Walk"),"Mounted board has a native walking controller");
        var config=com.hypixel.hytale.server.core.entity.entities.player.movement.MovementConfig.getAssetMap().getAsset("SM_Hoverboard_Mount");
        var horse=com.hypixel.hytale.server.core.entity.entities.player.movement.MovementConfig.getAssetMap().getAsset("Mount");
        require(config!=null&&horse!=null,"Board inherits the loaded native horse movement asset");
        var movement=new com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager();
        movement.setDefaultSettings(config,new com.hypixel.hytale.server.core.modules.physics.component.PhysicsValues(),com.hypixel.hytale.protocol.GameMode.Adventure);movement.applyDefaultSettings();movement.update(packets);
        var settings=movement.getSettings();var horseSettings=horse.toPacket();
        require(settings.maxSpeedMultiplier>0&&settings.wishDirectionWeightX>0&&settings.wishDirectionWeightY>0,"Resolved native movement packet has nonzero speed limit and steering weights (old partial asset froze input)");
        require(settings.maxSpeedMultiplier==horseSettings.maxSpeedMultiplier&&settings.baseSpeed==horseSettings.baseSpeed&&settings.jumpForce==horseSettings.jumpForce&&settings.collisionExpulsionForce==horseSettings.collisionExpulsionForce,"Ground movement, jumping and collision settings match native horse controller");
        require(settings.fly==com.hypixel.hytale.protocol.FlyMode.Disabled,"Hoverboard keeps horse-style movement without creative flight");
        var update=packets.packets.stream().filter(packet->packet instanceof com.hypixel.hytale.protocol.packets.player.UpdateMovementSettings).map(packet->(com.hypixel.hytale.protocol.packets.player.UpdateMovementSettings)packet).findFirst().orElseThrow();
        var movementBytes=MemorySegment.ofArray(new byte[update.computeSize()]);update.serialize(movementBytes,0);
        require(update.equals(com.hypixel.hytale.protocol.packets.player.UpdateMovementSettings.toObject(movementBytes)),"Resolved mounted movement settings survive native client packet serialization");
        mount.setOwnerPlayerRef(null);store.removeEntity(board,RemoveReason.REMOVE);

        var miningCells=EquipmentService.hammerCells(new org.joml.Vector3i(25,12,25),1,-1,1,1);
        for(var pos:miningCells)world.setBlock(pos.x,pos.y,pos.z,"Rock_Stone");
        var thorium=com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap().getAsset("Tool_Pickaxe_Thorium");
        require(thorium!=null&&thorium.getTool()!=null,"Thorium pickaxe mining profile loaded");
        var hammer=new ItemStack("SM_Graviton_Hammer",1);
        int first=com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils.performBlockDamage(null,null,miningCells,new org.joml.Vector3i(25,12,25),hammer,thorium.getTool(),null,false,1f,0,false,false,store,world.getChunkStore().getStore());
        require(first==0&&miningCells.stream().allMatch(p->"Rock_Stone".equals(world.getBlockType(p.x,p.y,p.z).getId())),"First Thorium-strength area hit damages all nine stones without instant breaking");
        int secondHit=com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils.performBlockDamage(null,null,miningCells,new org.joml.Vector3i(25,12,25),hammer,thorium.getTool(),null,false,1f,0,false,false,store,world.getChunkStore().getStore());
        require(secondHit==9,"Second normal native hit completes all nine damaged stones");

        for(var pos:miningCells)world.setBlock(pos.x,pos.y,pos.z,"Rock_Stone");
        int chargedHit=com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils.performBlockDamage(null,null,miningCells,new org.joml.Vector3i(25,12,25),hammer,thorium.getTool(),null,false,EquipmentService.hammerDamageScale("hammer1"),0,false,false,store,world.getChunkStore().getStore());
        require(chargedHit==9&&EquipmentService.hammerDamageScale("hammer0")==1&&EquipmentService.hammerDamageScale("hammer2")==3&&EquipmentService.hammerDamageScale("hammer3")==4,"Charged area damage breaks nine stones in one native hit while primary remains unchanged");
        var attacker=NPCPlugin.get().spawnNPC(store,"Cow",null,new Vector3d(25,32,25),new Rotation3f()).first();
        var victim=NPCPlugin.get().spawnNPC(store,"Cow",null,new Vector3d(25,32,26),new Rotation3f()).first();
        var health=store.getComponent(victim,com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap.getComponentType());
        int healthIndex=com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes.getHealth();
        float unchanged=health.get(healthIndex).get();
        require(!EquipmentService.damageHammerTarget(attacker,victim,store,"hammer0")&&health.get(healthIndex).get()==unchanged,"Primary hammer adds no new combat damage");
        var targetNpc=store.getComponent(victim,com.hypixel.hytale.server.npc.entities.NPCEntity.getComponentType());
        require(!targetNpc.getCanCauseDamage(attacker,store),"Native Cow self-species damage group is active in the fixture");
        EquipmentService.damageHammerTarget(attacker,victim,store,"hammer1");
        require(health.get(healthIndex).get()==unchanged,"Attributed slam respects native protected damage groups");
        store.removeEntity(attacker,RemoveReason.REMOVE);
        attacker=NPCPlugin.get().spawnNPC(store,"Zombie",null,new Vector3d(25,32,25),new Rotation3f()).first();
        require(targetNpc.getCanCauseDamage(attacker,store),"Native hostile attacker is permitted to damage this target");
        for(String charge:List.of("hammer1","hammer2","hammer3")) {
            health.maximizeStatValue(healthIndex);float beforeHealth=health.get(healthIndex).get();
            require(beforeHealth>EquipmentService.chargedHammerDamage(charge),"Damage subject survives charge fixture");
            EquipmentService.damageHammerTarget(attacker,victim,store,charge);
            require(Math.abs(health.get(healthIndex).get()-(beforeHealth-EquipmentService.chargedHammerDamage(charge)))<.001,"Native attributed charged hammer health delta "+charge+": before="+beforeHealth+", after="+health.get(healthIndex).get()+", expectedDamage="+EquipmentService.chargedHammerDamage(charge));
        }
        store.removeEntity(victim,RemoveReason.REMOVE);store.removeEntity(attacker,RemoveReason.REMOVE);

        try(var live=NativePlayerFixture.create(world,"CapsuleVerification",new Vector3d(21.5,18,20.5))){
        owner=live.owner();
        var field=anomalies.spawn(AnomalyType.GRAVITY,world,new Vector3d(21.5,18,21.5),true);
        var captured=anomalies.capture(field.id).orElseThrow();String token=captured.token();
        var capsule=new ItemStack(captured.itemId(),1).withMetadata("SMAnomaly",Codec.STRING,token).withMetadata("VerificationName",Codec.STRING,"Preserved identity");
        var casInventory=new SimpleItemContainer((short)2);var stack=capsule.withQuantity(3);casInventory.setItemStackForSlot((short)0,stack,false);
        require(LaboratoryProjectiles.removeExact(casInventory,(short)0,stack,1),"Unique payload reserves one unit with native expected-value replacement");
        var remaining=casInventory.getItemStack((short)0);
        require(remaining.getQuantity()==2&&com.hexvane.strangematter.util.StackData.metadata(remaining).equals(com.hexvane.strangematter.util.StackData.metadata(capsule)),"Reservation preserves every metadata field of the remaining stack");
        require(!LaboratoryProjectiles.removeExact(casInventory,(short)0,stack,1)&&casInventory.getItemStack((short)0).getQuantity()==2,"Delayed stale slot snapshot cannot consume another unit");
        require(LaboratoryProjectiles.removeExact(casInventory,(short)0,remaining,2)&&ItemStack.isEmpty(casInventory.getItemStack((short)0)),"Exact final-unit removal clears the slot");
        var inventory=live.hotbar();inventory.setItemStackForSlot((short)0,capsule,false);live.save();
        var projectiles=new LaboratoryProjectiles(anomalies,new LaboratoryFields(machines),directory.resolve("equipment-integration"));
        var launched=projectiles.launchCapsule(world,owner,new Vector3d(21.5,18,20.5),new Vector3d(0,0,1),inventory,(short)0,capsule,false);
        require(launched==LaboratoryProjectiles.LaunchResult.LAUNCHED&&ItemStack.isEmpty(inventory.getItemStack((short)0)),"Survival throw reserves native inventory once");
        require(projectiles.inFlight(token),"Live throw excludes the nonce from another operation");
        var pending=LaboratoryProjectiles.readFlights(directory.resolve("equipment-integration/capsule-flights.json"));
        require(pending.size()==1&&pending.getFirst().phase==LaboratoryProjectiles.Phase.PREPARED&&com.hexvane.strangematter.util.StackData.metadata(LaboratoryProjectiles.decodeCapsule(pending.getFirst().item)).equals(com.hexvane.strangematter.util.StackData.metadata(capsule)),"Actual launch persisted the full unique payload before inventory acknowledgement");
        var shotsField=LaboratoryProjectiles.class.getDeclaredField("shots");shotsField.setAccessible(true);
        @SuppressWarnings("unchecked") var shots=(Map<String,List<LaboratoryProjectiles.Shot>>)shotsField.get(projectiles);
        var flight=shots.get(world.getName()).getFirst();
        require(flight.inventorySave!=null,"Throw invokes the real native player save, never a substituted future");
        flight.inventorySave.get(10,java.util.concurrent.TimeUnit.SECONDS);
        var savedPlayer=com.hypixel.hytale.server.core.universe.Universe.get().getPlayerStorage().load(owner.getUuid()).get(10,java.util.concurrent.TimeUnit.SECONDS);
        require(ItemStack.isEmpty(savedPlayer.getComponent(com.hypixel.hytale.server.core.inventory.InventoryComponent.Hotbar.getComponentType()).getInventory().getItemStack((short)0)),"Actual player file acknowledges consumed capsule before release");
        for(int y=5;y<=21;y++)world.setBlock(21,y,23,"Rock_Stone");
        var start=new Vector3d(flight.position);projectiles.tick(world,.01);
        require(flight.visual!=null&&flight.visual.isValid()&&store.getComponent(flight.visual,com.hypixel.hytale.server.core.modules.entity.component.ModelComponent.getComponentType())!=null,"Capsule creates a visible networked model before impact");
        require(flight.position.z>start.z&&anomalies.get(field.id).orElseThrow().contained,"Thrown model follows its ballistic path while original anomaly remains contained");
        for(int i=0;i<20&&projectiles.inFlight(token);i++)projectiles.tick(world,.05);
        require(!projectiles.inFlight(token)&&!anomalies.get(field.id).orElseThrow().contained,"Acknowledged throw moves, hits a native block and releases the original identity");
        require(!projectiles.validCapsule(capsule,false),"Impact retires the old nonce, rejecting replay");
        world.setBlock(21,18,21,"Rock_Stone");
        require(LaboratoryProjectiles.throwOrigin(world,new Vector3d(21.5,18.5,20.9),new Vector3d(0,0,1)).z<21,"Capsule hand offset cannot skip a point-blank wall");
        world.setBlock(21,18,21,"Empty");

        var second=anomalies.capture(field.id).orElseThrow();var creative=new ItemStack(second.itemId(),1).withMetadata("SMAnomaly",Codec.STRING,second.token());
        inventory.setItemStackForSlot((short)0,creative,false);
        require(projectiles.launchCapsule(world,owner,new Vector3d(21.5,18,20.5),new Vector3d(0,0,1),inventory,(short)0,creative,true)==LaboratoryProjectiles.LaunchResult.LAUNCHED,"Tagged creative capsule launches without a player-storage dependency");
        require(creative.equals(inventory.getItemStack((short)0)),"Creative capsule does not consume native inventory");
        require(projectiles.launchCapsule(world,owner,new Vector3d(21.5,18,20.5),new Vector3d(0,0,1),inventory,(short)0,creative,true)==LaboratoryProjectiles.LaunchResult.ALREADY_QUEUED,"Repeated creative input identifies the existing throw instead of duplicating it");
        for(int i=0;i<20&&projectiles.inFlight(second.token());i++)projectiles.tick(world,.05);
        require(!projectiles.inFlight(second.token())&&!anomalies.get(field.id).orElseThrow().contained,"Creative queue completes its actual projectile impact without a needless inventory save");
        projectiles.cleanup(world);
        }
        System.out.println("NATIVE_EQUIPMENT_VERIFICATION_PASSED: owned2x NPC with horse movement settings and valid mount packet; native charged block/combat damage; actual capsule native disk save, visible flight/impact/replay and creative no-save path.");
    }
    private static void verifyHeldInteractions(){
        var hammer=com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap().getAsset("SM_Graviton_Hammer");
        var primary=com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction.getAssetMap().getAsset(hammer.getInteractions().get(com.hypixel.hytale.protocol.InteractionType.Primary));
        var swing=com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction.getAssetMap().getAsset(primary.getInteractionIds()[0]);
        var swingPacket=swing.toPacket();
        require("Pickaxe".equals(hammer.getPlayerAnimationsId())&&"Mine".equals(swingPacket.effects.itemAnimationId),"Loaded hammer sends the native pickaxe Mine animation");
        require(Math.abs(primary.toPacket().cooldown.cooldown-.7f)<.001&&EquipmentService.HAMMER_SWING_NANOS==700_000_000L,"Native hammer cooldown and server-authoritative swing guard agree at0.7 seconds");
        require(swingPacket.runTime>.1&&swingPacket.effects.localSoundEventIndex>0&&swingPacket.effects.worldSoundEventIndex>0,"Mining has a native windup and resolved owner/world swing audio");
        var secondary=com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction.getAssetMap().getAsset(hammer.getInteractions().get(com.hypixel.hytale.protocol.InteractionType.Secondary));
        var charge=com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction.getAssetMap().getAsset(secondary.getInteractionIds()[0]);
        require(charge.toPacket() instanceof com.hypixel.hytale.protocol.ChargingInteraction&&"SM_Graviton_Chargeup_SFX".equals(charge.getEffects().getWorldSoundEventId()),"The native Charging interaction starts the charge sound before release");
        require(charge.toPacket().effects.localSoundEventIndex==charge.toPacket().effects.worldSoundEventIndex&&charge.toPacket().effects.clearSoundEventOnFinish,"Charge sound resolves for the wielder and stops when charging ends");
        for(var type:AnomalyType.values()){
            var item=com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap().getAsset(type.capsuleItemId());
            for(var action:List.of(com.hypixel.hytale.protocol.InteractionType.Primary,com.hypixel.hytale.protocol.InteractionType.Secondary,com.hypixel.hytale.protocol.InteractionType.Use)){
                var root=com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction.getAssetMap().getAsset(item.getInteractions().get(action));
                var launch=com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction.getAssetMap().getAsset(root.getInteractionIds()[0]);
                require("Throw".equals(launch.toPacket().effects.itemAnimationId),"Filled capsule sends the native rubble Throw animation for "+type+" "+action);
            }
        }
        System.out.println("HELD_INTERACTION_VERIFICATION: native Mine/Throw animations,0.7s hammer cadence, charging audio and release cleanup resolved in actual client packets.");
    }
    public static final class RecordingPackets extends PacketHandler {
        final List<ToClientPacket> packets=new ArrayList<>();
        RecordingPackets(){super(null,new ProtocolVersion(0));}
        @Override public String getIdentifier(){return "StrangeMatter equipment fixture";}
        @Override public void accept(ToServerPacket packet){}
        @Override public void write(ToClientPacket packet){packets.add(packet);}
        @Override public void writeNoCache(ToClientPacket packet){packets.add(packet);}
        @Override public void write(ToClientPacket... values){packets.addAll(Arrays.asList(values));}
        @Override public void write(ToClientPacket[] values,ToClientPacket last){write(values);write(last);}
    }
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
