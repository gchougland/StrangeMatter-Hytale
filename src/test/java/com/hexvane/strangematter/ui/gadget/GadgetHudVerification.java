package com.hexvane.strangematter.ui.gadget;

import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.packets.interface_.CustomHud;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;

/** Uses a recording native packet handler supplied by LivePageVerification. */
public final class GadgetHudVerification {
    public static void verify(PlayerRef player, List<ToClientPacket> sent) {
        var manager = new HudManager();
        var other = new CustomUIHud(player, "Other_Mod_Hud", 10) {
            @Override protected void build(UICommandBuilder cmd) {}
        };
        manager.addCustomHud(player, other);
        int first = sent.size();
        var hud = new GadgetHudService.GadgetHud(player);
        manager.addCustomHud(player, hud);
        var readout = new GadgetHudService.Readout("FIELD SCANNER", "Acquiring anomaly", "Hold the beam steady", .5, false, "SM_Field_Scanner");
        hud.render(readout);
        int rendered = sent.size(); hud.render(readout);
        require(sent.size() == rendered, "Unchanged HUD readout emits no repeat packet");
        CustomHud visual = (CustomHud) sent.getLast();
        var buffer = MemorySegment.ofArray(new byte[visual.computeSize()]); visual.serialize(buffer, 0);
        CustomHud decoded = CustomHud.toObject(buffer);
        require(decoded.hudId.equals(GadgetHudService.KEY) && !decoded.clear && decoded.zOrder == 20, "Gadget update is scoped to its native HUD key");
        require(Arrays.stream(decoded.commands).anyMatch(c -> "#GadgetIcon.ItemId".equals(c.selector) && c.data.contains("SM_Field_Scanner")), "Wire HUD update carries the held gadget's native item icon");
        require(Arrays.stream(decoded.commands).anyMatch(c -> "#GadgetProgressFill.Anchor".equals(c.selector) && c.data.contains("170")), "Half-complete acquisition has a half-width visual meter");
        hud.render(new GadgetHudService.Readout("FIELD SCANNER", "Target lost", "Aim at the anomaly again", -1, true, "SM_Field_Scanner"));
        CustomHud error = (CustomHud) sent.getLast();
        require(Arrays.stream(error.commands).anyMatch(c -> "#GadgetAccent.Background".equals(c.selector) && c.data.contains("f37c9c")), "Error state changes the visual accent");
        manager.removeCustomHud(player, GadgetHudService.KEY);
        require(manager.getCustomHud("Other_Mod_Hud") == other && manager.getCustomHuds().size() == 1, "Adding, rendering and removing the gadget preserves another mod's HUD");
        require(sent.subList(first, sent.size()).stream().allMatch(p -> p instanceof CustomHud packet && packet.hudId.equals(GadgetHudService.KEY)), "Gadget traffic never clears or rewrites unrelated native HUD layers");
        manager.removeCustomHud(player, "Other_Mod_Hud");
        System.out.println("PASS: keyed native gadget HUD, wire-decoded icon and progress meter, error color, changed-only updates and other-mod HUD preservation.");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
