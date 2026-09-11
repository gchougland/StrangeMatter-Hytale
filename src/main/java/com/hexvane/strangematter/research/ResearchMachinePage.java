package com.hexvane.strangematter.research;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3i;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hexvane.strangematter.ui.LivePageTransport;
import com.hexvane.strangematter.ui.MachineInventoryPanel;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.EmptyItemContainer;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Six live instruments on one native Hytale page, with a server-owned instability simulation. */
public final class ResearchMachinePage extends InteractiveCustomUIPage<ResearchPageData> {
    private final ResearchService service;
    private final Vector3i position;
    private final String requestedToken;
    private final long researchGeneration;
    private final AtomicBoolean queued = new AtomicBoolean();
    private ResearchSession session;
    private LivePageTransport.Lease input;
    private MachineInventoryPanel inventoryPanel;
    private ItemContainer noteInventory;
    private Ref<EntityStore> inventoryOwner;
    private Store<EntityStore> inventoryStore;
    private String noteToken, machineKey, message = "Place a research note in the machine slot, then review its instruments.";
    private record NoteChoice(String token, ResearchNode node) { }
    private List<NoteChoice> notes = List.of();
    private String selectedNote, boundNote;
    private boolean initialized, acquired, resultRecorded, dirty, notesDirty;
    private volatile boolean disposed;
    private ScheduledFuture<?> pulse;
    public ResearchMachinePage(PlayerRef player, ResearchService service, Vector3i position) {
        this(player, service, position, null);
    }
    public ResearchMachinePage(PlayerRef player, ResearchService service, Vector3i position, String requestedToken) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ResearchPageData.CODEC);
        this.service = service; this.position = new Vector3i(position.x, position.y, position.z);
        this.requestedToken = requestedToken;
        this.researchGeneration = service.generation(player.getUuid());
    }
    public static boolean open(PlayerRef player,ResearchService service,Vector3i position,String requestedToken,Store<EntityStore> store){
        var ref=player.getReference();if(ref==null||!ref.isValid())return false;
        var page=new ResearchMachinePage(player,service,position,requestedToken);
        page.noteInventory=ResearchDeskInventory.open(store.getExternalData().getWorld(),position,service);if(page.noteInventory==null)return false;
        page.inventoryOwner=ref;page.inventoryStore=store;
        page.inventoryPanel=new MachineInventoryPanel(ref,store,page.noteInventory,EmptyItemContainer.INSTANCE,()->!page.disposed&&page.validMachine(ref,store));
        page.inventoryPanel.setPage(page);
        var entity=store.getComponent(ref,Player.getComponentType());
        return entity!=null&&entity.getPageManager().openCustomPageWithWindows(ref,store,page,page.inventoryPanel.windows());
    }
    @Override public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        if (!initialized) {
            input = service.pages().attach(playerRef, this, ref, store, data -> handleDataEvent(ref, store, data));
            service.refreshNotes(store, ref);
            if(noteInventory==null)throw new IllegalStateException("Research inventory windows must open with the page");
            if(requestedToken!=null&&validMachine(ref,store)){
                var source=ResearchService.inventory(store,ref);short slot=ResearchService.findToken(source,requestedToken);
                if(slot>=0&&noteInventory.getItemStack((short)0)==null)source.moveItemStackFromSlot(slot,noteInventory,true,true);
            }
            cmd.append("StrangeMatter/ResearchMachine.ui");MachineInventoryPanel.append(cmd,"#ResearchInventory"); inventoryPanel.build(cmd,events); initialized = true;
            for(var type:ResearchType.values()){
                ResearchDisciplineUi.icon(cmd,"#"+type.name()+"Icon",type);ResearchDisciplineUi.icon(cmd,"#"+type.name()+"ShutterIcon",type);
                cmd.append("#NoteDisciplines","StrangeMatter/ResearchDisciplineChip.ui");String chip="#NoteDisciplines["+type.ordinal()+"]";
                ResearchDisciplineUi.icon(cmd,chip+" #DisciplineIcon",type);cmd.set(chip+" #DisciplineLabel.Text",type.displayName());
            }
            input.bind(events, "#Close", "close", "");
            input.bind(events, "#Begin", "begin", "");

            sound(store, "Open");
            bindControl(events, "EnergyAmpMinus", "ENERGY", "amplitude", -1); bindControl(events, "EnergyAmpPlus", "ENERGY", "amplitude", 1);
            bindControl(events, "EnergyPeriodMinus", "ENERGY", "period", -1); bindControl(events, "EnergyPeriodPlus", "ENERGY", "period", 1);
            bindControl(events, "ShadowAngleMinus", "SHADOW", "angle", -1); bindControl(events, "ShadowAnglePlus", "SHADOW", "angle", 1);
            bindControl(events, "ShadowDistanceMinus", "SHADOW", "distance", -1); bindControl(events, "ShadowDistancePlus", "SHADOW", "distance", 1);
            bindSpace(events, input);
            bindControl(events, "TimeMinus", "TIME", "speed", -1); bindControl(events, "TimePlus", "TIME", "speed", 1);
            bindCognition(events, input);
            for (int i = -5; i <= 5; i++) bindControl(events, "Force" + (i + 5), "GRAVITY", "force", i);
            startPulse(ref, store);
        }
        rebuildNotes(ref, cmd, events, store);
        render(cmd,events);
        bindInsert(events);boundNote=selectedNote;
    }
    private void bindControl(UIEventBuilder events, String id, String type, String control, int value) {
        input.bind(events, "#" + id, "control", type + ":" + control + ":" + value);
    }
    private static void bindCognition(UIEventBuilder events, LivePageTransport.Lease input) {
        for (int i = 0; i < 9; i++) input.bind(events, "#Rune" + i, "control", "COGNITION:symbol:" + i);
    }
    private static void bindSpace(UIEventBuilder events, LivePageTransport.Lease input) {
        input.bind(events, "#SpaceMinus", "control", "SPACE:warp:-1");
        input.bind(events, "#SpacePlus", "control", "SPACE:warp:1");
        input.bind(events, "#SpaceHorizontal", "control", "SPACE:axis:0");
        input.bind(events, "#SpaceVertical", "control", "SPACE:axis:1");
    }
    private void rebuildNotes(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        var stack=noteInventory.getItemStack((short)0);var node=service.noteNode(stack);
        String token=ResearchService.noteToken(stack);
        notes=node==null||token==null?List.of():List.of(new NoteChoice(token,node));
        selectedNote=notes.isEmpty()?null:token;
    }
    private void bindInsert(UIEventBuilder events){input.bind(events,"#ViewInstruments","review",selectedNote==null?"":selectedNote);}
    private String missingPrerequisites(ResearchNode node){
        return node.prerequisites().stream().filter(id->!service.hasUnlocked(playerRef.getUuid(),id)).map(service::researchName).collect(Collectors.joining(", "));
    }
    private void renderNotes(UICommandBuilder cmd, UIEventBuilder events){
        cmd.set("#NotePicker.Visible",session==null);
        if(session!=null)return;
        inventoryPanel.draw(cmd,events);
        cmd.set("#MachineInputLabel.Text","RESEARCH NOTE");
        var choice=notes.isEmpty()?null:notes.getFirst();
        cmd.set("#NoteDetails.Visible",choice!=null);cmd.set("#NoNoteSelection.Visible",choice==null);
        if(choice==null)return;
        var node=choice.node();String missing=missingPrerequisites(node);
        cmd.set("#NoteIcon.ItemId",noteIcon(node));cmd.set("#NoteTitle.Text",node.name());
        var discipline=ResearchType.forResearchNode(node.id());cmd.set("#NoteIcon.Visible",discipline==null);cmd.set("#NoteDisciplineIcon.Visible",discipline!=null);
        if(discipline!=null)ResearchDisciplineUi.icon(cmd,"#NoteDisciplineIcon",discipline);
        cmd.set("#NoteDescription.Text",node.description());
        int activeIndex=0;for(var type:ResearchType.values()){
            String chip="#NoteDisciplines["+type.ordinal()+"]";boolean active=node.costs().containsKey(type);cmd.set(chip+".Visible",active);
            if(active){var at=new Anchor();at.setLeft(Value.of((activeIndex%3)*152));at.setTop(Value.of((activeIndex/3)*21));at.setWidth(Value.of(148));at.setHeight(Value.of(20));cmd.setObject(chip+".Anchor",at);activeIndex++;}
        }
        cmd.set("#NotePrerequisites.Text",missing.isEmpty()?"Your note is used only when the experiment succeeds.":"Complete this research first: "+missing);
        cmd.set("#NotePrerequisites.Style.TextColor",missing.isEmpty()?"#a6daca":"#efaabe");
        cmd.set("#ViewInstruments.Disabled",!missing.isEmpty()||service.hasUnlocked(playerRef.getUuid(),node.id()));
    }
    private static String noteIcon(ResearchNode node){
        String special=switch(node.id()){
            case "gravity_anomalies"->"SM_Gravitic_Shard";case "temporal_anomalies"->"SM_Chrono_Shard";
            case "spatial_anomalies"->"SM_Spatial_Shard";case "energy_anomalies"->"SM_Energetic_Shard";
            case "shadow_anomalies"->"SM_Shade_Shard";case "cognitive_anomalies"->"SM_Insight_Shard";
            case "containment_basics"->"SM_Echo_Vacuum";default->"SM_"+Arrays.stream(node.id().split("_")).map(part->Character.toUpperCase(part.charAt(0))+part.substring(1)).collect(Collectors.joining("_"));
        };
        return com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap().getAsset(special)==null?ResearchService.NOTE_ITEM:special;
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, ResearchPageData data) {
        if (disposed || input == null || !input.accepts(data) || data.action == null) return;
        if (session != null) session.advanceInputClock(System.nanoTime());
        if (data.action.equals("close")) { dispose(); close(); return; }
        if (!validMachine(ref, store)) { message = "Research connection lost. Return to the Research Machine."; dispose(); close(); return; }
        switch (data.action) {
            case "review" -> {
                if(!Objects.equals(selectedNote,data.value)){message="The note changed. Review the note in the machine slot.";break;}
                insert(ref, store, data.value);
            }
            case "begin" -> { if (session != null && session.state() == ResearchSession.State.READY) { session.begin(); sound(store, "Begin_Research"); message = "Keep all active instruments stable until the instability gauge clears."; } }
            case "control" -> {
                if (session == null || data.value == null) return;
                String[] parts = data.value.split(":");
                if (parts.length != 3) return;
                ResearchType type = ResearchType.fromName(parts[0]);
                if (type == null) return;
                try { session.control(type, parts[1], Integer.parseInt(parts[2])); } catch (NumberFormatException ignored) { return; }
            }
            default -> { return; }
        }
        // Controls and note token bindings never get rebuilt by animation. Apply every input
        // immediately, then coalesce only visual state at the next acknowledged frame.
        dirty = true;
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String raw) {
        if (input != null) input.receiveFromNative(raw);
    }
    private void insert(Ref<EntityStore> ref, Store<EntityStore> store, String token) {
        if (session != null || token == null) return;
        var inventory = noteInventory;
        short slot = ResearchService.findToken(inventory, token);
        if (slot < 0) { message = "That note is no longer in the machine slot."; sound(store, "Note_Reject"); return; }
        ResearchNode node = service.noteNode(inventory.getItemStack(slot));
        if (node == null || service.hasUnlocked(playerRef.getUuid(), node.id())) { message = "This note has already been completed or is no longer valid."; sound(store, "Note_Reject"); return; }
        if (!node.prerequisites().stream().allMatch(id -> service.hasUnlocked(playerRef.getUuid(), id))) { message = "Complete this note's prerequisite research first."; sound(store, "Note_Reject"); return; }
        String key = store.getExternalData().getWorld().getName() + ":" + position.x + "," + position.y + "," + position.z;
        if (!reserve(key)) { message = "A research experiment is already using this machine or researcher."; sound(store, "Note_Reject"); return; }
        noteToken = token; session = new ResearchSession(node, System.nanoTime(), service.settings()); session.advanceInputClock(System.nanoTime());
        message = "Note accepted. Read each instrument's instructions, then begin stabilization.";
        sound(store, "Note_Insert");
    }
    private void startPulse(Ref<EntityStore> ref, Store<EntityStore> store) {
        var world = store.getExternalData().getWorld();
        // Queue at most one batch, so a stalled world cannot accumulate timer callbacks.
        try { pulse = service.timer.scheduleAtFixedRate(() -> queuePulse(world, () -> tickBatch(ref, store)), 200, 200, TimeUnit.MILLISECONDS); }
        catch (RuntimeException stopped) { dispose(); }
    }
    void queuePulse(Executor world, Runnable tick) {
        if (disposed || !queued.compareAndSet(false, true)) return;
        try {
            world.execute(() -> {
                try { if (!disposed) tick.run(); } finally { queued.set(false); }
            });
        } catch (RuntimeException stopped) {
            queued.set(false);
            dispose(); // Only local state and synchronized reservations; safe after world shutdown.
        }
    }
    private void tickBatch(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (disposed) return;
        if (!ref.isValid()) { dispose(); return; }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || player.getPageManager().getCustomPage() != this) { dispose(); return; }
        if (!validMachine(ref, store)) { dispose(); close(); return; }
        String currentToken=ResearchService.noteToken(noteInventory.getItemStack((short)0));
        if(session!=null&&!resultRecorded&&!Objects.equals(noteToken,currentToken)){release();session=null;noteToken=null;message="The note was removed. Place a note to start another experiment.";dirty=true;}
        if(!Objects.equals(selectedNote,currentToken)){notesDirty=true;dirty=true;}
        if(session==null)dirty=true; // Native inventory changes remain visible while the selector is open.
        if (session != null) session.advanceInputClock(System.nanoTime());
        // Never advance a memory cue, physics step or instability clock while its previous
        // visible frame is awaiting acknowledgement. Slow links slow simulation, not input.
        if (!input.ready()) return;
        if (session != null) session.advancePresentationClock(System.nanoTime());
        if (session == null || session.state() == ResearchSession.State.READY || resultRecorded) {
            if (dirty||notesDirty) {
                UICommandBuilder cmd=new UICommandBuilder();UIEventBuilder events=new UIEventBuilder();
                if(notesDirty)rebuildNotes(ref,cmd,events,store);
                render(cmd,events);
                if(!Objects.equals(selectedNote,boundNote))bindInsert(events);
                // The selected title and its exact note token travel together.
                if(input.send(cmd,events)){dirty=false;notesDirty=false;boundNote=selectedNote;}
            }
            return;
        }
        var inventory = noteInventory;
        if (ResearchService.findToken(inventory, noteToken) < 0) { message = "The note was removed. Experiment cancelled."; dispose(); close(); return; }
        for (int i = 0; i < 4; i++) session.tick();
        if (session.state() == ResearchSession.State.SUCCESS) {
            boolean completed = false;
            try {
                completed = service.finish(playerRef.getUuid(), session, noteToken, inventory, researchGeneration);
                message = completed ? "Research complete. " + session.node().name() + " unlocked. The note has been consumed." : "Research could not be recorded. Your note remains available.";
                sound(store, completed ? "Success" : "Note_Reject");
            } catch (RuntimeException e) { message = "Research could not be saved. Your note was restored; check the server storage."; }
            if (completed) try { service.syncRecipes(playerRef, store); }
            catch (RuntimeException delivery) {
                System.getLogger(ResearchMachinePage.class.getName()).log(System.Logger.Level.WARNING, "Research committed; native recipe synchronization will retry on the next join", delivery);
                message = "Research complete. " + session.node().name() + " unlocked. Recipe discovery will refresh when you reconnect.";
            }
            release();
            resultRecorded = true;
        } else if (session.state() == ResearchSession.State.FAILURE) {
            message = "Containment failed. Your note remains in the machine for another attempt.";
            sound(store, "Failure");
            release();
            resultRecorded = true;
        }
        UICommandBuilder cmd = new UICommandBuilder(); UIEventBuilder events = new UIEventBuilder(); render(cmd,events);
        dirty = !input.send(cmd, events); // A terminal result must retry if its final frame could not be sent.
    }
    private boolean validMachine(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (researchGeneration != service.generation(playerRef.getUuid())) return false;
        var world = store.getExternalData().getWorld();
        var block = world.getBlockType(position.x, position.y, position.z);
        if (!"SM_Research_Machine".equals(com.hexvane.strangematter.machine.MachineService.baseId(block))) return false;
        if(noteInventory!=null&&!ResearchDeskInventory.current(world,position,noteInventory))return false;
        var transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return false;
        var p = transform.getPosition();
        double dx = p.x - position.x - .5, dy = p.y - position.y - .5, dz = p.z - position.z - .5;
        return dx * dx + dy * dy + dz * dz <= 100;
    }
    private void sound(Store<EntityStore> store, String action) {
        try {
            com.hexvane.strangematter.effects.GadgetEffects.sound(store.getExternalData().getWorld(),
                    "SM_Research_Machine_" + action + "_SFX", new org.joml.Vector3d(position.x + .5, position.y + .5, position.z + .5));
        } catch (RuntimeException delivery) {
            System.getLogger(ResearchMachinePage.class.getName()).log(System.Logger.Level.WARNING, "Research action succeeded; its positional sound could not be delivered", delivery);
        }
    }
    private void render(UICommandBuilder cmd, UIEventBuilder events) {
        cmd.set("#Message.Text", message);
        cmd.set("#ResearchName.Text", session == null ? "RESEARCH MACHINE / AWAITING NOTE" : session.node().name().toUpperCase(Locale.ROOT));
        renderNotes(cmd,events);
        cmd.set("#Begin.Visible", session != null && session.state() == ResearchSession.State.READY);
        renderInstability(cmd, session);
        for (ResearchType type : ResearchType.values()) {
            String prefix = "#" + type.name();
            var p = session == null ? null : session.panel(type);
            cmd.set(prefix + "Controls.Visible", p != null);
            cmd.set(prefix + "Shutter.Visible", p == null);
            cmd.set(prefix + "State.Text", p == null ? "OFFLINE" : p.stable ? "STABLE" : "UNSTABLE");
            cmd.set(prefix + "State.Style.TextColor", p == null ? "#697a98" : p.stable ? "#63e6c2" : "#e89abd");
            if (p == null) continue;
            switch (type) {
                case COGNITION -> renderCognition(cmd, p);
                case ENERGY -> renderEnergy(cmd, p);
                case GRAVITY -> renderGravity(cmd, p);
                case SHADOW -> renderShadow(cmd, p);
                case SPACE -> renderSpace(cmd, p);
                case TIME -> renderTime(cmd, p);
            }
        }
    }
    private static double displayedInstabilityFraction(ResearchSession session) {
        if (session == null) return .5;
        // Terminal displays reach the gauge endpoints without changing the simulation's cutoffs.
        return switch (session.state()) {
            case SUCCESS -> 0;
            case FAILURE -> 1;
            default -> session.instability();
        };
    }
    static int displayedInstability(ResearchSession session) {
        return (int) Math.round(displayedInstabilityFraction(session) * 100);
    }
    static void renderInstability(UICommandBuilder cmd, ResearchSession session) {
        double instability = displayedInstabilityFraction(session);
        cmd.set("#Instability.Text", "INSTABILITY  " + displayedInstability(session) + "%");
        anchor(cmd, "#InstabilityFill", 0, 0, Math.max(1, (int) (instability * 960)), 9);
        cmd.set("#InstabilityFill.Visible", instability > 0);
        cmd.set("#InstabilityFill.Background", instability >= .8 ? "#f26887" : instability >= .6 ? "#bb84f3" : "#4bdbe2");
    }
    private static void renderCognition(UICommandBuilder cmd, ResearchSession.Panel p) {
        for (int i = 0; i < 9; i++) cmd.set("#RuneGlow" + i + ".Visible", p.displaying && !p.displayGap && !p.stable && p.pattern[p.displayIndex] == i);
        cmd.set("#CognitionReadout.Text", p.stable ? "Sequence locked" : p.cueEnded ? "Experiment ended" : !p.cueStarted ? "Begin to watch the pattern" : p.displaying ? (p.displayGap ? "Next symbol " : "Watch symbol ") + (p.displayIndex + 1) + " of " + p.pattern.length : "Repeat the symbols  " + p.inputCount + "/" + p.pattern.length);
    }
    private void renderEnergy(UICommandBuilder cmd, ResearchSession.Panel p) {
        for (int i = 0; i < 32; i++) {
            double phase = i / 31.0 * Math.PI * 4;
            anchor(cmd, "#WaveTarget" + i, i * 7 + 4, 39 - (int) Math.round(Math.sin(phase / p.targetSecondary - p.angle) * p.target * 23), 6, 6);
            anchor(cmd, "#WaveLive" + i, i * 7 + 5, 40 - (int) Math.round(Math.sin(phase / p.secondary - p.angle) * p.value * 23), 4, 4);
        }
        cmd.set("#EnergyReadout.Text", String.format(Locale.ROOT, "Amplitude %.2f  /  Period %.2f", p.value, p.secondary));
        cmd.set("#EnergyLock.Text", p.stable ? "Waves locked" : service.settings().energyRequiredAlignmentTicks <= 1 ? "Match cyan to purple" : "Match cyan to purple; hold " + service.settings().energyRequiredAlignmentTicks / 20.0 + " seconds");
    }
    private void renderGravity(UICommandBuilder cmd, ResearchSession.Panel p) {
        anchor(cmd, "#GravityCube", 105, 4 + (int) ((1 - p.position) * 83), 24, 24);
        cmd.set("#GravityReadout.Text", "Counterforce " + (int) p.value + "  /  Keep the cube between the lines");
        anchor(cmd, "#ForceSelection", ((int) p.value + 5) * 26 + 1, 145, 23, 3);
    }
    private void renderShadow(UICommandBuilder cmd, ResearchSession.Panel p) {
        double target = Math.toRadians(p.target), live = Math.toRadians(p.value + 180);
        for (int i = 0; i < 12; i++) {
            double t = (i + 1) / 12.0;
            anchor(cmd, "#ShadowTarget" + i, 122 + (int) Math.round(Math.cos(target) * p.targetSecondary * 2 * t), 42 - (int) Math.round(Math.sin(target) * p.targetSecondary * t), 6, 6);
            anchor(cmd, "#ShadowLive" + i, 123 + (int) Math.round(Math.cos(live) * ResearchSession.shadowLength(p) * 2 * t), 43 - (int) Math.round(Math.sin(live) * ResearchSession.shadowLength(p) * t), 4, 4);
        }
        double light = Math.toRadians(p.value);
        anchor(cmd, "#ShadowLight", 125 + (int) (Math.cos(light) * p.secondary * 1.5),
                Math.max(0, Math.min(82, 45 - (int) (Math.sin(light) * p.secondary))), 9, 9);
        cmd.set("#ShadowReadout.Text", "Light angle " + (int) p.value + "  /  Distance " + (int) p.secondary);
    }
    private void renderSpace(UICommandBuilder cmd, ResearchSession.Panel p) {
        for (int i = 0; i < 49; i++) {
            int x = i % 7, y = i / 7;
            double dx = x - 3, dy = y - 3;
            int bendX = (int) Math.round(p.value * (1 - dy * dy / 12) * 1.5);
            int bendY = (int) Math.round(p.secondary * (1 - dx * dx / 12) * 1.5);
            anchor(cmd, "#SpaceTarget" + i, 86 + x * 10, 8 + y * 10, 6, 6);
            anchor(cmd, "#SpaceDot" + i, 87 + x * 10 + bendX, 9 + y * 10 + bendY, 4, 4);
        }
        cmd.set("#SpaceReadout.Text", p.stable ? "Lattice aligned" : "Horizontal " + (int) p.value + "   Vertical " + (int) p.secondary + "   Margin " + session.spaceAlignmentMargin());
        anchor(cmd, "#SpaceAxisSelection", p.selectedAxis == 0 ? 12 : 150, 113, 126, 2);
    }
    private void renderTime(UICommandBuilder cmd, ResearchSession.Panel p) {
        for (int i = 0; i < 12; i++) {
            double t = (i + 1) / 12.0, current = Math.toRadians(p.angle - 90), target = Math.toRadians(p.targetAngle - 90);
            anchor(cmd, "#TimeTarget" + i, 110 + (int) Math.round(Math.cos(target) * 40 * t), 47 + (int) Math.round(Math.sin(target) * 40 * t), 6, 6);
            anchor(cmd, "#TimeLive" + i, 111 + (int) Math.round(Math.cos(current) * 40 * t), 48 + (int) Math.round(Math.sin(current) * 40 * t), 4, 4);
        }
        cmd.set("#TimePulse.Visible", session.ticks() / 20 % 2 == 0);
        cmd.set("#TimeReadout.Text", String.format(Locale.ROOT, "Cyan %.2fx   Match the purple hand", p.value));
    }
    static void anchor(UICommandBuilder cmd, String selector, int left, int top, int width, int height) {
        Anchor anchor = new Anchor(); anchor.setLeft(Value.of(left)); anchor.setTop(Value.of(top)); anchor.setWidth(Value.of(width)); anchor.setHeight(Value.of(height));
        cmd.setObject(selector + ".Anchor", anchor);
    }
    synchronized boolean reserve(String key) {
        if (disposed || acquired || !service.acquire(key, playerRef.getUuid(), researchGeneration)) return false;
        machineKey = key; acquired = true; return true;
    }
    private synchronized void release() { if (acquired) { service.release(machineKey, playerRef.getUuid(), researchGeneration); acquired = false; } }
    private synchronized void dispose() { disposed = true; if(inventoryPanel!=null)inventoryPanel.close(inventoryOwner,inventoryStore); if (input != null) input.close(); if (pulse != null) pulse.cancel(false); release(); }
    @Override public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) { dispose(); }
}
