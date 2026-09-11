package com.hexvane.strangematter.research;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Authoritative research ledger. Every award, purchase and completion is persisted atomically. */
public final class ResearchService implements AutoCloseable {
    public static final String NOTE_ITEM = "SM_Research_Notes";
    public static final String NOTE_TOKEN = "StrangeMatterNote";
    private final Path statePath;
    private final ResearchSettings settings;
    private final Map<String,ResearchNode> catalog;
    private final com.hexvane.strangematter.ui.LivePageTransport pages;
    private final com.hexvane.strangematter.ui.gadget.GadgetHudService hud = new com.hexvane.strangematter.ui.gadget.GadgetHudService();
    private volatile BiConsumer<UUID,ResearchType> scanHook=(player,type)->{};
    private volatile BiConsumer<UUID,ResearchNode> completionHook=(player,node)->{};
    private Properties ledger = new Properties();
    private final Map<String, UUID> machineUsers = new HashMap<>();
    private final Set<UUID> researchers = new HashSet<>();
    final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "StrangeMatter-Research-UI"); t.setDaemon(true); return t;
    });
    public ResearchService(Path dataDirectory) {
        statePath = dataDirectory.resolve("research.properties");
        try {
            Files.createDirectories(dataDirectory);
            settings = ResearchSettings.load(dataDirectory);
            catalog = ResearchCatalog.load(dataDirectory);
            if (Files.exists(statePath)) try (var in = Files.newBufferedReader(statePath, StandardCharsets.UTF_8)) { ledger.load(in); }
        } catch (IOException e) { throw new UncheckedIOException("Cannot load Strange Matter research ledger", e); }
        pages = new com.hexvane.strangematter.ui.LivePageTransport();
    }
    public com.hexvane.strangematter.ui.LivePageTransport pages() { return pages; }
    public com.hexvane.strangematter.ui.gadget.GadgetHudService hud() { return hud; }
    public ResearchSettings settings() { return settings; }
    public ResearchNode node(String id) { return catalog.get(id); }
    public List<ResearchNode> nodes() { return List.copyOf(catalog.values()); }
    public String requiredResearchForItem(String itemId) { return ResearchRecipeBridge.requirement(itemId); }
    public String researchName(String nodeId) { var node = node(nodeId); return node == null ? nodeId : node.name(); }
    public void syncRecipes(PlayerRef player, Store<EntityStore> store) { ResearchRecipeBridge.sync(this, player, store); }
    /** Administrative unlocks are atomic and preserve a valid prerequisite graph. */
    public synchronized List<ResearchNode> unlock(UUID player, String requested, boolean includePrerequisites) {
        var ordered = new LinkedHashSet<String>();
        if ("all".equalsIgnoreCase(requested)) for (var node : nodes()) collectUnlock(player, node.id(), true, ordered);
        else collectUnlock(player, requested, includePrerequisites, ordered);
        Properties next = copy(); var changed = new ArrayList<ResearchNode>();
        for (String id : ordered) if (!hasUnlocked(player, id)) { next.setProperty(prefix(player) + "unlocked." + id, "true"); changed.add(node(id)); }
        if (!changed.isEmpty()) { commit(next); for (var node : changed) notifyHook(() -> completionHook.accept(player, node)); }
        return List.copyOf(changed);
    }
    private void collectUnlock(UUID player, String id, boolean includePrerequisites, LinkedHashSet<String> ordered) {
        var node = node(id); if (node == null) throw new IllegalArgumentException("Unknown research: " + id);
        for (String prerequisite : node.prerequisites()) if (!hasUnlocked(player, prerequisite) && !ordered.contains(prerequisite)) {
            if (!includePrerequisites) throw new IllegalArgumentException("Complete " + researchName(prerequisite) + " first, or include prerequisites.");
            collectUnlock(player, prerequisite, true, ordered);
        }
        ordered.add(id);
    }
    public void setScanHook(BiConsumer<UUID,ResearchType> hook) { scanHook=Objects.requireNonNull(hook); }
    public void setCompletionHook(BiConsumer<UUID,ResearchNode> hook) { completionHook=Objects.requireNonNull(hook); }
    public record ProfileView(Map<ResearchType, Integer> points, Set<String> unlocked, int scannedCount) {}
    public synchronized ProfileView profile(UUID player) {
        var points = new EnumMap<ResearchType, Integer>(ResearchType.class);
        for (ResearchType type : ResearchType.values()) points.put(type, points(player, type));
        var unlocked = new HashSet<String>();
        for (ResearchNode node : nodes()) if (hasUnlocked(player, node.id())) unlocked.add(node.id());
        String prefix = prefix(player) + "scan.";
        int scanned = (int) ledger.stringPropertyNames().stream().filter(k -> k.startsWith(prefix)).count();
        return new ProfileView(Collections.unmodifiableMap(points), Set.copyOf(unlocked), scanned);
    }
    public synchronized int points(UUID player, ResearchType type) {
        return Integer.parseInt(ledger.getProperty(prefix(player) + "points." + type.getName(), "0"));
    }
    public synchronized boolean hasUnlocked(UUID player, String nodeId) {
        ResearchNode node = node(nodeId);
        if (node == null) return false;
        return node.defaultUnlocked() || "true".equals(ledger.getProperty(prefix(player) + "unlocked." + nodeId))
                || nodeId.equals("reality_forge_category") && hasUnlocked(player, "reality_forge");
    }
    public synchronized boolean hasScanned(UUID player, String objectId) { return ledger.containsKey(scanKey(player, objectId)); }
    public synchronized boolean scan(UUID player, String objectId, ResearchType type, int amount) {
        Objects.requireNonNull(player); Objects.requireNonNull(type);
        if (objectId == null || objectId.isBlank() || amount < 1 || amount > 10000) return false;
        if (hasScanned(player, objectId)) return false;
        Properties next = copy();
        next.setProperty(scanKey(player, objectId), "true");
        next.setProperty(prefix(player) + "points." + type.getName(), Integer.toString(Math.addExact(points(player, type), amount)));
        commit(next);
        notifyHook(()->scanHook.accept(player,type));
        return true;
    }
    public synchronized void addPoints(UUID player, ResearchType type, int amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative research award");
        Properties next = copy();
        next.setProperty(prefix(player) + "points." + type.getName(), Integer.toString(Math.addExact(points(player, type), amount)));
        commit(next);
    }
    /** Apply one award to every discipline, or leave every balance unchanged on overflow. */
    public synchronized void addPointsAll(UUID player, int amount) {
        Objects.requireNonNull(player);
        if (amount < 0) throw new IllegalArgumentException("Negative research award");
        Properties next = copy();
        for (ResearchType type : ResearchType.values()) {
            next.setProperty(prefix(player) + "points." + type.getName(), Integer.toString(Math.addExact(points(player, type), amount)));
        }
        commit(next);
    }
    public synchronized String availability(UUID player, ResearchNode node) {
        if (hasUnlocked(player, node.id())) return "Research already unlocked.";
        for (String prerequisite : node.prerequisites()) if (!hasUnlocked(player, prerequisite)) return "Requires " + node(prerequisite).name() + ".";
        for (var cost : node.costs().entrySet()) if (points(player, cost.getKey()) < cost.getValue()) return "Scan more " + cost.getKey().displayName().toLowerCase(Locale.ROOT) + " anomalies to afford this note.";
        return null;
    }
    /** Returns a user-facing result; full inventory never spends research points. */
    public synchronized String purchase(UUID player, String nodeId, ItemContainer inventory) {
        ResearchNode node = node(nodeId);
        if (node == null) return "Unknown research.";
        String unavailable = availability(player, node);
        if (unavailable != null) return unavailable;
        String token = UUID.randomUUID().toString();
        ItemStack stack = describeNote(new ItemStack(NOTE_ITEM, 1).withMetadata(NOTE_TOKEN, Codec.STRING, token), node);
        if (inventory == null || !inventory.canAddItemStack(stack)) return "Make room in your inventory for a research note.";
        Properties next = copy();
        for (var cost : node.costs().entrySet()) next.setProperty(prefix(player) + "points." + cost.getKey().getName(), Integer.toString(points(player, cost.getKey()) - cost.getValue()));
        next.setProperty("note." + token, nodeId);
        Properties previous = ledger;
        commit(next);
        var transaction = inventory.addItemStack(stack, true, false, true);
        if (!transaction.succeeded() || !ItemStack.isEmpty(transaction.getRemainder())) {
            commit(previous);
            return "Could not deliver the research note; your points were refunded.";
        }
        return "Created " + node.name() + " notes. Insert them into a Research Machine.";
    }
    public synchronized ResearchNode noteNode(ItemStack stack) {
        String token = noteToken(stack);
        return token == null ? null : node(ledger.getProperty("note." + token));
    }
    public ItemStack refreshNoteDescription(ItemStack stack) {
        ResearchNode node = noteNode(stack); return node == null ? stack : describeNote(stack, node);
    }
    private static ItemStack describeNote(ItemStack stack, ResearchNode node) {
        String title = "Research Notes: " + node.name();
        String description = node.description() + "\n\nInsert these notes into a Research Machine. The note is consumed only when this research succeeds.";
        var translations = new org.bson.BsonDocument().append("Name", new org.bson.BsonString(title)).append("Description", new org.bson.BsonString(description));
        return stack.withMetadata("TranslationProperties", translations).withMetadata(
                com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata.KEYED_CODEC,
                new com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata(
                        com.hypixel.hytale.server.core.Message.raw(title), com.hypixel.hytale.server.core.Message.raw(description)));
    }
    public void useNotes(PlayerRef playerRef, Store<EntityStore> store, ItemStack stack, Vector3i target) {
        var currentRef = playerRef.getReference();
        if (currentRef == null || !currentRef.isValid() || currentRef.getStore() != store) return;
        refreshNotes(store, currentRef);
        ResearchNode node = noteNode(stack);
        if (node == null) { hud.notice(playerRef, store, "RESEARCH NOTES", "Unwritten notes", "Use a Research Tablet to purchase a specific experiment.", false); return; }
        if (target != null) {
            var block = store.getExternalData().getWorld().getBlockType(target.x, target.y, target.z);
            var transform = store.getComponent(currentRef, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
            boolean inRange = transform != null && transform.getPosition().distanceSquared(target.x + .5, target.y + .5, target.z + .5) <= 100;
            if (inRange && "SM_Research_Machine".equals(com.hexvane.strangematter.machine.MachineService.baseId(block))) {
                var ref = playerRef.getReference();
                if (ref != null && ref.isValid()) {
                    var player = store.getComponent(ref, Player.getComponentType());
                    if (player != null) player.getPageManager().openCustomPage(ref, store, new ResearchMachinePage(playerRef, this, target, noteToken(stack)));
                }
                return;
            }
        }
        hud.notice(playerRef, store, "RESEARCH NOTES", node.name(), "Use these notes at a Research Machine to start this experiment.", false);
    }
    public void refreshNotes(Store<EntityStore> store, Ref<EntityStore> ref) {
        var inventory = inventory(store, ref); if (inventory == null) return;
        for (short slot = 0; slot < inventory.getCapacity(); slot++) {
            var stack = inventory.getItemStack(slot); if (noteToken(stack) == null) continue;
            var titled = refreshNoteDescription(stack);
            if (!titled.equals(stack)) inventory.setItemStackForSlot(slot, titled);
        }
    }
    public static String noteToken(ItemStack stack) {
        if (ItemStack.isEmpty(stack) || !NOTE_ITEM.equals(stack.getItemId())) return null;
        return stack.getFromMetadataOrNull(NOTE_TOKEN, Codec.STRING);
    }
    synchronized boolean finish(UUID player, ResearchSession session, String token, ItemContainer inventory) {
        if (session.state() != ResearchSession.State.SUCCESS || token == null) return false;
        if (!session.node().id().equals(ledger.getProperty("note." + token))) return false;
        short slot = findToken(inventory, token);
        if (slot < 0) return false;
        ItemStack original = inventory.getItemStack(slot);
        var removed = inventory.removeItemStackFromSlot(slot, original, 1, true, true);
        if (!removed.succeeded()) return false;
        Properties next = copy();
        next.remove("note." + token);
        next.setProperty(prefix(player) + "unlocked." + session.node().id(), "true");
        try { commit(next); } catch (RuntimeException e) {
            inventory.addItemStackToSlot(slot, original.withQuantity(1), true, false);
            throw e;
        }
        notifyHook(()->completionHook.accept(player,session.node()));
        return true;
    }
    static short findToken(ItemContainer inventory, String token) {
        if (inventory == null) return -1;
        for (short slot = 0; slot < inventory.getCapacity(); slot++) if (token.equals(noteToken(inventory.getItemStack(slot)))) return slot;
        return -1;
    }
    synchronized boolean acquire(String machine, UUID player) {
        if (machineUsers.containsKey(machine) || researchers.contains(player)) return false;
        machineUsers.put(machine, player); researchers.add(player); return true;
    }
    synchronized void release(String machine, UUID player) { machineUsers.remove(machine, player); researchers.remove(player); }
    public void openTablet(PlayerRef playerRef, Store<EntityStore> store) {
        openTablet(playerRef, store, null);
    }
    public void openTablet(PlayerRef playerRef, Store<EntityStore> store, String selectedNode) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) return;
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) player.getPageManager().openCustomPage(ref, store, new ResearchTabletPage(playerRef, this, selectedNode));
    }
    public void openInfo(PlayerRef playerRef, Store<EntityStore> store, String nodeId) {
        var node = node(nodeId); var ref = playerRef.getReference();
        if (node == null || !hasUnlocked(playerRef.getUuid(), nodeId) || ref == null || !ref.isValid() || ref.getStore() != store) return;
        var player = store.getComponent(ref, Player.getComponentType());
        if (player != null) player.getPageManager().openCustomPage(ref, store, new ResearchInfoPage(playerRef, this, node));
    }
    public void openMachine(PlayerRef playerRef, Store<EntityStore> store, Vector3i position) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) return;
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) player.getPageManager().openCustomPage(ref, store, new ResearchMachinePage(playerRef, this, position));
    }
    static ItemContainer inventory(Store<EntityStore> store, Ref<EntityStore> ref) {
        return InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST);
    }
    private static String prefix(UUID player) { return "player." + player + "."; }
    private static String scanKey(UUID player, String objectId) {
        return prefix(player) + "scan." + Base64.getUrlEncoder().withoutPadding().encodeToString(objectId.getBytes(StandardCharsets.UTF_8));
    }
    private Properties copy() { Properties copy = new Properties(); copy.putAll(ledger); return copy; }
    private static void notifyHook(Runnable hook) {
        try { hook.run(); } catch(RuntimeException ex) { System.getLogger(ResearchService.class.getName()).log(System.Logger.Level.WARNING,"Research progression callback failed after the ledger committed",ex); }
    }
    private void commit(Properties next) {
        Path temp = statePath.resolveSibling(statePath.getFileName() + ".tmp");
        try {
            try (var out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { next.store(out, "Strange Matter research ledger v1"); }
            try { Files.move(temp, statePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, statePath, StandardCopyOption.REPLACE_EXISTING); }
            ledger = next;
        } catch (IOException e) { throw new UncheckedIOException("Cannot save Strange Matter research", e); }
    }
    @Override public void close() { timer.shutdownNow(); pages.close(); hud.close(); }
}
