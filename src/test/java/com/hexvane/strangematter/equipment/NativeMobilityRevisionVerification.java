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
import com.hypixel.hytale.server.core.io.ProtocolVersion;
import com.hypixel.hytale.server.core.io.handlers.game.GamePacketHandler;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
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
            UUID boardId=fixture.store().getComponent(board,UUIDComponent.getComponentType()).getUuid();
            require(fixture.store().getComponent(board,Frozen.getComponentType())!=null&&fixture.store().getComponent(board,StepComponent.getComponentType())==null,"Only NPC simulation is suspended for client-controlled mount");
            var handler=new GamePacketHandler(null,new ProtocolVersion(0),null);
            var steering=new SteeringSystem(NPCEntity.getComponentType());
            // Exercise the native packet path and then the exact NPC steering system which used
            // to overwrite the rider's downhill position. Not merely a MountNPC packet assertion.
            for(int step=0;step<5;step++){
                var expected=new Vector3d(16.5+step*.4,18.1-step*.25,16.5);
                var packet=new MountMovement();packet.absolutePosition=new Position(expected.x,expected.y,expected.z);
                packet.bodyOrientation=new Direction(0,0,0);packet.movementStates=new MovementStates();packet.movementStates.running=true;
                handler.handleMountMovement(packet,fixture.owner(),fixture.ref(),world,fixture.store());
                fixture.store().forEachChunk(steering.getQuery(),(chunk,commands)->{
                    for(int i=0;i<chunk.size();i++)if(chunk.getReferenceTo(i).equals(board))steering.tick(.05f,i,chunk,fixture.store(),commands);
                });
                var actual=fixture.store().getComponent(board,TransformComponent.getComponentType()).getPosition();
                require(actual.distanceSquared(expected)<1e-12,"Native NPC steering must preserve each rider downstep: actual="+actual+" expected="+expected);
            }
            MountPlugin.checkDismountNpc(fixture.store(),fixture.ref(),fixture.player());
            mobility.tick(world,.05);
            require(world.getEntityStore().getRefFromUUID(boardId)==null,"Native dismount folds and removes owned entity");
            for(int i=0;i<8&&countBoards(fixture.inventory())==0;i++){fixture.save();mobility.tick(world,.05);}
            fixture.save();mobility.tick(world,.05);
            require(countBoards(fixture.inventory())==1,"Native dismount returns exactly one physical item");
            ItemStack returned=board(fixture.inventory());assertPayload(original,returned);
            require(new HoverboardLedger(directory.resolve("live")).validAvailable(returned),"Completed native return save retires the receipt with usable current identity");
            mobility.cleanup(world);
        }
        System.out.println("NATIVE_MOBILITY_REVISION_PASSED: actual player save-gated consumption/return, full metadata and durability, native mount movement then NPC steering across five downhill positions, entity cleanup, full inventory and restart nonce replay boundaries.");
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
        var a=ItemStack.CODEC.encode(expected).asDocument();var b=org.bson.BsonDocument.parse(ItemStack.CODEC.encode(actual).asDocument().toJson());
        b.getDocument("Metadata").remove(HoverboardLedger.TOKEN);
        require(a.equals(b),"Board return preserves every original BSON field, metadata and durability");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
