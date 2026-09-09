package com.hexvane.strangematter.anomaly;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Run without a server: verifies the identity/capsule save boundary and source rarity defaults. */
public final class AnomalyVerification {
    public static void main(String[] args) throws Exception { run(); System.out.println("Anomaly identity, capture rollback, persistence, rarity, first-contact economy, and native age-role verification passed."); }
    public static void run() throws Exception {
        Path directory=Files.createTempDirectory("sm-anomaly-verification-");
        UUID id=UUID.fromString("2f1e3c47-2687-4b40-aaec-5a343d2ce531");
        String initial="""
            {"version":1,"anomalies":[{"id":"%s","type":"GRAVITY","world":"default","x":10.5,"y":75,"z":-20.5,"natural":true,"enabled":true}],"surveyed":{}}
            """.formatted(id);
        Files.writeString(directory.resolve("anomalies.json"),initial);
        AnomalyService service=new AnomalyService(directory);
        UUID player=UUID.randomUUID();int[] callbacks={0};
        service.setFirstContactHook((who,field,award)->{check(who.equals(player),"First-contact player identity");check(award==0,"Source exposure grants an advancement, not RP");callbacks[0]++;});
        check(service.observeContact(player,service.get(id).orElseThrow()),"First exposure produces callback");
        check(!service.observeContact(player,service.get(id).orElseThrow()),"Repeated exposure cannot farm a reward");
        check(callbacks[0]==1,"Exactly one exposure callback per discipline");
        check(service.get(id).orElseThrow().scannable(),"Natural anomaly must be scannable");
        CapturedAnomaly capsule=service.capture(id).orElseThrow();
        check(capsule.itemId().equals("SM_Containment_Capsule_Gravity"),"Capsule item ID parity");
        check(service.capture(id).isEmpty(),"Second capture must not mint another capsule");
        check(!service.get(id).orElseThrow().scannable(),"Contained anomaly cannot be scanned");
        AnomalyService loaded=new AnomalyService(directory);
        check(loaded.observedTypes(player).contains(AnomalyType.GRAVITY),"First contact persists across restart");
        check(loaded.get(id).orElseThrow().contained,"Capture persisted before giving capsule");
        check(!loaded.cancelCapture(capsule.token()+"bad"),"Foreign/modified token cannot roll back a capture");
        check(loaded.cancelCapture(capsule.token()),"Inventory failure can roll back exact capsule token");
        check(!loaded.cancelCapture(capsule.token()),"Rollback token is one-use");
        check(new AnomalyService(directory).get(id).orElseThrow().scannable(),"Rollback restores original scan provenance");
        var second=loaded.capture(id).orElseThrow();
        check(!second.token().equals(capsule.token()),"Recapture issues a fresh nonce");
        check(!loaded.cancelCapture(capsule.token()),"Old token cannot cancel a new capture");
        var settings=new AnomalyGenerationSettings();
        check(settings.rarity("default","Zone1",AnomalyType.GRAVITY,500)==500,"Original default rarity retained");
        settings.environmentRarity.put("Zone1",java.util.Map.of("GRAVITY",120));
        check(settings.rarity("default","Zone1",AnomalyType.GRAVITY,500)==120,"Biome override used");
        check(settings.rarity("default","Zone1",AnomalyType.THOUGHTWELL,500)==500,"Biome override does not alter other types");
        for(AnomalyType type:AnomalyType.values())check(AnomalyType.fromName(type.name())==type,"Type round trip");
        check(TemporalRoles.transition("Cow").orElseThrow().target().equals("Cow_Calf"),"Use actual juvenile NPC role rather than Calf model ID");
        check(TemporalRoles.transition("Cow_Calf").orElseThrow().target().equals("Cow"),"Juvenile matures to matching adult role");
        check(TemporalRoles.transition("Tamed_Cow").orElseThrow().target().equals("Tamed_Cow_Calf"),"Age regression preserves taming");
        check(TemporalRoles.transition("Tamed_Cow_Calf").orElseThrow().target().equals("Tamed_Cow"),"Maturation preserves taming");
        check(TemporalRoles.transition("Skeleton_Fighter").isEmpty(),"Hostiles are not changed into livestock");
        check(new TemporalAge("default","Cow","Cow_Calf").remainingSeconds==1200,"MC -24000 age becomes twenty loaded minutes");
        // Do not recursively delete a computed path: this test owns these exact three files.
        Files.deleteIfExists(directory.resolve("anomalies.json.tmp"));Files.deleteIfExists(directory.resolve("anomalies.json"));Files.delete(directory);
    }
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
