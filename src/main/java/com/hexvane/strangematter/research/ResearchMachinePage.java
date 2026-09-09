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
    private final AtomicBoolean queued = new AtomicBoolean();
    private ResearchSession session;
    private LivePageTransport.Lease input;
    private String noteToken, machineKey, message = "Choose a research note from your inventory. Keep its active instruments stable to complete the experiment.";
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
    }
    @Override public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        if (!initialized) {
            input = service.pages().attach(playerRef, this, ref, store, data -> handleDataEvent(ref, store, data));
            service.refreshNotes(store, ref);
            if (requestedToken != null && validMachine(ref, store)) insert(ref, store, requestedToken);
            cmd.append("StrangeMatter/ResearchMachine.ui"); initialized = true;
            for(var type:ResearchType.values()){
                ResearchDisciplineUi.icon(cmd,"#"+type.name()+"Icon",type);ResearchDisciplineUi.icon(cmd,"#"+type.name()+"ShutterIcon",type);
                cmd.append("#NoteDisciplines","StrangeMatter/ResearchDisciplineChip.ui");String chip="#NoteDisciplines["+type.ordinal()+"]";
                ResearchDisciplineUi.icon(cmd,chip+" #DisciplineIcon",type);cmd.set(chip+" #DisciplineLabel.Text",type.displayName());
            }
            input.bind(events, "#Close", "close", "");
            input.bind(events, "#Begin", "begin", "");
            input.bind(events, "#RefreshNotes", "refresh", "");
            sound(store, "Open");
            bindControl(events, "EnergyAmpMinus", "ENERGY", "amplitude", -1); bindControl(events, "EnergyAmpPlus", "ENERGY", "amplitude", 1);
            bindControl(events, "EnergyPeriodMinus", "ENERGY", "period", -1); bindControl(events, "EnergyPeriodPlus", "ENERGY", "period", 1);
            bindControl(events, "ShadowAngleMinus", "SHADOW", "angle", -1); bindControl(events, "ShadowAnglePlus", "SHADOW", "angle", 1);
            bindControl(events, "ShadowDistanceMinus", "SHADOW", "distance", -1); bindControl(events, "ShadowDistancePlus", "SHADOW", "distance", 1);
            bindControl(events, "SpaceMinus", "SPACE", "warp", -1); bindControl(events, "SpacePlus", "SPACE", "warp", 1);
            bindControl(events, "TimeMinus", "TIME", "speed", -1); bindControl(events, "TimePlus", "TIME", "speed", 1);
            bindCognition(events, input);
            for (int i = -5; i <= 5; i++) bindControl(events, "Force" + (i + 5), "GRAVITY", "force", i);
            startPulse(ref, store);
        }
        rebuildNotes(ref, cmd, events, store);
        render(cmd);
        bindInsert(events);boundNote=selectedNote;
    }
    private void bindControl(UIEventBuilder events, String id, String type, String control, int value) {
        input.bind(events, "#" + id, "control", type + ":" + control + ":" + value);
    }
    private static void bindCognition(UIEventBuilder events, LivePageTransport.Lease input) {
        for (int i = 0; i < 9; i++) input.bind(events, "#Rune" + i, "control", "COGNITION:symbol:" + i);
    }
    private void rebuildNotes(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        cmd.clear("#Notes");
        notes=List.of();
        if (session != null) return;
        var inventory = ResearchService.inventory(store, ref);
        var found=new ArrayList<NoteChoice>();
        if (inventory != null) for (short slot = 0; slot < inventory.getCapacity(); slot++) {
            var stack = inventory.getItemStack(slot);
            ResearchNode node = service.noteNode(stack);
            if (node == null || service.hasUnlocked(playerRef.getUuid(), node.id())) continue;
            String token=ResearchService.noteToken(stack);
            if(token==null||found.stream().anyMatch(choice->choice.token().equals(token)))continue;
            cmd.append("#Notes", "StrangeMatter/ResearchNoteRow.ui");
            String selector = "#Notes[" + found.size() + "]";
            cmd.set(selector + " #NoteName.Text", node.name());
            cmd.set(selector + " #NoteRowIcon.ItemId", noteIcon(node));
            var discipline=ResearchType.forResearchNode(node.id());cmd.set(selector+" #NoteRowIcon.Visible",discipline==null);cmd.set(selector+" #NoteRowDisciplineIcon.Visible",discipline!=null);
            if(discipline!=null)ResearchDisciplineUi.icon(cmd,selector+" #NoteRowDisciplineIcon",discipline);
            input.bind(events, selector + " #SelectNote", "select", token);
            found.add(new NoteChoice(token,node));
        }
        notes=List.copyOf(found);
        if(notes.stream().noneMatch(choice->choice.token().equals(selectedNote)))selectedNote=notes.isEmpty()?null:notes.getFirst().token();
        if (notes.isEmpty()) message = "Use your Research Tablet to create a note from field observations.";
    }
    private void bindInsert(UIEventBuilder events){input.bind(events,"#InsertNote","insert",selectedNote==null?"":selectedNote);}
    private String missingPrerequisites(ResearchNode node){
        return node.prerequisites().stream().filter(id->!service.hasUnlocked(playerRef.getUuid(),id)).map(service::researchName).collect(Collectors.joining(", "));
    }
    private void renderNotes(UICommandBuilder cmd){
        cmd.set("#NotePicker.Visible",session==null);
        if(session!=null)return;
        cmd.set("#NoteCount.Text",notes.size()+" unfinished "+(notes.size()==1?"note":"notes")+" in your inventory");
        cmd.set("#NoNotes.Visible",notes.isEmpty());
        var choice=notes.stream().filter(note->note.token().equals(selectedNote)).findFirst().orElse(null);
        cmd.set("#NoteDetails.Visible",choice!=null);cmd.set("#NoNoteSelection.Visible",choice==null);
        for(int i=0;i<notes.size();i++){
            var note=notes.get(i);String row="#Notes["+i+"]";
            boolean selected=note.token().equals(selectedNote),ready=missingPrerequisites(note.node()).isEmpty();
            cmd.set(row+" #NoteState.Text",selected?(ready?"SELECTED":"SELECTED   RESEARCH NEEDED"):(ready?note.node().costs().size()+" active instruments":"PREREQUISITE NEEDED"));
            cmd.set(row+" #NoteState.Style.TextColor",ready?"#9bdbd7":"#dfa9be");
            cmd.set(row+" #NoteName.Style.TextColor",selected?"#79eff5":"#d8deef");
            cmd.set(row+" #NoteAccent.Background",selected?"#74eaf0":ready?"#544772":"#654355");
        }
        if(choice==null)return;
        var node=choice.node();String missing=missingPrerequisites(node);
        cmd.set("#NoteIcon.ItemId",noteIcon(node));cmd.set("#NoteTitle.Text",node.name());
        var discipline=ResearchType.forResearchNode(node.id());cmd.set("#NoteIcon.Visible",discipline==null);cmd.set("#NoteDisciplineIcon.Visible",discipline!=null);
        if(discipline!=null)ResearchDisciplineUi.icon(cmd,"#NoteDisciplineIcon",discipline);
        cmd.set("#NoteDescription.Text",node.description());
        int activeIndex=0;for(var type:ResearchType.values()){
            String chip="#NoteDisciplines["+type.ordinal()+"]";boolean active=node.costs().containsKey(type);cmd.set(chip+".Visible",active);
            if(active){var at=new Anchor();at.setLeft(Value.of((activeIndex%3)*174));at.setTop(Value.of((activeIndex/3)*21));at.setWidth(Value.of(170));at.setHeight(Value.of(20));cmd.setObject(chip+".Anchor",at);activeIndex++;}
        }
        cmd.set("#NotePrerequisites.Text",missing.isEmpty()?"Ready to insert. Your note is used only when the experiment succeeds.":"Complete this research first: "+missing);
        cmd.set("#NotePrerequisites.Style.TextColor",missing.isEmpty()?"#a6daca":"#efaabe");
        cmd.set("#InsertNote.Disabled",!missing.isEmpty());
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
            case "select" -> {
                if(session!=null||notes.stream().noneMatch(note->note.token().equals(data.value)))return;
                selectedNote=data.value;message="Review this note, then insert it when you are ready.";
            }
            case "refresh" -> {if(session!=null)return;service.refreshNotes(store,ref);notesDirty=true;message="Notes refreshed from your inventory.";}
            case "insert" -> {
                if(!Objects.equals(selectedNote,data.value)){message="Note selection changed. Review the selected note before inserting it.";break;}
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
        var inventory = ResearchService.inventory(store, ref);
        short slot = ResearchService.findToken(inventory, token);
        if (slot < 0) { message = "That note is no longer in your inventory."; sound(store, "Note_Reject"); return; }
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
        if (session != null) session.advanceInputClock(System.nanoTime());
        // Never advance a memory cue, physics step or instability clock while its previous
        // visible frame is awaiting acknowledgement. Slow links slow simulation, not input.
        if (!input.ready()) return;
        if (session == null || session.state() == ResearchSession.State.READY || resultRecorded) {
            if (dirty||notesDirty) {
                UICommandBuilder cmd=new UICommandBuilder();UIEventBuilder events=new UIEventBuilder();
                if(notesDirty)rebuildNotes(ref,cmd,events,store);
                render(cmd);
                if(!Objects.equals(selectedNote,boundNote))bindInsert(events);
                // The selected title and its exact note token travel together.
                if(input.send(cmd,events)){dirty=false;notesDirty=false;boundNote=selectedNote;}
            }
            return;
        }
        var inventory = ResearchService.inventory(store, ref);
        if (ResearchService.findToken(inventory, noteToken) < 0) { message = "The inserted note left your inventory. Experiment cancelled."; dispose(); close(); return; }
        for (int i = 0; i < 4; i++) session.tick();
        if (session.state() == ResearchSession.State.SUCCESS) {
            boolean completed = false;
            try {
                completed = service.finish(playerRef.getUuid(), session, noteToken, inventory);
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
            message = "Containment failed. Your note remains in your inventory for another attempt.";
            sound(store, "Failure");
            release();
            resultRecorded = true;
        }
        UICommandBuilder cmd = new UICommandBuilder(); render(cmd); if (input.send(cmd, null)) dirty = false;
    }
    private boolean validMachine(Ref<EntityStore> ref, Store<EntityStore> store) {
        var world = store.getExternalData().getWorld();
        var block = world.getBlockType(position.x, position.y, position.z);
        if (!"SM_Research_Machine".equals(com.hexvane.strangematter.machine.MachineService.baseId(block))) return false;
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
    private void render(UICommandBuilder cmd) {
        cmd.set("#Message.Text", message);
        cmd.set("#ResearchName.Text", session == null ? "RESEARCH MACHINE / AWAITING NOTE" : session.node().name().toUpperCase(Locale.ROOT));
        renderNotes(cmd);
        cmd.set("#Begin.Visible", session != null && session.state() == ResearchSession.State.READY);
        double instability = session == null ? .5 : session.state() == ResearchSession.State.SUCCESS ? 0 : session.instability();
        cmd.set("#Instability.Text", "INSTABILITY  " + displayedInstability(session) + "%");
        anchor(cmd, "#InstabilityFill", 0, 0, Math.max(1, (int) (instability * 960)), 9);
        cmd.set("#InstabilityFill.Visible", instability > 0);
        cmd.set("#InstabilityFill.Background", instability >= .8 ? "#f26887" : instability >= .6 ? "#bb84f3" : "#4bdbe2");
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
    static int displayedInstability(ResearchSession session) {
        return session == null ? 50 : session.state() == ResearchSession.State.SUCCESS ? 0 : (int) Math.round(session.instability() * 100);
    }
    private static void renderCognition(UICommandBuilder cmd, ResearchSession.Panel p) {
        for (int i = 0; i < 9; i++) cmd.set("#RuneGlow" + i + ".Visible", p.displaying && p.pattern[p.displayIndex] == i);
        cmd.set("#CognitionReadout.Text", p.stable ? "Sequence locked" : p.displaying ? "Watch the glowing symbols" : "Repeat the symbols  " + p.inputCount + "/" + p.pattern.length);
    }
    private void renderEnergy(UICommandBuilder cmd, ResearchSession.Panel p) {
        for (int i = 0; i < 32; i++) {
            double phase = i / 31.0 * Math.PI * 4;
            anchor(cmd, "#WaveTarget" + i, i * 7 + 5, 42 - (int) (Math.sin(phase / p.targetSecondary) * p.target * 23), 5, 3);
            anchor(cmd, "#WaveLive" + i, i * 7 + 5, 42 - (int) (Math.sin(phase / p.secondary) * p.value * 23), 4, 4);
        }
        cmd.set("#EnergyReadout.Text", String.format(Locale.ROOT, "Amplitude %.2f  /  Period %.2f", p.value, p.secondary));
        cmd.set("#EnergyLock.Text", p.stable ? "Waves locked" : "Match cyan to purple; hold " + service.settings().energyRequiredAlignmentTicks / 20.0 + " seconds");
    }
    private void renderGravity(UICommandBuilder cmd, ResearchSession.Panel p) {
        anchor(cmd, "#GravityCube", 105, 4 + (int) ((1 - p.position) * 83), 24, 24);
        cmd.set("#GravityReadout.Text", "Counterforce " + (int) p.value + "  /  Keep the cube between the lines");
        for (int i = 0; i < 11; i++) cmd.set("#Force" + i + ".Text", (p.value == i - 5 ? "[" : "") + (i - 5) + (p.value == i - 5 ? "]" : ""));
    }
    private void renderShadow(UICommandBuilder cmd, ResearchSession.Panel p) {
        double target = Math.toRadians(p.target), live = Math.toRadians(p.value + 180);
        for (int i = 0; i < 12; i++) {
            double t = (i + 1) / 12.0;
            anchor(cmd, "#ShadowTarget" + i, 125 + (int) (Math.cos(target) * p.targetSecondary * 2 * t), 45 - (int) (Math.sin(target) * p.targetSecondary * t), 6, 5);
            anchor(cmd, "#ShadowLive" + i, 125 + (int) (Math.cos(live) * ResearchSession.shadowLength(p) * 2 * t), 45 - (int) (Math.sin(live) * ResearchSession.shadowLength(p) * t), 4, 4);
        }
        double light = Math.toRadians(p.value);
        anchor(cmd, "#ShadowLight", 125 + (int) (Math.cos(light) * p.secondary * 1.5),
                Math.max(0, Math.min(82, 45 - (int) (Math.sin(light) * p.secondary))), 9, 9);
        cmd.set("#ShadowReadout.Text", "Light angle " + (int) p.value + "  /  Distance " + (int) p.secondary);
    }
    private void renderSpace(UICommandBuilder cmd, ResearchSession.Panel p) {
        for (int i = 0; i < 49; i++) {
            int x = i % 7, y = i / 7;
            double dx = x - 3, dy = y - 3, radius = Math.sqrt(dx * dx + dy * dy);
            double angle = Math.atan2(dy, dx) + p.value * (4 - radius) * 1.1;
            anchor(cmd, "#SpaceDot" + i, 115 + (int) (Math.cos(angle) * radius * 13), 49 + (int) (Math.sin(angle) * radius * 13), 5, 5);
        }
        cmd.set("#SpaceReadout.Text", "Spatial distortion " + (int) (p.value * 100) + "%  /  Restore the lattice");
    }
    private void renderTime(UICommandBuilder cmd, ResearchSession.Panel p) {
        for (int i = 0; i < 12; i++) {
            double t = (i + 1) / 12.0, current = Math.toRadians(p.angle - 90), target = Math.toRadians(p.targetAngle - 90);
            anchor(cmd, "#TimeTarget" + i, 113 + (int) (Math.cos(target) * 40 * t), 50 + (int) (Math.sin(target) * 40 * t), 4, 4);
            anchor(cmd, "#TimeLive" + i, 113 + (int) (Math.cos(current) * 40 * t), 50 + (int) (Math.sin(current) * 40 * t), 3, 3);
        }
        cmd.set("#TimePulse.Visible", session.ticks() / 20 % 2 == 0);
        cmd.set("#TimeReadout.Text", String.format(Locale.ROOT, "Clock speed %.2fx  /  Match the purple hand", p.value));
    }
    static void anchor(UICommandBuilder cmd, String selector, int left, int top, int width, int height) {
        Anchor anchor = new Anchor(); anchor.setLeft(Value.of(left)); anchor.setTop(Value.of(top)); anchor.setWidth(Value.of(width)); anchor.setHeight(Value.of(height));
        cmd.setObject(selector + ".Anchor", anchor);
    }
    synchronized boolean reserve(String key) {
        if (disposed || acquired || !service.acquire(key, playerRef.getUuid())) return false;
        machineKey = key; acquired = true; return true;
    }
    private synchronized void release() { if (acquired) { service.release(machineKey, playerRef.getUuid()); acquired = false; } }
    private synchronized void dispose() { disposed = true; if (input != null) input.close(); if (pulse != null) pulse.cancel(false); release(); }
    @Override public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) { dispose(); }
}
