package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.ui.gadget.GadgetHudService;
import com.hypixel.hytale.builtin.mounts.MountPlugin;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.protocol.packets.entities.MountMovement;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.protocol.packets.interaction.MountNPC;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.protocol.packets.player.ClientTeleport;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.npc.components.StepComponent;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.SteeringSystem;
import org.joml.Vector3d;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Real player inventory saves, native rider movement/steering and restart receipts. */
public final class NativeMobilityRevisionVerification {
    public static void verify(World world)throws Exception{
        var directory=Files.createTempDirectory("sm-native-board-");
        var original=new ItemStack("SM_Hoverboard",1).withMaxDurability(123).withDurability(37.5)
                .withMetadata("CustomOwnerNote",Codec.STRING,"keep navy paint and calibration");
        verifyReceiptBoundaries(directory.resolve("receipts"),original);
        try(var fixture=NativePlayerFixture.create(world,"NativeHoverboardVerification",new Vector3d(16.5,17,16.5));var hud=new GadgetHudService()){
            world.setBlock(16,16,16,"Rock_Stone");
            var mobility=new MobilityTools(hud,directory.resolve("live"));
            fixture.hotbar().setItemStackForSlot((short)0,original,false);
            fixture.store().getComponent(fixture.ref(),InventoryComponent.Hotbar.getComponentType()).setActiveSlot((byte)0,fixture.ref(),fixture.store());
            mobility.useHoverboard(fixture.owner(),fixture.store());
            require(fixture.player().getMountEntityId()==0,"Mount waits for durable inventory identity before removal");
            require(!ItemStack.isEmpty(fixture.hotbar().getItemStack((short)0)),"Initial save gate retains the actual board");
            for(int i=0;i<8&&fixture.player().getMountEntityId()==0;i++){fixture.save();mobility.tick(world,.05);}
            int network=fixture.player().getMountEntityId();require(network!=0,"Two native required inventory saves deploy and mount the owned board");
            require(countBoards(fixture.inventory())==0,"Deployed board is consumed from actual native inventory");
            var board=world.getEntityStore().getRefFromNetworkId(network);require(board!=null&&board.isValid(),"Mounted native board entity exists");
            var model=fixture.store().getComponent(board,ModelComponent.getComponentType()).getModel();
            var bounds=model.getBoundingBox();
            require(bounds.min.y==0&&Math.abs(bounds.max.y-1.74)<1e-6,"Native mounted collision is rooted at its feet, not 1.1 blocks below its origin");
            require(model.getModel().equals("Items/StrangeMatter/hoverboard_mount.blockymodel")&&Math.abs(model.getEyeHeight()-1.64)<1e-6,"Mount uses the dedicated feet-origin model and corresponding eye height");
            var spawnedAt=fixture.store().getComponent(board,TransformComponent.getComponentType()).getPosition();
            require(Math.abs(spawnedAt.y-17)<1e-6,"Spawn no longer applies a separate negative-collider compensation");
            var mountPacket=fixture.packets().ofType(MountNPC.class).getLast();
            require(Math.abs(mountPacket.anchorY-1.64)<1e-6&&Math.abs(MobilityTools.BOARD_VISUAL_ORIGIN_Y+8.5*model.getScale()/64-mountPacket.anchorY)<1e-6,"Translated mount artwork places its original grip top exactly at the unchanged native rider anchor");
            var boardAudio=fixture.store().getComponent(board,com.hypixel.hytale.server.core.modules.entity.component.AudioComponent.getComponentType());
            require(boardAudio!=null&&boardAudio.getSoundEventIds().length==1,"Actual deployed board owns exactly one continuous riding sound");
            UUID boardId=fixture.store().getComponent(board,UUIDComponent.getComponentType()).getUuid();
            require(fixture.store().getComponent(board,Frozen.getComponentType())!=null&&fixture.store().getComponent(board,StepComponent.getComponentType())==null,"Only NPC simulation is suspended for client-controlled mount");
            var handler=fixture.packets();
            var steering=new SteeringSystem(NPCEntity.getComponentType());
            // Exercise the native packet path and then the exact NPC steering system which used
            // to overwrite the rider's downhill position. Not merely a MountNPC packet assertion.
            for(int step=0;step<5;step++){
                var expected=new Vector3d(16.5+step*.4,17-step*.25,16.5);
                var packet=new MountMovement();packet.absolutePosition=new Position(expected.x,expected.y,expected.z);
                packet.bodyOrientation=new Direction(0,0,0);packet.movementStates=new MovementStates();packet.movementStates.running=true;
                handler.handleMountMovement(packet,fixture.owner(),fixture.ref(),world,fixture.store());
                fixture.store().forEachChunk(steering.getQuery(),(chunk,commands)->{
                    for(int i=0;i<chunk.size();i++)if(chunk.getReferenceTo(i).equals(board))steering.tick(.05f,i,chunk,fixture.store(),commands);
                });
                var actual=fixture.store().getComponent(board,TransformComponent.getComponentType()).getPosition();
                require(actual.distanceSquared(expected)<1e-12,"Native NPC steering must preserve each rider downstep: actual="+actual+" expected="+expected);
            }
            // Reproduce airborne rider input through ALL registered server systems, including
            // fall damage, player collision processing, mount tracking and transform replication.
            // The former fixture checked the board's steering only and never moved the rider.
            int teleportsBefore=fixture.packets().ofType(ClientTeleport.class).size();
            for(int step=0;step<6;step++){
                var expected=new Vector3d(21.5,24-step*.35,20.5);
                var rider=new Vector3d(expected).add(0,MobilityTools.BOARD_ANCHOR_Y,0);
                var states=new MovementStates();states.falling=true;states.running=true;
                var packet=new MountMovement();packet.absolutePosition=new Position(expected.x,expected.y,expected.z);
                packet.bodyOrientation=new Direction(0,0,0);packet.movementStates=states;
                handler.handleMountMovement(packet,fixture.owner(),fixture.ref(),world,fixture.store());
                var input=fixture.store().getComponent(fixture.ref(),PlayerInput.getComponentType());
                input.queue(new PlayerInput.SetMovementStates(states));
                input.queue(new PlayerInput.SetClientVelocity(new Vector3d(0,-7,0)));
                input.queue(new PlayerInput.AbsoluteMovement(rider.x,rider.y,rider.z));
                fixture.store().tick(.05f);
                var actual=fixture.store().getComponent(board,TransformComponent.getComponentType()).getPosition();
                var riderActual=fixture.store().getComponent(fixture.ref(),TransformComponent.getComponentType()).getPosition();
                require(actual.distanceSquared(expected)<1e-12&&riderActual.distanceSquared(rider)<1e-12,"Full native airborne tick preserves mount/rider: board="+actual+" rider="+riderActual);
                require(fixture.store().getComponent(fixture.ref(),Teleport.getComponentType())==null&&fixture.packets().ofType(ClientTeleport.class).size()==teleportsBefore,"Airborne native systems cannot add a rider teleport");
            }
            MountPlugin.checkDismountNpc(fixture.store(),fixture.ref(),fixture.player());
            mobility.tick(world,.05);
            require(world.getEntityStore().getRefFromUUID(boardId)==null,"Native dismount folds and removes owned entity");
            for(int i=0;i<8&&countBoards(fixture.inventory())==0;i++){fixture.save();mobility.tick(world,.05);}
            fixture.save();mobility.tick(world,.05);
            require(countBoards(fixture.inventory())==1,"Native dismount returns exactly one physical item");
            ItemStack returned=board(fixture.inventory());assertPayload(original,returned);
            require(returned.equals(fixture.hotbar().getItemStack((short)0)),"Open hotbar receives the folded board ahead of empty storage");
            require(new HoverboardLedger(directory.resolve("live")).validAvailable(returned),"Completed native return save retires the receipt with usable current identity");
            mobility.cleanup(world);
        }
        verifyReturnSlots(world,directory.resolve("return-slots"),original);
        System.out.println("NATIVE_MOBILITY_REVISION_PASSED: feet-origin mount model/collider/anchor/spawn, actual player save-gated consumption/return, native mount input plus complete ECS airborne ticks with no board/rider snap or teleport, full metadata, entity cleanup, full inventory and restart nonce replay boundaries.");
    }
    private static void verifyReturnSlots(World world,Path directory,ItemStack original)throws Exception{
        try(var fixture=NativePlayerFixture.create(world,"NativeHoverboardReturnSlots",new Vector3d(16.5,17,16.5))){
            var storage=fixture.store().getComponent(fixture.ref(),InventoryComponent.Storage.getComponentType()).getInventory();
            for(int scenario=0;scenario<3;scenario++){
                var inventory=fixture.inventory();
                for(short slot=0;slot<inventory.getCapacity();slot++)inventory.setItemStackForSlot(slot,new ItemStack("Rock_Stone",100),false);
                var recovery=new HoverboardRecovery(directory.resolve(Integer.toString(scenario)));
                var receipt=recovery.ledger().prepare(fixture.owner().getUuid(),original);
                require(recovery.ledger().reserve(receipt.id)&&recovery.ledger().mounted(receipt.id),"Owned consumed board has a durable return receipt");
                recovery.fold(receipt.id);
                if(scenario<2)storage.setItemStackForSlot((short)0,ItemStack.EMPTY,false);
                if(scenario==0)fixture.hotbar().setItemStackForSlot((short)6,ItemStack.EMPTY,false);
                recovery.tick(world,(owner,id)->{throw new AssertionError("Return must not redeploy");},(owner,message)->{});
                if(scenario==2){
                    require(countBoards(fixture.inventory())==0&&recovery.ledger().pendingReturns(fixture.owner().getUuid()).size()==1,
                            "Full native inventory retains the receipt without adding or dropping a board");
                    fixture.save();
                    recovery=new HoverboardRecovery(directory.resolve(Integer.toString(scenario)));
                    fixture.hotbar().setItemStackForSlot((short)2,ItemStack.EMPTY,false);
                }
                for(int tick=0;tick<4;tick++){fixture.save();recovery.tick(world,(owner,id)->false,(owner,message)->{});}
                require(countBoards(fixture.inventory())==1,"Return and retry produce exactly one physical board");
                var expected=scenario==1?storage.getItemStack((short)0):fixture.hotbar().getItemStack((short)(scenario==0?6:2));
                require(!ItemStack.isEmpty(expected)&&HoverboardLedger.ITEM.equals(expected.getItemId()),
                        "Return uses an open hotbar slot, storage only when hotbar is full, and hotbar after full-inventory restart: scenario="+scenario+" actual="+expected);
                assertPayload(original,expected);
                require(recovery.ledger().validAvailable(expected),"Preferred slot return remains saved and usable");
            }
        }
    }
    private static void verifyReceiptBoundaries(Path directory,ItemStack original)throws Exception{
        UUID owner=UUID.randomUUID();var ledger=new HoverboardLedger(directory);
        var preparing=ledger.prepare(owner,original);String oldToken=preparing.token();
        var stamped=HoverboardLedger.decode(preparing.item);assertPayload(original,stamped);
        ledger=new HoverboardLedger(directory);
        require(ledger.validAvailable(stamped)&&ledger.pendingReturns(owner).isEmpty(),"Restart before consumption releases preparation without minting a second board");
        var reserved=ledger.prepare(owner,stamped);require(ledger.reserve(reserved.id),"Durable consume intent is recorded");
        ledger=new HoverboardLedger(directory);var recovered=ledger.pendingReturns(owner).getFirst();
        require(!oldToken.equals(recovered.token())&&ledger.prepare(owner,stamped)==null,"Restart after consume intent rotates nonce and blocks stale saved copies");
        var full=new SimpleItemContainer((short)1);full.setItemStackForSlot((short)0,new ItemStack("Rock_Stone",100),false);
        require(!HoverboardRecovery.reconcile(full,recovered),"Full inventory keeps return receipt pending without dropping the item");
        full.setItemStackForSlot((short)0,stamped,false);
        require(HoverboardRecovery.reconcile(full,recovered),"Old saved inventory copy is reconciled into exact current board");
        require(countBoards(full)==1,"Old/current receipt reconciliation cannot duplicate the board");
        assertPayload(original,full.getItemStack((short)0));ledger.returned(recovered.id);
        require(ledger.prepare(UUID.randomUUID(),stamped)==null,"Retired nonce remains unusable even if a stale copy moved to another owner");
        var current=full.getItemStack((short)0);require(ledger.validAvailable(current),"Current returned board can be deployed again");
        // Native ItemContainer CAS must compare all metadata and quantity under its write lock.
        require(!HoverboardRecovery.replaceExact(full,(short)0,original,ItemStack.EMPTY),"Stale pre-stamp inventory snapshot cannot consume the current board");
    }
    private static int countBoards(com.hypixel.hytale.server.core.inventory.container.ItemContainer inventory){int count=0;for(short i=0;i<inventory.getCapacity();i++){var item=inventory.getItemStack(i);if(!ItemStack.isEmpty(item)&&HoverboardLedger.ITEM.equals(item.getItemId()))count+=item.getQuantity();}return count;}
    private static ItemStack board(com.hypixel.hytale.server.core.inventory.container.ItemContainer inventory){for(short i=0;i<inventory.getCapacity();i++){var item=inventory.getItemStack(i);if(!ItemStack.isEmpty(item)&&HoverboardLedger.ITEM.equals(item.getItemId()))return item;}throw new AssertionError("Board absent");}
    private static void assertPayload(ItemStack expected,ItemStack actual){
        var a=ItemStack.CODEC.encode(expected,new com.hypixel.hytale.codec.ExtraInfo()).asDocument();var b=org.bson.BsonDocument.parse(ItemStack.CODEC.encode(actual,new com.hypixel.hytale.codec.ExtraInfo()).asDocument().toJson());
        b.getDocument("Metadata").remove(HoverboardLedger.TOKEN);
        require(a.equals(b),"Board return preserves every original BSON field, metadata and durability");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
