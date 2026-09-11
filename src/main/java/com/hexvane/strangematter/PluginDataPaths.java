package com.hexvane.strangematter;

import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.nio.file.Path;
import java.util.Objects;

/** Display name changes must not start a second set of research and anomaly ledgers. */
final class PluginDataPaths {
    static final String LEGACY_DIRECTORY = "Hexvane_StrangeMatter";
    static final String DISPLAY_DIRECTORY = "Hexvane_Strange Matter";

    private PluginDataPaths() { }

    static Path storageDirectory(Path loaderDirectory) {
        Objects.requireNonNull(loaderDirectory, "Plugin data directory");
        // Native PendingLoadJavaPlugin builds this leaf from Group + '_' + Name.
        // Preserve an explicitly supplied development/custom directory verbatim.
        Path leaf = loaderDirectory.getFileName();
        return leaf != null && leaf.toString().equals(DISPLAY_DIRECTORY)
                ? loaderDirectory.resolveSibling(LEGACY_DIRECTORY) : loaderDirectory;
    }

    static JavaPluginInit preserveLegacyDirectory(JavaPluginInit init) {
        Objects.requireNonNull(init, "Plugin initialization");
        Path directory = storageDirectory(init.getDataDirectory());
        if (directory.equals(init.getDataDirectory())) return init;
        // Supported native initialization API; identity, classpath behavior and
        // jar location remain exactly those selected by Hytale's loader.
        return new JavaPluginInit(init.getPluginManifest(), directory,
                init.getFile(), init.getClassLoader());
    }
}
