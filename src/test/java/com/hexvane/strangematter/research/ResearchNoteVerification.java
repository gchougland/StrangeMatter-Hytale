package com.hexvane.strangematter.research;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import org.bson.BsonDocument;
import org.bson.BsonString;
import java.nio.file.Files;
import java.util.UUID;

/** Runs after native Item assets have loaded, alongside the isolated world smoke test. */
public final class ResearchNoteVerification {
    public static void verify() throws Exception {
        var directory = Files.createTempDirectory("sm-native-notes-");
        try (var research = new ResearchService(directory)) {
            UUID player = UUID.randomUUID();
            require(research.scan(player, "note-title-test", ResearchType.COGNITION, 20), "Native note fixture earns observations");
            var inventory = new SimpleItemContainer((short) 8);
            String result = research.purchase(player, "cognitive_anomalies", inventory);
            require(result.startsWith("Created "), "Native research purchase succeeds: " + result);
            ItemStack note = inventory.getItemStack((short) 0);
            String token = ResearchService.noteToken(note);
            require(token != null, "Title metadata preserves the minted research token");
            String title = "Research Notes: " + research.node("cognitive_anomalies").name();
            require(title.equals(note.getDisplayName().getRawText()), "Native item display override contains the actual research title");
            require(note.getDisplayDescription().getRawText().contains("Insert these notes into a Research Machine"), "Native tooltip describes machine insertion");
            var packet = note.toPacket();
            require(packet.metadata != null, "Native inventory packet includes title metadata");
            var transmitted = BsonDocument.parse(packet.metadata);
            require(title.equals(transmitted.getDocument("TranslationProperties").getString("Name").getValue()), "Client-facing metadata has the matching research title");
            ItemStack restored = new ItemStack(packet.itemId, packet.quantity, transmitted);
            require(title.equals(restored.getDisplayName().getRawText()) && token.equals(ResearchService.noteToken(restored)), "Native packet metadata round trip retains title and token identity");
            ItemStack legacy = new ItemStack(ResearchService.NOTE_ITEM, 1)
                    .withMetadata(ResearchService.NOTE_TOKEN, Codec.STRING, token)
                    .withMetadata("OtherMod", new BsonDocument("Marker", new BsonString("preserve")));
            ItemStack refreshed = research.refreshNoteDescription(legacy);
            require(title.equals(refreshed.getDisplayName().getRawText()), "Existing untitled notes receive the actual research title");
            require(token.equals(ResearchService.noteToken(refreshed)) && com.hexvane.strangematter.util.StackData.metadata(refreshed).containsKey("OtherMod"), "Refresh preserves both the minted token and unrelated metadata");
            require(refreshed.equals(research.refreshNoteDescription(refreshed)), "Refreshing an already titled note causes no inventory mutation");
        }
        System.out.println("PASS: native research-note title/description, client packet metadata round trip, legacy-note migration and token preservation.");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
