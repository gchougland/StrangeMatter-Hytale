package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.*;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerBlockWindow;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.*;
import com.hypixel.hytale.server.core.universe.world.connectedblocks.ConnectedBlocksUtil;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.bson.*;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Real block holders, native joined chest lifecycle, typed item data and collision regressions. */
final class NativeGraviticChestVerification {
    static void verify(World world)throws Exception {
        clear(world);
        try(var player=NativePlayerFixture.create(world,"ChestTransport",new Vector3d(18.5,184,18.5))){
            verifyClearance(world);
            verifyChest(world,player,false);
            verifyChest(world,player,true);
            verifyReplayBoundaries(world,player);
        } finally {clear(world);}
        System.out.println("NATIVE_GRAVITIC_CHEST_VERIFICATION_PASSED: four-side extraction and sealed ceilings, full native small/joined chest inventory and BSON types, rotated footprint and filler targeting, closed windows, checkpoint quarantine, occupied placement, native holder reload and crash recovery without replay.");
    }
    private static void verifyClearance(World world){
        var store=world.getEntityStore().getStore();var source=new Vector3d(23.5,184.5,23.5);
        for(var offset:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})world.setBlock(23+offset[0],184,23+offset[1],"Rock_Stone");
        var position=new Vector3d(source);var goal=new Vector3d(26.5,187.5,26.5);
        for(int tick=0;tick<18;tick++){
            var delta=new Vector3d(goal).sub(position);if(delta.length()>.6)delta.normalize(.6);
            position=AdvancedGadgetTargeting.carryMove(store,GraviticBlockJournal.BOX,position,delta);
            require(position.y>=185.49||Math.abs(position.x-23.5)<.02&&Math.abs(position.z-23.5)<.02,"Extraction never enters surrounding solid cubes: "+position);
        }
        require(position.distance(goal)<.1,"A four-side surrounded block rises and follows diagonal aim without manual sideways adjustment: "+position);
        world.setBlock(23,185,23,"Rock_Stone");position.set(source);
        for(int tick=0;tick<20;tick++)position=AdvancedGadgetTargeting.carryMove(store,GraviticBlockJournal.BOX,position,new Vector3d(.4,.4,.4));
        require(position.distance(source)<.03,"A sealed ceiling and four sides cannot be tunneled through: "+position);
        world.setBlock(23,185,23,"Empty");for(var offset:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})world.setBlock(23+offset[0],184,23+offset[1],"Empty");
    }
    private static void verifyChest(World world,NativePlayerFixture player,boolean joined)throws Exception{
        var source=new Vector3i(joined?32:23,184,23);int rotation=joined?0:RotationTuple.of(Rotation.Ninety,Rotation.None).index();
        List<ItemStack> expected;
        if(joined){
            var first=new Vector3i(source).add(-1,0,0);place(world,first,"SM_Resonite_Chest",0);place(world,source,"SM_Resonite_Chest",0);
            expected=new ArrayList<>(fill(world,first,100));expected.addAll(fill(world,source,200));
            var ref=world.getChunkStore().getChunkSectionReferenceAtBlock(source.x,source.y,source.z);
            ConnectedBlocksUtil.setConnectedBlockAndNotifyNeighbors(world.getChunkStore(),BlockType.getAssetMap().getIndex("SM_Resonite_Chest"),RotationTuple.NONE,new Vector3i(0,1,0),source,ref,ref.getStore().getComponent(ref,BlockSection.getComponentType()));
            require(world.getBlockType(source.x,source.y,source.z).getId().equals("SM_Resonite_Chest_Large"),"Fixture really joins native chest inventories across two loaded columns");
            expected=snapshotContents(world,source);require(expected.size()==36,"Joined native chest retains both eighteen-slot containers");
        }else{place(world,source,"SM_Resonite_Chest",rotation);expected=fill(world,source,0);}
        var original=GraviticChestTransport.container(world,source);var oldInventory=original.getItemContainer();
        var window=new ContainerBlockWindow(source.x,source.y,source.z,rotation,world.getBlockType(source.x,source.y,source.z),oldInventory);
        require(player.player().getWindowManager().openWindow(player.ref(),window,player.store())!=null,"Native chest window opens before pickup");original.getWindows().put(player.owner().getUuid(),window);int oldWindow=window.getId();
        var directory=Files.createTempDirectory("sm-native-chest-");var gate=new AtomicReference<>(new CompletableFuture<Void>());var journal=new GraviticBlockJournal(directory,w->gate.get());
        var contact=joined?new Vector3i(source).add(-1,0,0):source;
        journal.moveBlocked((w,p)->true);require(journal.begin(world,player.owner().getUuid(),contact)==null,"Unsettled automation receipt refuses chest capture");journal.moveBlocked((w,p)->false);
        require(GraviticBlockJournal.eligible(world,contact),"Native chest is capturable through its actual aimed filler cell");
        var id=journal.begin(world,player.owner().getUuid(),contact);require(id!=null,"Chest payload is durably journaled before native intact removal");
        var receipt=journal.receipt(id);require(receipt.source().pos().equals(source)&&receipt.rotation()==rotation,"Resolved native origin and rotation persist");
        for(var cell:receipt.cells(receipt.source()))require(world.getBlock(cell.x(),cell.y(),cell.z())==0&&journal.reserved(world,cell.pos()),"Every chest footprint cell is removed and temporarily reserved");
        require(oldInventory.isEmpty()&&player.player().getWindowManager().getWindow(oldWindow)==null&&original.getWindows().isEmpty(),"Native pickup closes windows and invalidates the stale inventory object");
        var decoded=GraviticChestTransport.decode(receipt.chest());assertContents(decoded.getComponent(ItemContainerBlock.getComponentType()),expected,"Native extended BSON receipt");
        var visual=journal.visual(world,id);require(visual!=null&&visual.isValid(),"Chest uses the native block visual");
        var box=player.store().getComponent(visual,BoundingBox.getComponentType()).getBoundingBox();require(joined?box.max.x-box.min.x>1.5:box.max.x-box.min.x<1.1,"Collider covers the actual native joined/small shape");
        gate.get().complete(null);journal.tick(world);require(journal.ready(id),"Chest moves only after source deletion checkpoint");
        // Fill every automatic placement candidate and the return address: retain ownership.
        var target=new Vector3d(27.5,184.5,18.5);var obstructed=GraviticBlockJournal.destinations(target);
        for(var cell:obstructed)world.setBlock(cell.x(),cell.y(),cell.z(),"Rock_Stone");world.setBlock(source.x,source.y,source.z,"Wood_Hardwood_Planks");
        require(!journal.place(world,id,player.ref(),target)&&journal.ready(id),"Occupied destinations and occupied source retain the full receipt");
        require("Wood_Hardwood_Planks".equals(world.getBlockType(source.x,source.y,source.z).getId()),"Occupied return address is never overwritten");
        for(var cell:obstructed)world.setBlock(cell.x(),cell.y(),cell.z(),"Empty");world.setBlock(source.x,source.y,source.z,"Empty");
        gate.set(new CompletableFuture<>());require(journal.place(world,id,player.ref(),target),"Authorized clear destination receives intact chest");
        var destination=journal.receipt(id).destination();assertContents(GraviticChestTransport.container(world,destination.pos()),expected,"Placed chest");
        require(GraviticChestMarker.locked(world,destination.pos()),"Destination carries persisted uncommitted ownership marker");
        for(var p:receipt.cells(destination))require(journal.reserved(world,p.pos()),"Every destination filler is protected during commit");
        var locked=GraviticChestTransport.container(world,destination.pos()).getItemContainer();require(!locked.removeItemStackFromSlot((short)0).succeeded(),"Native inventory extraction is refused before checkpoint");
        verifyTubeQuarantine(world,destination.pos(),true);
        // Real native holder decode/recreation drops runtime filters; LockOnLoad must restore them.
        reload(world,destination.pos());assertContents(GraviticChestTransport.container(world,destination.pos()),expected,"Native serialized holder reload");
        require(!GraviticChestTransport.container(world,destination.pos()).getItemContainer().removeItemStackFromSlot((short)0).succeeded(),"Native loaded uncommitted chest remains locked before journal tick");
        if(joined){
            // A partial disk checkpoint may restore the anchor column before its filler column.
            // Edit only the decoded block section; ordinary world removal correctly clears both.
            var filler=receipt.cells(destination).stream().filter(p->!p.equals(destination)).findFirst().orElseThrow();
            var section=world.getChunkStore().getChunkSectionReferenceAtBlock(filler.x(),filler.y(),filler.z());
            section.getStore().getComponent(section,BlockSection.getComponentType()).set(filler.x(),filler.y(),filler.z(),0,0,0);
        }
        var recovered=new GraviticBlockJournal(directory,w->gate.get());recovered.tick(world);
        assertContents(GraviticChestTransport.container(world,destination.pos()),expected,"Unacknowledged destination recovery");
        for(var p:receipt.cells(destination))require(destination.pos().equals(GraviticBlockJournal.origin(world,p.pos()))&&world.getBlock(p.x(),p.y(),p.z())!=0,"Recovery reconstructs every owned footprint fragment before retirement");
        gate.get().complete(null);recovered.tick(world);require(recovered.receipt(id)==null&&!GraviticChestMarker.locked(world,destination.pos()),"Only acknowledged destination and retired receipt unlock the native chest");
        verifyTubeQuarantine(world,destination.pos(),false);
        var restored=GraviticChestTransport.container(world,destination.pos());assertContents(restored,expected,"Committed native chest");
        require(restored.getItemContainer().removeItemStackFromSlot((short)0).succeeded(),"Committed inventory permits ordinary extraction");
        new GraviticBlockJournal(directory,w->CompletableFuture.completedFuture(null)).tick(world);
        require(ItemStack.isEmpty(restored.getItemContainer().getItemStack((short)0)),"Restart after retirement never replays already-extracted contents");
        restored.getItemContainer().clear();world.setBlock(destination.x(),destination.y(),destination.z(),"Empty");player.store().removeEntity(visual,RemoveReason.REMOVE);
    }
    private static void verifyReplayBoundaries(World world,NativePlayerFixture player)throws Exception{
        var source=new Vector3i(23,184,23);place(world,source,"SM_Resonite_Chest",0);var expected=fill(world,source,500);String payload=GraviticChestTransport.encode(GraviticChestTransport.snapshot(world,source));
        var receipt=new GraviticBlockJournal.Receipt(UUID.randomUUID(),player.owner().getUuid(),world.getName(),"SM_Resonite_Chest",0,new GraviticBlockJournal.Cell(source.x,source.y,source.z),null,GraviticBlockJournal.Phase.PREPARED,payload,List.of(new GraviticBlockJournal.Cell(0,0,0)));
        var directory=Files.createTempDirectory("sm-chest-prepared-");write(directory,receipt);
        var journal=new GraviticBlockJournal(directory,w->CompletableFuture.completedFuture(null));journal.tick(world);journal.tick(world);
        require(journal.receipt(receipt.id())==null,"Crash before source deletion reconciles the exact original holder");assertContents(GraviticChestTransport.container(world,source),expected,"Prepared original recovery");
        // Same chest model with emptied/changed inventory is not proof that this is our destination.
        write(directory,receipt);GraviticChestTransport.container(world,source).getItemContainer().clear();
        journal=new GraviticBlockJournal(directory,w->CompletableFuture.completedFuture(null));journal.tick(world);journal.tick(world);
        require(journal.receipt(receipt.id())!=null&&GraviticChestTransport.container(world,source).getItemContainer().isEmpty(),"A different empty same-model chest cannot receive a replayed inventory");
        world.setBlock(source.x,source.y,source.z,"Empty");journal.tick(world);journal.tick(world);assertContents(GraviticChestTransport.container(world,source),expected,"Deferred recovery after conflicting chest removed");
        GraviticChestTransport.container(world,source).getItemContainer().clear();world.setBlock(source.x,source.y,source.z,"Empty");
    }
    private static void write(Path directory,GraviticBlockJournal.Receipt receipt)throws Exception{Files.writeString(directory.resolve("gravitic-blocks.json"),new com.google.gson.Gson().toJson(new GraviticBlockJournal.Saved(2,UUID.randomUUID(),List.of(receipt))));}
    private static void reload(World world,Vector3i p){
        String data=GraviticChestTransport.encode(GraviticChestTransport.snapshot(world,p));var type=world.getBlockType(p.x,p.y,p.z);var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(p.x,p.z));int rotation=WorldAccess.rotation(chunk,p.x,p.y,p.z);
        require(GraviticChestTransport.detach(world,p),"Native chest can unload intact for persistence fixture");
        var ref=world.getChunkStore().getChunkSectionReferenceAtBlock(p.x,p.y,p.z);var store=world.getChunkStore().getStore();
        com.hypixel.hytale.server.core.modules.block.BlockEntity.setBlockEntity(store,ref,store.getComponent(ref,BlockComponentSection.getComponentType()),p.x,p.y,p.z,type,rotation,GraviticChestTransport.decode(data));
    }
    private static List<ItemStack> fill(World world,Vector3i p,int offset){
        var contents=GraviticChestTransport.container(world,p).getItemContainer();var expected=new ArrayList<ItemStack>();
        for(short slot=0;slot<contents.getCapacity();slot++){
            var data=new BsonDocument("smallLong",new BsonInt64(73+slot)).append("nested",new BsonDocument("sequence",new BsonInt32(offset+slot))).append("bytes",new BsonBinary(new byte[]{3,5,8}));
            var item=slot==0?GadgetEnergy.withCharge(new ItemStack("SM_Field_Scanner",1),937):new ItemStack("SM_Insight_Shard",slot%5+1);
            item=item.withMetadata("GraviticFixture",data);contents.setItemStackForSlot(slot,item,false);expected.add(item);
        }return expected;
    }
    private static List<ItemStack> snapshotContents(World world,Vector3i p){var container=GraviticChestTransport.container(world,p).getItemContainer();var out=new ArrayList<ItemStack>();for(short slot=0;slot<container.getCapacity();slot++)out.add(container.getItemStack(slot));return out;}
    private static void assertContents(ItemContainerBlock component,List<ItemStack> expected,String message){require(component!=null&&component.getItemContainer().getCapacity()==expected.size(),message+" capacity");for(short slot=0;slot<expected.size();slot++){var item=component.getItemContainer().getItemStack(slot);require(Objects.equals(item,expected.get(slot)),message+" exact stack/metadata at slot "+slot);require(item.getMetadata().getDocument("GraviticFixture").get("smallLong").isInt64(),message+" preserves BSON int64 type");}}
    private static void place(World world,Vector3i p,String id,int rotation){var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(p.x,p.z));require(chunk!=null,"Chest fixture column is loaded");WorldAccess.set(chunk,p.x,p.y,p.z,BlockType.getAssetMap().getIndex(id),BlockType.getAssetMap().getAsset(id),rotation,0,0);}
    private static void verifyTubeQuarantine(World world,Vector3i p,boolean locked)throws Exception{
        // Invoke the actual endpoint resolver rather than duplicating its lock predicate.
        var type=Class.forName("com.hexvane.strangematter.automation.TubeEndpoints");var ctor=type.getDeclaredConstructors()[0];ctor.setAccessible(true);var endpoints=ctor.newInstance(new Object[]{null});
        var position=Class.forName("com.hexvane.strangematter.automation.TubeEndpoints$Position");var positionCtor=position.getDeclaredConstructor(int.class,int.class,int.class);positionCtor.setAccessible(true);
        var all=type.getDeclaredMethod("all",World.class,position,boolean.class);all.setAccessible(true);var resolved=(List<?>)all.invoke(endpoints,world,positionCtor.newInstance(p.x,p.y,p.z),true);
        require(locked?resolved.isEmpty():!resolved.isEmpty(),"Actual tube endpoint resolver respects the durable chest lock");
    }
    private static void clear(World world){for(int x=20;x<=34;x++)for(int z=16;z<=27;z++)for(int y=183;y<=189;y++)world.setBlock(x,y,z,"Empty");}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
