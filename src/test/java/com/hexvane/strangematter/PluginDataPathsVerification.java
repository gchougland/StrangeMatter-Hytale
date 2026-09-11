package com.hexvane.strangematter;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.plugin.PluginClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PluginDataPathsVerification {
    public static void main(String[] args) throws Exception {
        Path legacy = Path.of("mods", PluginDataPaths.LEGACY_DIRECTORY);
        Path renamed = Path.of("mods", PluginDataPaths.DISPLAY_DIRECTORY);
        require(PluginDataPaths.storageDirectory(renamed).equals(legacy), "Renamed native data leaf uses established storage");
        require(PluginDataPaths.storageDirectory(legacy).equals(legacy), "Legacy native data path is unchanged");
        Path custom = Path.of("development", "private-fixture-data");
        require(PluginDataPaths.storageDirectory(custom).equals(custom), "Explicit custom development path is unchanged");
        require(PluginDataPaths.storageDirectory(Path.of(PluginDataPaths.DISPLAY_DIRECTORY)).equals(Path.of(PluginDataPaths.LEGACY_DIRECTORY)), "Relative leaf without parent is supported");
        require(PluginDataPaths.storageDirectory(renamed.toAbsolutePath()).equals(legacy.toAbsolutePath()), "Absolute path keeps original parent");

        var manifest = new PluginManifest(); manifest.setGroup("Hexvane"); manifest.setName("Strange Matter");
        var id = new PluginIdentifier(manifest);
        require(PluginIdentifier.fromString("Hexvane:Strange Matter").equals(id), "Native plugin identity accepts spaced name");
        for (boolean classpath : new boolean[]{false, true}) {
            try (var loader = new PluginClassLoader(null, id, classpath)) {
                Path jar = Path.of("mods", "StrangeMatter.jar");
                var original = new JavaPluginInit(manifest, renamed, jar, loader);
                var adjusted = PluginDataPaths.preserveLegacyDirectory(original);
                require(adjusted.getDataDirectory().equals(legacy), "Native init receives old data directory");
                require(adjusted.getPluginManifest() == manifest && adjusted.getClassLoader() == loader
                        && adjusted.getFile().equals(jar) && adjusted.isInServerClassPath() == classpath,
                        "Supported wrapper retains manifest, loader, jar and classpath status");
                require(PluginDataPaths.preserveLegacyDirectory(adjusted) == adjusted, "Wrapper is idempotent");
                var explicit = new JavaPluginInit(manifest, custom, null, loader);
                require(PluginDataPaths.preserveLegacyDirectory(explicit) == explicit, "Custom init remains the same native object");
            }
        }

        Path root = Files.createTempDirectory("sm-plugin-data-path-");
        Path mods = Files.createDirectory(root.resolve("mods"));
        Path old = Files.createDirectory(mods.resolve(PluginDataPaths.LEGACY_DIRECTORY));
        Path ledger = old.resolve("research.json");
        byte[] saved = "{\"owner\":{\"completed\":[\"research\"],\"points\":37}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            Files.write(ledger, saved);
            Path selected = PluginDataPaths.storageDirectory(mods.resolve(PluginDataPaths.DISPLAY_DIRECTORY));
            require(java.util.Arrays.equals(Files.readAllBytes(selected.resolve("research.json")), saved), "Existing progress stays readable without copying or changing ledger bytes");
            require(!Files.exists(mods.resolve(PluginDataPaths.DISPLAY_DIRECTORY)), "Rename creates no second progress directory");
        } finally {
            Files.deleteIfExists(ledger); Files.delete(old); Files.delete(mods); Files.delete(root);
        }
        System.out.println("PASS: spaced native identity, legacy and custom data paths, supported initialization wrapper and original progress bytes.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
