package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.anomaly.*;
import com.hypixel.hytale.codec.Codec;
import org.bson.*;
import org.bson.json.*;
import org.joml.Vector3d;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Durable payload, acknowledgement-boundary, nonce replay and invalid-ledger regression checks. */
public final class CapsuleFlightVerification {
    public static void main(String[] args)throws Exception {
        Path directory=Files.createTempDirectory("sm-flight-test-");UUID anomaly=UUID.randomUUID(),nonce=UUID.randomUUID();String token=anomaly+":"+nonce;
        var metadata=new BsonDocument("SMAnomaly",new BsonString(token))
            .append("Nested",new BsonDocument("binary",new BsonBinary((byte)4,new byte[16])).append("smallLong",new BsonInt64(7)).append("values",new BsonArray(List.of(new BsonDouble(.25),new BsonBoolean(true)))))
            .append("customName",new BsonString("Contained field Ω"));
        var bson=new BsonDocument("Id",new BsonString("SM_Containment_Capsule_Gravity")).append("Quantity",new BsonInt32(1))
            .append("Durability",new BsonDouble(7.25)).append("MaxDurability",new BsonDouble(60)).append("Quality",new BsonInt32(3))
            .append("OverrideDroppedItemAnimation",new BsonBoolean(true)).append("Metadata",metadata);
        String encoded=bson.toJson(JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build());
        var item=LaboratoryProjectiles.decodeCapsule(encoded);
        var recovered=LaboratoryProjectiles.decodeCapsule(LaboratoryProjectiles.encodeCapsule(item));
        require(recovered.getMetadata().equals(metadata),"All nested BSON metadata types survive native ItemStack round trip");
        require(recovered.getDurability()==7.25&&recovered.getMaxDurability()==60&&recovered.getQualityIndex()==3,"Durability and quality survive persistence");
        require(BsonDocument.parse(LaboratoryProjectiles.encodeCapsule(recovered)).getBoolean("OverrideDroppedItemAnimation").getValue(),"Native item presentation metadata survives");
        require(LaboratoryProjectiles.sameToken(recovered,token)&&!LaboratoryProjectiles.sameToken(recovered,UUID.randomUUID()+":"+nonce),"Recovery reconciles the exact token, not an item ID");

        var shot=new LaboratoryProjectiles.Shot();shot.id=UUID.randomUUID();shot.owner=UUID.randomUUID();shot.world="verification";shot.token=token;shot.item=encoded;shot.type=AnomalyType.GRAVITY;
        require(LaboratoryProjectiles.requiresInventoryAcknowledgement(shot),"Survival queue retains the native inventory acknowledgement boundary");
        shot.creative=true;require(!LaboratoryProjectiles.requiresInventoryAcknowledgement(shot),"Unchanged creative inventory does not block a legitimate throw on player storage");shot.creative=false;
        shot.position=new Vector3d(18.25,150.5,-30.125);shot.velocity=new Vector3d(3,-6.5,19);shot.age=2.75;shot.phase=LaboratoryProjectiles.Phase.PREPARED;
        Path file=directory.resolve("capsule-flights.json");LaboratoryProjectiles.writeFlights(file,List.of(shot));
        var saved=LaboratoryProjectiles.readFlights(file).getFirst();
        require(saved.id.equals(shot.id)&&saved.owner.equals(shot.owner)&&saved.world.equals(shot.world)&&saved.position.equals(shot.position)&&saved.velocity.equals(shot.velocity)&&saved.age==shot.age,"Flight identity, owner, world and trajectory survive restart");
        require(saved.item.equals(encoded)&&!saved.reconciled&&saved.inventorySave==null&&saved.visual==null,"Only durable state is loaded; native visual and inventory reconciliation restart safely");
        saved.inventorySave=new CompletableFuture<>();
        require(LaboratoryProjectiles.pollInventorySave(saved)==LaboratoryProjectiles.SaveResult.WAIT&&saved.phase==LaboratoryProjectiles.Phase.PREPARED,"No flight before required inventory save acknowledges");
        saved.inventorySave.complete(null);
        require(LaboratoryProjectiles.pollInventorySave(saved)==LaboratoryProjectiles.SaveResult.FLIGHT&&saved.reconciled,"Acknowledged consumption permits flight");
        saved.phase=LaboratoryProjectiles.Phase.REFUND;saved.inventorySave=new CompletableFuture<>();
        require(LaboratoryProjectiles.pollInventorySave(saved)==LaboratoryProjectiles.SaveResult.WAIT,"Refund receipt cannot retire before inventory durability");
        saved.inventorySave.completeExceptionally(new java.io.IOException("simulated disk failure"));
        require(LaboratoryProjectiles.pollInventorySave(saved)==LaboratoryProjectiles.SaveResult.RETRY&&!saved.reconciled&&saved.phase==LaboratoryProjectiles.Phase.REFUND,"Failed save retains recovery state and retries");
        saved.inventorySave=CompletableFuture.completedFuture(null);
        require(LaboratoryProjectiles.pollInventorySave(saved)==LaboratoryProjectiles.SaveResult.REFUNDED,"Durable refund may retire its queue record");

        Files.writeString(directory.resolve("anomalies.json"),"{\"version\":1,\"anomalies\":[{\"id\":\""+anomaly+"\",\"type\":\"GRAVITY\",\"world\":\"verification\",\"x\":0,\"y\":100,\"z\":0,\"natural\":true,\"contained\":true,\"enabled\":true,\"capsuleNonce\":\""+nonce+"\"}]}");
        var anomalies=new AnomalyService(directory);var service=new LaboratoryProjectiles(anomalies,null,directory);
        require(service.inFlight(token)&&service.validCapsule(item,false),"Restart restores live queue exclusion and valid identity");
        require(!anomalies.consumeCapsule(anomaly+":"+UUID.randomUUID()),"Wrong nonce cannot consume a crafting ingredient");
        require(anomalies.consumeCapsule(token)&&!anomalies.consumeCapsule(token),"Completed crafting consumes a contained identity exactly once");
        require(!service.validCapsule(item,false)&&!new AnomalyService(directory).get(anomaly).isPresent(),"Old capsule and recovered flight cannot replay a consumed identity after restart");
        LaboratoryProjectiles.writeFlights(file,List.of(shot,shot));String bad=Files.readString(file);boolean rejected=false;
        try{LaboratoryProjectiles.readFlights(file);}catch(RuntimeException expected){rejected=true;}
        require(rejected&&bad.equals(Files.readString(file)),"Duplicate queue identities are rejected without rewriting the user's ledger");
        LaboratoryProjectiles.writeFlights(file,List.of());require(LaboratoryProjectiles.readFlights(file).isEmpty(),"Durable retirement leaves no replayable shot");
        Files.delete(file);Files.delete(directory.resolve("anomalies.json"));Files.delete(directory);
        System.out.println("PASS: capsule full-BSON/trajectory persistence, forced-save gates, refund retries, queue exclusion and nonce consumption replay.");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
