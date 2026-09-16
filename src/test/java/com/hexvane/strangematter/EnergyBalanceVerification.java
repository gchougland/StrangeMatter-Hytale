package com.hexvane.strangematter;

import java.nio.file.Files;

final class EnergyBalanceVerification {
    static void verify()throws Exception{
        var current=new StrangeMatterConfig();
        require(current.burnerGeneration==10&&current.riftGeneration==2,"Fuel generation 200 RE/s, each renewable 40 RE/s");
        require(current.riftGeneration*current.maxStabilizersPerRift<current.forgeConsumption,"One unlimited rift cannot continuously power a forge even with all stabilizers");
        require(current.burnerGeneration==current.forgeConsumption,"One fueled burner supports one unupgraded forge");
        var old=Files.createTempDirectory("sm-energy-balance-old-");
        var file=old.resolve("machines-config.json");
        String original="{\"burnerGeneration\":20,\"riftGeneration\":100,\"riftCapacity\":100000,\"conduitTransfer\":123}";
        Files.writeString(file,original);
        var upgraded=StrangeMatterConfig.load(old);
        require(upgraded.burnerGeneration==10&&upgraded.riftGeneration==2&&upgraded.riftCapacity==1200&&upgraded.conduitTransfer==123,"Existing shipped defaults migrate while custom settings survive");
        require(Files.readString(old.resolve("machines-config.before-energy-balance.json")).equals(original),"Migration retains exact old configuration as backup");
        String saved=Files.readString(file);StrangeMatterConfig.load(old);
        require(Files.readString(file).equals(saved),"Balance upgrade only runs once");
        var custom=Files.createTempDirectory("sm-energy-balance-custom-");
        Files.writeString(custom.resolve("machines-config.json"),"{\"burnerGeneration\":25,\"riftGeneration\":7,\"riftCapacity\":5000}");
        var retained=StrangeMatterConfig.load(custom);
        require(retained.burnerGeneration==25&&retained.riftGeneration==7&&retained.riftCapacity==5000,"Explicit custom energy economy remains authoritative");
        require(current.riftCapacity==1200&&current.energyBalanceRevision==2,"Fresh stabilizer buffer holds thirty seconds of generation");
        var recent=Files.createTempDirectory("sm-energy-balance-v1-");String previous="{\"energyBalanceRevision\":1,\"riftCapacity\":12000,\"conduitTransfer\":77}";
        Files.writeString(recent.resolve("machines-config.json"),previous);var reduced=StrangeMatterConfig.load(recent);
        require(reduced.riftCapacity==1200&&reduced.conduitTransfer==77&&reduced.energyBalanceRevision==2,"Recent shipped buffer migrates without replacing a custom conduit rate");
        require(Files.readString(recent.resolve("machines-config.before-energy-balance-v2.json")).equals(previous),"Buffer migration preserves the exact previous configuration");
        String once=Files.readString(recent.resolve("machines-config.json"));StrangeMatterConfig.load(recent);require(once.equals(Files.readString(recent.resolve("machines-config.json"))),"Buffer migration is idempotent");
        Files.writeString(recent.resolve("machines-config.json"),"{\"energyBalanceRevision\":1,\"riftCapacity\":9000}");require(StrangeMatterConfig.load(recent).riftCapacity==9000,"Custom recent stabilizer capacity is retained");
        System.out.println("ENERGY_BALANCE_VERIFICATION_PASSED: constrained fuel/renewable output, factory demand and backed-up idempotent default migration.");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
