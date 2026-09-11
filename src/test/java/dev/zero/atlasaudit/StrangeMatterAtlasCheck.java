package dev.zero.atlasaudit;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Test-only bridge to ZeroErrors' unmodified MIT atlas audit. Never shipped to players. */
public final class StrangeMatterAtlasCheck {
    public static void verify() throws Exception {
        var report = AtlasAudit.run();
        Files.writeString(Path.of("atlas-audit-report.txt"), report.fullReport());
        Files.writeString(Path.of("atlas-audit-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println(report.fullReport());
        for (var atlas : report.atlases()) {
            if (atlas.sourceError() || atlas.unreadableCount() != 0)
                throw new AssertionError("Atlas audit incomplete: " + atlas.name());
            if (atlas.overflowsMinSpec())
                throw new AssertionError("Loaded packs exceed the 8192 atlas limit: " + atlas.name());
        }
    }
    private StrangeMatterAtlasCheck() {}
}
