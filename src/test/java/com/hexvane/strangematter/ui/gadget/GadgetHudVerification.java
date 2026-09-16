package com.hexvane.strangematter.ui.gadget;

import com.hexvane.strangematter.research.ResearchType;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.packets.interface_.CustomHud;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.modules.entity.spectator.SpectatingHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.lang.foreign.MemorySegment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Uses a recording native packet handler supplied by LivePageVerification. */
public final class GadgetHudVerification {
    public static void verify(PlayerRef player, List<ToClientPacket> sent) {
        var manager = new HudManager();
        // Compare the actual engine implementation over the wire: null and "" have
        // different selector-presence bits, even though both can look like "root" in Java.
        manager.addCustomHud(player, new SpectatingHud(player));
        CustomHud nativeInitial = wire((CustomHud) sent.getLast());
        require(nativeInitial.commands.length == 1 && nativeInitial.commands[0].type == CustomUICommandType.AppendInline
            && nativeInitial.commands[0].selector == null, "Native SpectatingHud appends its root with an absent selector");
        manager.removeCustomHud(player, SpectatingHud.KEY);
        var other = new CustomUIHud(player, "Other_Mod_Hud", 10) {
            @Override protected void build(UICommandBuilder cmd) {}
        };
        manager.addCustomHud(player, other);
        int first = sent.size();
        var hud = new GadgetHudService.GadgetHud(player);
        manager.addCustomHud(player, hud);
        CustomHud initial = (CustomHud) sent.getLast();
        CustomHud initialDecoded = wire(initial);
        require(initialDecoded.clear && initialDecoded.commands.length == 1, "Initial HUD packet builds exactly one owned layer");
        var append = initialDecoded.commands[0];
        require(append.type == nativeInitial.commands[0].type && append.selector == nativeInitial.commands[0].selector,
            "Initial HUD uses the same absent root selector as the real native SpectatingHud packet");
        require(packagedDocument().equals(append.text), "Actual wire carries the exact packaged HUD markup as its single layout source");
        require(append.text.contains("#GadgetIcon") && append.text.contains("#GadgetProgressFill"), "Inline HUD includes instrument icon and acquisition meter targets");
        hud.render(new GadgetHudService.Readout("Hoverboard", "Board folded", "Activate near solid ground to deploy and mount the board.", -1, false, "SM_Hoverboard"));
        CustomHud heldBoard = wire((CustomHud) sent.getLast());
        require(!heldBoard.clear && heldBoard.hudId.equals(GadgetHudService.KEY), "First held hoverboard readout updates the newly created owned layer");
        require(Arrays.stream(heldBoard.commands).anyMatch(c -> "#GadgetIcon.ItemId".equals(c.selector) && c.data.contains("SM_Hoverboard")), "Holding the board sends its native item icon");
        for (String hidden : List.of("#GadgetProgress.Visible", "#GadgetDiscipline.Visible"))
            require(Arrays.stream(heldBoard.commands).anyMatch(c -> hidden.equals(c.selector) && c.data.contains("false")), "Folded hoverboard hides unused HUD element " + hidden);
        var readout = new GadgetHudService.Readout("FIELD SCANNER", "Acquiring anomaly", "Hold the beam steady", .5, false, "SM_Field_Scanner", ResearchType.ENERGY);
        hud.render(readout);
        int rendered = sent.size(); hud.render(readout);
        require(sent.size() == rendered, "Unchanged HUD readout emits no repeat packet");
        CustomHud visual = (CustomHud) sent.getLast();
        var buffer = MemorySegment.ofArray(new byte[visual.computeSize()]); visual.serialize(buffer, 0);
        CustomHud decoded = CustomHud.toObject(buffer);
        require(decoded.hudId.equals(GadgetHudService.KEY) && !decoded.clear && decoded.zOrder == 20, "Gadget update is scoped to its native HUD key");
        require(Arrays.stream(decoded.commands).anyMatch(c -> "#GadgetIcon.ItemId".equals(c.selector) && c.data.contains("SM_Field_Scanner")), "Wire HUD update carries the held gadget's native item icon");
        require(Arrays.stream(decoded.commands).anyMatch(c -> "#GadgetProgressFill.Anchor".equals(c.selector) && c.data.contains("170")), "Half-complete acquisition has a half-width visual meter");
        require(Arrays.stream(decoded.commands).anyMatch(c -> "#GadgetDisciplineIcon.Background".equals(c.selector) && c.data.contains(ResearchType.ENERGY.uiIconPath())), "Typed target uses the shared original research discipline texture alongside its held gadget");
        require(Arrays.stream(decoded.commands).anyMatch(c -> "#GadgetDisciplineName.Text".equals(c.selector) && c.data.contains("Energy")), "Discipline icon has a readable target label");
        hud.render(new GadgetHudService.Readout("FIELD SCANNER","Acquiring anomaly","Hold steady",.5,false,"SM_Field_Scanner",ResearchType.ENERGY,new GadgetHudService.EnergyReadout(0,2000,12000,60000,200,false)));
        var depleted=wire((CustomHud)sent.getLast());
        require(Arrays.stream(depleted.commands).anyMatch(c->"#GadgetEnergyFill.Visible".equals(c.selector)&&c.data.contains("false")),"Empty energy renders no filled pixel");
        require(Arrays.stream(depleted.commands).anyMatch(c->"#GadgetProgressFill.Anchor".equals(c.selector)&&c.data.contains("170")),"Energy does not replace scan progress");
        require(Arrays.stream(depleted.commands).anyMatch(c->"#GadgetPackText.Text".equals(c.selector)&&c.data.contains("12000")&&c.data.contains("200 RE/s")),"Same HUD reports worn pack reserve and transfer");
        hud.render(new GadgetHudService.Readout("FIELD SCANNER", "Target lost", "Aim at the anomaly again", -1, true, "SM_Field_Scanner"));
        CustomHud error = (CustomHud) sent.getLast();
        require(Arrays.stream(error.commands).anyMatch(c -> "#GadgetAccent.Background".equals(c.selector) && c.data.contains("f37c9c")), "Error state changes the visual accent");
        require(Arrays.stream(error.commands).anyMatch(c -> "#GadgetDiscipline.Visible".equals(c.selector) && c.data.contains("false")), "Losing a typed target hides its discipline instead of leaving a stale symbol");
        manager.removeCustomHud(player, GadgetHudService.KEY);
        manager.addCustomHud(player, hud);
        int rebuilt = sent.size();
        hud.render(new GadgetHudService.Readout("FIELD SCANNER", "Target lost", "Aim at the anomaly again", -1, true, "SM_Field_Scanner"));
        require(sent.size() == rebuilt + 1, "Reattaching the same HUD repopulates labels even when its readout is unchanged");
        require(Arrays.stream(((CustomHud) sent.getLast()).commands).anyMatch(c -> "#GadgetStatus.Text".equals(c.selector) && c.data.contains("Target lost")), "Rebuilt native HUD receives its current status instead of empty defaults");
        manager.removeCustomHud(player, GadgetHudService.KEY);
        require(manager.getCustomHud("Other_Mod_Hud") == other && manager.getCustomHuds().size() == 1, "Adding, rendering and removing the gadget preserves another mod's HUD");
        require(sent.subList(first, sent.size()).stream().allMatch(p -> p instanceof CustomHud packet && packet.hudId.equals(GadgetHudService.KEY)), "Gadget traffic never clears or rewrites unrelated native HUD layers");
        manager.removeCustomHud(player, "Other_Mod_Hud");
        System.out.println("PASS: keyed native gadget HUD, root selector matches real SpectatingHud over the wire, exact packaged inline template, first held hoverboard readout, icon and progress meter, error color, changed-only updates, same-instance rebuild and other-mod HUD preservation. Client UI parsing remains a separate check.");
    }
    private static CustomHud wire(CustomHud packet) {
        var bytes = MemorySegment.ofArray(new byte[packet.computeSize()]);
        packet.serialize(bytes, 0);
        return CustomHud.toObject(bytes);
    }
    private static String packagedDocument() {
        try (var stream = GadgetHudService.class.getResourceAsStream(GadgetHudService.DOCUMENT_RESOURCE)) {
            require(stream != null, "Gadget HUD document exists on the production resource classpath");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new AssertionError("Cannot inspect packaged gadget HUD", failure); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
