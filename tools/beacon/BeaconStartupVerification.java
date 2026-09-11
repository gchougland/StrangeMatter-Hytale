package com.hexvane.strangematter.telemetry;

import com.hexvane.strangematter.StrangeMatterPlugin;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate test plugin: production StrangeMatterPlugin and Beacon both keep their real entry points. */
public final class BeaconStartupVerification extends JavaPlugin {
    private final AtomicBoolean finished = new AtomicBoolean();
    public BeaconStartupVerification(JavaPluginInit init) { super(init); }
    @Override protected void start() {
        CompletableFuture.delayedExecutor(45, TimeUnit.SECONDS).execute(() -> finish(new TimeoutException("Beacon startup probe")));
        Universe.get().getUniverseReady().thenRunAsync(() -> {
            try {
                var mod = StrangeMatterPlugin.instance();
                require(mod != null, "Real Strange Matter plugin initialized");
                ClassLoader modLoader = mod.getClass().getClassLoader();
                Class<?> locator = Class.forName("com.alechilles.beacon.api.TelemetryRuntimeLocator", true, modLoader);
                Class<?> runtimeType = Class.forName("com.alechilles.beacon.api.TelemetryRuntimeApi", true, modLoader);
                Object runtime = locator.getMethod("tryGet").invoke(null);
                require(runtime != null, "Beacon registered through native plugin lifecycle");
                require(!Boolean.TRUE.equals(runtimeType.getMethod("isEnabled").invoke(runtime)), "Isolated runtime stays disabled");
                Object project = runtimeType.getMethod("findProject", String.class).invoke(runtime, BeaconTelemetry.PROJECT_ID);
                require(project != null, "Beacon discovered Server/Beacon/project.json in unmodified production jar");
                Class<?> projectType = Class.forName("com.alechilles.beacon.api.TelemetryProjectHandle", true, modLoader);
                require(!Boolean.TRUE.equals(projectType.getMethod("isEnabled").invoke(project)), "Test project override stays disabled");
                require(projectType.getMethod("displayName").invoke(project).equals("Strange Matter"), "Correct discovered project");
                require(locator.getClassLoader() != modLoader, "Public API is provided by the separate Beacon plugin");
                try (var bridge = new BeaconTelemetry(modLoader)) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (bridge.status() == BeaconTelemetry.Status.WAITING && System.nanoTime() < deadline) Thread.sleep(10);
                    require(bridge.status() == BeaconTelemetry.Status.DISABLED, "Actual bridge resolves API across native plugin classloaders");
                    bridge.usage(BeaconTelemetry.Feature.SCAN, 42);
                    require(!bridge.isActive(), "Disabled probe cannot emit test activity");
                }
                require(locator.getMethod("tryGet").invoke(null) == runtime, "Bridge cleanup preserves Beacon runtime");
                finish(null);
            } catch (Throwable error) { finish(error); }
        }).exceptionally(error -> { finish(error); return null; });
    }
    private void finish(Throwable error) {
        if (!finished.compareAndSet(false, true)) return;
        String result = error == null ? "BEACON_STARTUP_VERIFICATION_PASSED: production entry points, native discovery and cross plugin classloaders; runtime and project disabled"
                : "BEACON_STARTUP_VERIFICATION_FAILED: " + error;
        System.out.println(result);
        if (error != null) error.printStackTrace();
        try { Files.writeString(Path.of("beacon-startup-result.txt"), result + "\n"); }
        catch (Exception failure) { failure.printStackTrace(); }
        CompletableFuture.runAsync(() -> HytaleServer.get().shutdownServer());
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
