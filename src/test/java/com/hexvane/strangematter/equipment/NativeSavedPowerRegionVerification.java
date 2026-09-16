package com.hexvane.strangematter.equipment;

import com.google.gson.GsonBuilder;
import com.hexvane.strangematter.automation.FactoryComponent;
import com.hexvane.strangematter.machine.MachineService;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.util.BsonUtil;
import com.hypixel.hytale.storage.IndexedStorageFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Optional snapshot evidence. Decoded holders are never installed in any world or registry. */
public final class NativeSavedPowerRegionVerification {
    private static final String PROPERTY="strangematter.powerSnapshotRegion";
    private static final int CHUNK_X=-50,CHUNK_Z=5;
    private NativeSavedPowerRegionVerification(){}

    public static CompletableFuture<Void> verifyAsync(){
        String configured=System.getProperty(PROPERTY,"");
        if(configured.isBlank())return CompletableFuture.completedFuture(null);
        // Native chunk decoding may resolve assets. As in BufferChunkLoader, it must run
        // outside the world thread to avoid taking an asset write lock beneath its read lock.
        return CompletableFuture.runAsync(()->{try{verify(Path.of(configured));}catch(Exception error){throw new CompletionException(error);}});
    }

    private record BlockEvidence(int x,int y,int z,int nativeId,String rawId,String defaultStateKey,
                                 String machineId,int filler,int rotation,boolean parkedHolder,String factoryPayload){}
    private record Report(String region,String sha256,int chunkX,int chunkZ,int regionSlot,
                          List<BlockEvidence> targets,List<BlockEvidence> neighborhoodMachines){}

    private static void verify(Path supplied)throws Exception{
        Path copy=supplied.toRealPath();
        Path permitted=Path.of("power-snapshot").toRealPath();
        require(copy.getParent().equals(permitted)&&copy.getFileName().toString().equals("-2.0.region.bin"),
                "Only this disposable run's exact copied power snapshot may be decoded");
        String digest=sha256(copy);
        require(Math.floorDiv(CHUNK_X,ChunkUtil.SIZE)==-2&&Math.floorDiv(CHUNK_Z,ChunkUtil.SIZE)==0,
                "Negative chunk coordinates select region -2.0");
        int slot=ChunkUtil.indexColumn(CHUNK_X&ChunkUtil.SIZE_MASK,CHUNK_Z&ChunkUtil.SIZE_MASK);
        require(ChunkUtil.xFromColumn(slot)==14&&ChunkUtil.zFromColumn(slot)==5,
                "Native region slot retains negative-coordinate local X14/Z5");
        Holder<ChunkStore> decoded;
        // IndexedStorageFile always maps its index READ_WRITE. Only the disposable copy
        // is opened; no write methods are called, and its digest must remain identical.
        try(var region=IndexedStorageFile.open(copy,StandardOpenOption.READ,StandardOpenOption.WRITE)){
            var bytes=region.readBlob(slot);require(bytes!=null,"Saved region contains the failing chunk (-50,5)");
            decoded=ChunkStore.REGISTRY.deserialize(BsonUtil.readFromBuffer(bytes));
        }
        require(digest.equals(sha256(copy)),"Native region read leaves the copied source bytes unchanged");
        var column=decoded.getComponent(ChunkColumn.getComponentType());
        require(column!=null&&column.getSectionHolders()!=null,"Saved chunk decodes its native section holders");
        var targets=new ArrayList<BlockEvidence>();
        int[][] points={{-1579,123,179},{-1578,123,179},{-1580,123,179},{-1579,123,178},
                {-1578,123,178},{-1577,123,179},{-1580,123,180},{-1577,122,177}};
        for(var point:points){var row=read(column,point[0],point[1],point[2]);targets.add(row);
            System.out.println("SAVED_POWER_BLOCK: "+row.x+","+row.y+","+row.z+" raw="+row.rawId+
                    " base="+row.machineId+" filler="+row.filler+" rotation="+row.rotation+" factory="+(row.factoryPayload!=null));}
        require(targets.get(3).machineId.equals("SM_Rift_Stabilizer"),"Snapshot reproduces stabilizer A at the saved coordinates");
        require(targets.get(4).machineId.equals("SM_Resonance_Condenser"),"Snapshot reproduces the paused condenser location");
        require(targets.get(6).machineId.equals("SM_Resonant_Energy_Storage"),"Snapshot reproduces the nonfull storage location");
        require(targets.get(7).machineId.equals("SM_Rift_Stabilizer"),"Snapshot reproduces stabilizer B at the saved coordinates");
        require(targets.get(6).parkedHolder&&targets.get(6).factoryPayload!=null,
                "Storage's authoritative native Factory data survives detached holder decoding");
        var neighbors=new ArrayList<BlockEvidence>();
        for(int x=-1583;x<=-1574;x++)for(int y=120;y<=126;y++)for(int z=175;z<=182;z++){
            var row=read(column,x,y,z);if(MachineService.IDS.contains(row.machineId))neighbors.add(row);
        }
        require(neighbors.size()>=8,"Snapshot contains the surrounding saved power installation");
        var report=new Report(copy.getFileName().toString(),digest,CHUNK_X,CHUNK_Z,slot,List.copyOf(targets),List.copyOf(neighbors));
        Files.writeString(Path.of("saved-power-region-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("NATIVE_SAVED_POWER_REGION_VERIFICATION_PASSED: native region/BSON/section/Factory decode; "+neighbors.size()+
                " nearby machine cells; bridges="+targets.get(0).machineId+","+targets.get(1).machineId+"; detached holders only; sha256="+digest);
    }

    private static BlockEvidence read(ChunkColumn column,int x,int y,int z){
        require(Math.floorDiv(x,ChunkUtil.SIZE)==CHUNK_X&&Math.floorDiv(z,ChunkUtil.SIZE)==CHUNK_Z,"Probe stays within its one decoded chunk");
        var sections=column.getSectionHolders();int sectionIndex=ChunkUtil.indexSection(y);
        require(sectionIndex>=0&&sectionIndex<sections.length&&sections[sectionIndex]!=null,"Target Y section exists in saved holder");
        var holder=sections[sectionIndex];var section=holder.getComponent(BlockSection.getComponentType());
        require(section!=null,"Saved section contains a native block palette");
        int id=section.get(x,y,z),index=ChunkUtil.indexBlock(x,y,z);
        require(id==section.get(x&ChunkUtil.SIZE_MASK,y&ChunkUtil.SIZE_MASK,z&ChunkUtil.SIZE_MASK),
                "Native global and local block addressing agree at "+x+","+y+","+z);
        var type=BlockType.getAssetMap().getAsset(id);require(type!=null,"Saved block resolves to a native asset");
        var components=holder.getComponent(BlockComponentSection.getComponentType());
        var parked=components==null?null:components.getBlockHolder(index);
        require(components==null||components.getBlockReference(index)==null,"Detached snapshot has no live block entity references");
        var factory=parked==null?null:parked.getComponent(FactoryComponent.getComponentType());
        String payload=factory==null?null:FactoryComponent.CODEC.encode(factory,new ExtraInfo()).asDocument().toJson();
        return new BlockEvidence(x,y,z,id,type.getId(),type.getDefaultStateKey(),MachineService.baseId(type),
                section.getFiller(index),section.getRotationIndex(x,y,z),parked!=null,payload);
    }
    private static String sha256(Path file)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(file)){byte[] bytes=new byte[65536];for(int count;(count=input.read(bytes))!=-1;)digest.update(bytes,0,count);}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
