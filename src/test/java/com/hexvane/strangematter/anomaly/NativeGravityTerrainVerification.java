package com.hexvane.strangematter.anomaly;

import com.hypixel.hytale.protocol.CollisionType;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollision;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollisionConfig;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.event.events.ecs.*;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Real world excavation, native block/collider entities and journal recovery across save boundaries. */
public final class NativeGravityTerrainVerification {
    public static void verify(World world)throws Exception {
        var store=world.getEntityStore().getStore();var directory=Files.createTempDirectory("sm-native-gravity-terrain-");
        var save=new CompletableFuture<Void>();var terrain=new GravityTerrain(directory,w->save);
        var source=new AnomalyRecord(UUID.fromString("00000000-0000-0000-0000-000000000000"),AnomalyType.GRAVITY,world.getName(),new Vector3d(20,80,20),true);
        for(int x=12;x<=27;x++)for(int z=12;z<=27;z++)world.setBlock(x,78,z,"Rock_Stone");
        var config=HitboxCollisionConfig.getAssetMap().getAsset(GravityTerrain.COLLIDER);require(config!=null,"Native solid terrain config loaded");
        var wire=config.toPacket();var bytes=MemorySegment.ofArray(new byte[wire.computeSize()]);wire.serialize(bytes,0);
        var decoded=com.hypixel.hytale.protocol.HitboxCollisionConfig.toObject(bytes);
        require(decoded.collisionType==CollisionType.Hard&&decoded.allowEntityAnchoring&&!decoded.rotateAnchoredEntities,"Native wire enables hard collision and anchored riders without rotating player view");
        try {
            terrain.tick(world,List.of(source),.05);
            var receipts=terrain.receipts();var parts=terrain.parts();
            require(receipts.size()==8&&parts.size()==8&&receipts.stream().allMatch(r->r.cells().size()==1),"Eight separate natural blocks are lifted, each with its own restoration receipt");
            var ids=new HashSet<Integer>();
            for(var ref:parts){
                require(ref.isValid()&&store.getComponent(ref,BlockEntity.getComponentType())!=null,"Floating cell is a real native block entity");
                require(store.getComponent(ref,HitboxCollision.getComponentType())!=null&&store.getComponent(ref,BoundingBox.getComponentType())!=null,"Every visible block has its matching solid collision");
                require(store.getComponent(ref,Velocity.getComponentType())==null,"Kinematic platform is excluded from native falling-block integration");
                require(store.getComponent(ref,EntityStore.REGISTRY.getNonSerializedComponentType())!=null,"Presentation entity cannot survive independently of its terrain receipt");
                require(ids.add(store.getComponent(ref,NetworkId.getComponentType()).getId()),"Native allocated entity IDs are unique");
            }
            for(var r:receipts)for(var cell:r.cells())require(world.getBlock(cell.x(),cell.y(),cell.z())==0,"Lift moves the real source instead of duplicating blocks");
            var first=parts.getFirst();var initial=new Vector3d(store.getComponent(first,TransformComponent.getComponentType()).getPosition());
            for(int i=0;i<100;i++){terrain.tick(world,List.of(source),.05);store.tick(.05f);}
            var position=store.getComponent(first,TransformComponent.getComponentType()).getPosition();
            require(position.y>initial.y+1&&position.distance(initial)<4.2,"Native ECS preserves smoothly lifted pieces instead of applying falling physics");
            double lowest=parts.stream().mapToDouble(r->store.getComponent(r,TransformComponent.getComponentType()).getPosition().y).min().orElseThrow();
            double highest=parts.stream().mapToDouble(r->store.getComponent(r,TransformComponent.getComponentType()).getPosition().y).max().orElseThrow();
            require(highest-lowest>.65,"Separate blocks occupy distinct floating heights instead of a single flat platform");
            require(parts.stream().allMatch(r->{var rotation=store.getComponent(r,TransformComponent.getComponentType()).getRotation();return Math.abs(rotation.pitch())+Math.abs(rotation.roll())>.001;}),"Every lifted native block has a gentle real transform tilt");
            double low=position.y,high=position.y,previous=position.y;boolean rose=false,sank=false;
            for(int i=0;i<100;i++){
                terrain.tick(world,List.of(source),.05);store.tick(.05f);
                double current=store.getComponent(first,TransformComponent.getComponentType()).getPosition().y;
                low=Math.min(low,current);high=Math.max(high,current);rose|=current>previous+.0001;sank|=current<previous-.0001;previous=current;
            }
            require(high-low>.45&&rose&&sank,"Actual native solid terrain completes both phases of its gentle sine bob after the initial lift");
            var blocked=receipts.getFirst().cells().getFirst();world.setBlock(blocked.x(),blocked.y(),blocked.z(),"Wood_Hardwood_Planks");
            terrain.remove(world,source.id);
            require(parts.stream().noneMatch(r->r.isValid()),"Removing the field removes all owned moving entities");
            require(world.getBlockType(blocked.x(),blocked.y(),blocked.z()).getId().equals("Wood_Hardwood_Planks"),"Return preserves construction placed in the old hole");
            require(terrain.receipts().size()==8,"No receipt retires before the terrain save acknowledges");
            var neighbor=receipts.get(1).cells().getFirst();
            require(world.getBlockType(neighbor.x(),neighbor.y(),neighbor.z()).getId().equals(neighbor.block()),"An occupied single-block return does not strand independent pieces");
            world.setBlock(blocked.x(),blocked.y(),blocked.z(),"Empty");terrain.tick(world,List.of(),.05);
            for(var r:receipts)for(var cell:r.cells())require(world.getBlockType(cell.x(),cell.y(),cell.z()).getId().equals(cell.block()),"All source blocks restore exactly once the footprint is clear");
            var checkpointCell=receipts.getFirst().cells().getFirst();var checkpointPos=new Vector3i(checkpointCell.x(),checkpointCell.y(),checkpointCell.z());
            var checkpointBlock=world.getBlockType(checkpointPos.x,checkpointPos.y,checkpointPos.z);
            var breaking=new BreakBlockEvent(null,checkpointPos,checkpointBlock);
            new GravityTerrainEvents.Break(terrain).handle(0,null,store,null,breaking);
            var damaging=new DamageBlockEvent(null,checkpointPos,checkpointBlock,0,1);
            new GravityTerrainEvents.Damage(terrain).handle(0,null,store,null,damaging);
            require(breaking.isCancelled()&&damaging.isCancelled(),"Native break and damage handlers protect restored cells until the save checkpoint completes");
            save.complete(null);terrain.tick(world,List.of(),.05);require(terrain.receipts().isEmpty(),"Acknowledged native terrain snapshot permits durable receipt retirement");
            breaking=new BreakBlockEvent(null,checkpointPos,checkpointBlock);new GravityTerrainEvents.Break(terrain).handle(0,null,store,null,breaking);
            require(!breaking.isCancelled(),"Receipt protection ends after acknowledged retirement");
            var cornerReceipt=new GravityTerrain.Receipt(UUID.randomUUID(),source.id,world.getName(),13,84,13,List.of(new GravityTerrain.Cell(13,84,13,"Rock_Stone")));
            world.setBlock(14,84,13,"Rock_Stone");
            try{
                require(GravityTerrain.clear(world,cornerReceipt,new Vector3d(),new com.hypixel.hytale.math.vector.Rotation3f()),"Upright cube can touch an adjacent solid block without overlap");
                require(!GravityTerrain.clear(world,cornerReceipt,new Vector3d(),new com.hypixel.hytale.math.vector.Rotation3f(.1f,.1f,.08f)),"Rotated-corner collision bounds prevent a tilted block entering adjacent terrain");
            }finally{world.setBlock(14,84,13,"Empty");}

            var legacyDirectory=Files.createTempDirectory("sm-native-gravity-legacy-");
            var legacyCells=List.of(new GravityTerrain.Cell(12,78,12,"Rock_Stone"),new GravityTerrain.Cell(13,78,12,"Rock_Stone"),new GravityTerrain.Cell(12,78,13,"Rock_Stone"),new GravityTerrain.Cell(13,78,13,"Rock_Stone"));
            var legacyReceipt=new GravityTerrain.Receipt(UUID.randomUUID(),source.id,world.getName(),20,80,20,legacyCells);
            Files.writeString(legacyDirectory.resolve("gravity-terrain.json"),new com.google.gson.Gson().toJson(new GravityTerrain.Saved(1,List.of(legacyReceipt),Map.of())));
            for(var c:legacyCells)world.setBlock(c.x(),c.y(),c.z(),"Empty");
            var legacy=new GravityTerrain(legacyDirectory,w->CompletableFuture.completedFuture(null));legacy.tick(world,List.of(),.05);legacy.tick(world,List.of(),.05);
            require(legacy.receipts().isEmpty()&&legacyCells.stream().allMatch(c->c.block().equals(world.getBlockType(c.x(),c.y(),c.z()).getId())),"Version 1 four-cell receipts from previous releases still restore and retire safely");

            // Simulate restart after the excavation reached disk, before any return was saved.
            var failed=new GravityTerrain(directory,w->CompletableFuture.failedFuture(new java.io.IOException("fixture save failure")));
            failed.tick(world,List.of(source),.05);var pending=failed.receipts();require(!pending.isEmpty(),"Fresh lift writes new durable receipts");
            failed.stopWorld(world);failed.tick(world,List.of(),.05);
            require(!new GravityTerrain(directory,w->CompletableFuture.completedFuture(null)).receipts().isEmpty(),"Failed saves keep recoverable receipts on disk");
            for(var r:pending)for(var cell:r.cells())world.setBlock(cell.x(),cell.y(),cell.z(),"Empty");
            var recovered=new GravityTerrain(directory,w->CompletableFuture.completedFuture(null));recovered.tick(world,List.of(),.05);recovered.tick(world,List.of(),.05);
            require(recovered.receipts().isEmpty(),"Restart restores saved holes and retires only acknowledged recovery");
            for(var r:pending)for(var cell:r.cells())require(world.getBlockType(cell.x(),cell.y(),cell.z()).getId().equals(cell.block()),"Original terrain survives restart without serialized duplicate entities");
            source.released=true;recovered.tick(world,List.of(source),9);require(recovered.parts().isEmpty(),"Relocated fields do not excavate laboratories");
            source.released=false;
            var retryDirectory=Files.createTempDirectory("sm-native-gravity-retry-");var failure=new CompletableFuture<Void>();
            var retryTerrain=new GravityTerrain(retryDirectory,w->failure);retryTerrain.tick(world,List.of(source),.05);
            var owed=retryTerrain.receipts();require(!owed.isEmpty(),"Retry fixture owns actual excavated terrain");
            retryTerrain.stopWorld(world);failure.completeExceptionally(new java.io.IOException("fixture checkpoint failure"));retryTerrain.tick(world,List.of(),.05);
            var consumed=owed.getFirst().cells().getFirst();var consumedPos=new Vector3i(consumed.x(),consumed.y(),consumed.z());
            var consumedType=world.getBlockType(consumed.x(),consumed.y(),consumed.z());
            world.setBlock(consumed.x(),consumed.y(),consumed.z(),"Empty");retryTerrain.tick(world,List.of(),.05);
            require(world.getBlock(consumed.x(),consumed.y(),consumed.z())==0,"Failed save retry does not replenish a cell already restored in this process");
            new GravityTerrainEvents.EnvironmentBreak(retryTerrain).handle(store,null,new EnvironmentBreakBlockEvent(consumedPos,consumedType));
            var afterDestruction=new GravityTerrain(retryDirectory,w->CompletableFuture.completedFuture(null));
            require(afterDestruction.receipts().stream().flatMap(r->r.cells().stream()).noneMatch(c->c.x()==consumed.x()&&c.y()==consumed.y()&&c.z()==consumed.z()),"Environmental consumption is removed from the durable receipt before restart");
            afterDestruction.tick(world,List.of(),.05);afterDestruction.tick(world,List.of(),.05);
            require(world.getBlock(consumed.x(),consumed.y(),consumed.z())==0,"Restart cannot duplicate an environmentally consumed restored block");
            world.setBlock(consumed.x(),consumed.y(),consumed.z(),consumed.block());

            var placedDirectory=Files.createTempDirectory("sm-native-gravity-placed-");var edited=new GravityTerrain(placedDirectory,w->CompletableFuture.completedFuture(null));
            var placementHandler=new GravityTerrainEvents.Place(edited);
            for(var r:receipts){var c=r.cells().getFirst();var event=new PlaceBlockEvent(new ItemStack(c.block(),1),new Vector3i(c.x(),c.y(),c.z()),RotationTuple.NONE);placementHandler.handle(0,null,store,null,event);require(!event.isCancelled(),"Raw natural block placement is allowed and recorded");}
            var editedReloaded=new GravityTerrain(placedDirectory,w->CompletableFuture.completedFuture(null));editedReloaded.tick(world,List.of(source),.05);
            require(editedReloaded.parts().isEmpty(),"Native player placements remain excluded from terrain lifting after receipt-file reload");
            var built=new GravityTerrain(Files.createTempDirectory("sm-native-gravity-built-"),w->CompletableFuture.completedFuture(null));
            for(var r:receipts){var c=r.cells().getFirst();world.setBlock(c.x()-1,c.y()+1,c.z(),"Wood_Hardwood_Planks");}
            try{built.tick(world,List.of(source),.05);require(built.parts().isEmpty(),"Existing processed construction adjacent to a raw patch prevents excavation");}
            finally{built.stopWorld(world);for(var r:receipts){var c=r.cells().getFirst();world.setBlock(c.x()-1,c.y()+1,c.z(),"Empty");}}
        }finally {terrain.stopWorld(world);for(int x=12;x<=27;x++)for(int z=12;z<=27;z++)world.setBlock(x,78,z,"Empty");}
        System.out.println("NATIVE_GRAVITY_TERRAIN_VERIFICATION_PASSED: real bounded excavation and native collidable moving entities, anchoring wire, native physics exclusion, smooth lift, occupied footprint protection, save acknowledgements, native break/damage checkpoint guards, failed-save no-replenishment, environmental receipt consumption, restart recovery, durable player-edit exclusions, processed-neighbor and relocated field guards.");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
