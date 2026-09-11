package com.hexvane.strangematter.telemetry;

import com.alechilles.beacon.api.TelemetryRuntimeLocator;
import com.alechilles.beacon.api.TelemetryRuntimeApi;
import com.alechilles.beacon.api.TelemetryEventContext;
import com.alechilles.beacon.api.internal.TelemetryRuntimeApiImpl;
import com.alechilles.beacon.api.internal.TelemetryRuntimeOperations;
import com.alechilles.beacon.core.TelemetryCoreEngine;
import com.alechilles.beacon.crash.CrashReportClient;
import com.alechilles.beacon.project.TelemetryProjectDescriptor;
import com.alechilles.beacon.project.TelemetryProjectRegistration;
import com.alechilles.beacon.runtime.TelemetryDataPaths;
import com.alechilles.beacon.runtime.TelemetryRuntimeSettings;
import com.alechilles.beacon.runtime.TelemetryProjectOverrideStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Standalone installed-jar contract test. Never boots Beacon, Hytale, or an HTTP client. */
public final class BeaconInstalledVerification {
    private static final String ID = BeaconTelemetry.PROJECT_ID;
    private static final AtomicInteger uploads = new AtomicInteger();
    private static final AtomicInteger forbiddenFlushes = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path descriptorFile = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(root);
        var descriptor = TelemetryProjectDescriptor.fromJson(Files.readString(descriptorFile), null);
        check(descriptor.projectId().equals(ID), "Stable project identity");
        check(descriptor.stats().supported() && descriptor.usage().supported() && descriptor.performance().supported(), "Three supported categories");
        check(!descriptor.defaults().enabled() && !descriptor.stats().enabled() && !descriptor.usage().enabled()
                && !descriptor.performance().enabled(), "Fresh project requires explicit operator opt-in");
        check(!descriptor.capture().supported() && !descriptor.diagnostics().supported()
                && !descriptor.reports().supported() && !descriptor.events().errors().supported()
                && !descriptor.events().lifecycle().supported() && !descriptor.events().breadcrumbs().supported(), "No automatic logs/crashes/reports/breadcrumbs");
        check(descriptor.stats().details().isEmpty(), "Stats heartbeat remains standard and uncustomized");

        // Persist and load documented operator overrides in an isolated directory.
        // The real publishable key remains in the source descriptor, but is NEVER a delivery target here.
        RuntimeCase disabled = runtime(root.resolve("disabled"), descriptor, false, true);
        TelemetryRuntimeLocator.register(disabled.api);
        AtomicLong clock = new AtomicLong();
        try (var bridge = new BeaconTelemetry(BeaconInstalledVerification.class.getClassLoader(), clock::get, Runnable::run)) {
            bridge.tick();
            check(bridge.status() == BeaconTelemetry.Status.DISABLED, "Actual runtime.json enabled:false gates integration");
            bridge.usage(BeaconTelemetry.Feature.SCAN, 7);
            advance(bridge, clock);
            check(disabled.events().isEmpty(), "Disabled runtime creates no event files");

            RuntimeCase enabled = runtime(root.resolve("enabled"), descriptor, true, true);
            TelemetryRuntimeLocator.register(enabled.api);
            advance(bridge, clock);
            check(bridge.isActive(), "Actual 2.0.1 runtime locator and public project handle attach after startup");
            bridge.usage(BeaconTelemetry.Feature.SCAN, 3);
            bridge.usage(BeaconTelemetry.Feature.RESEARCH_UNLOCKED, 2);
            bridge.performance(BeaconTelemetry.Subsystem.MACHINES, 2_000_000);
            bridge.performance(BeaconTelemetry.Subsystem.MACHINES, 4_000_000);
            advance(bridge, clock);
            check(enabled.events().size() == 2, "One aggregate usage and one performance event");
            JsonObject usage = find(enabled.events(), "research_activity");
            JsonObject timing = find(enabled.events(), "subsystem_tick");
            JsonObject usageDetails = usage.getAsJsonObject("details");
            check(usageDetails.keySet().equals(java.util.Set.of("scans", "research_unlocks", "window_seconds")), "Only declared aggregate fields");
            check(usageDetails.get("scans").getAsLong() == 3 && usageDetails.get("research_unlocks").getAsLong() == 2, "Exact completed-action aggregate");
            JsonObject timingDetails = timing.getAsJsonObject("details");
            check(timingDetails.get("samples").getAsLong() == 2 && timingDetails.get("mean_ms").getAsDouble() == 3
                    && timingDetails.get("max_ms").getAsDouble() == 4, "Exact timing mean/max/sample count");
            check(timingDetails.get("subsystem").getAsString().equals("machines"), "Fixed subsystem enum");
            for (JsonObject event : enabled.events()) {
                check(!event.has("worldName") || event.get("worldName").isJsonNull(), "No world name");
                String json = event.toString();
                check(!json.contains(descriptor.hosted().projectKey()), "Publishable key is not an event detail");
                check(!json.contains(root.toString().replace("\\", "\\\\")), "No test or world path");
            }

            enabled.engine.setUsageEnabled(ID, false);
            enabled.engine.setPerformanceEnabled(ID, false);
            bridge.usage(BeaconTelemetry.Feature.SCAN, 91);
            bridge.performance(BeaconTelemetry.Subsystem.MACHINES, 1_000_000);
            advance(bridge, clock);
            check(enabled.events().size() == 2, "Actual category opt-out discards both categories");
            enabled.engine.setUsageEnabled(ID, true);
            enabled.engine.setPerformanceEnabled(ID, true);
            advance(bridge, clock);
            check(enabled.events().size() == 2, "No replay of a discarded window after opt-in");

            enabled.api.findProject(ID).recordUsageWithContext("not_allowed",
                    TelemetryEventContext.usage().detail("scans", 1).build());
            check(enabled.events().size() == 2, "Native event name allowlist enforced");
            enabled.api.findProject(ID).recordUsageWithContext("research_activity",
                    TelemetryEventContext.usage().detail("scans", 1).detail("undeclared", "never-upload-this").build());
            check(enabled.events().size() == 3 && enabled.events().stream().noneMatch(e -> e.toString().contains("never-upload-this")),
                    "Native detail allowlist strips undeclared values");

            enabled.engine.setProjectEnabled(ID, false);
            bridge.usage(BeaconTelemetry.Feature.SCAN, 25);
            advance(bridge, clock);
            check(!bridge.isActive() && enabled.events().size() == 3, "Project opt-out discards accumulated window");
            enabled.engine.setProjectEnabled(ID, true);
            advance(bridge, clock);
            advance(bridge, clock);
            check(bridge.isActive() && enabled.events().size() == 3, "Reenable does not replay project-disabled counts");

            bridge.usage(BeaconTelemetry.Feature.SCAN, 99);
            RuntimeCase replacement = runtime(root.resolve("replacement"), descriptor, true, true);
            TelemetryRuntimeLocator.register(replacement.api);
            advance(bridge, clock);
            check(bridge.isActive() && replacement.events().isEmpty(), "Runtime replacement never inherits another provider's counts");
            bridge.usage(BeaconTelemetry.Feature.SCAN, 4);
            advance(bridge, clock);
            check(find(replacement.events(), "research_activity").getAsJsonObject("details").get("scans").getAsLong() == 4,
                    "Replacement receives only new activity");
            TelemetryRuntimeLocator.clearIfCurrent(replacement.api);
            advance(bridge, clock);
            check(!bridge.isActive(), "Native locator retirement detaches bridge");
            TelemetryRuntimeLocator.register(replacement.api);
        }
        check(TelemetryRuntimeLocator.tryGet() != null, "Closing Strange Matter does not clear Beacon's shared locator");
        TelemetryRuntimeLocator.clear();
        RuntimeCase absentProject = runtime(root.resolve("absent-project"), descriptor, true, false);
        TelemetryRuntimeLocator.register(absentProject.api);
        try (var bridge = new BeaconTelemetry(BeaconInstalledVerification.class.getClassLoader(), clock::get, Runnable::run)) {
            bridge.tick();
            check(bridge.status() == BeaconTelemetry.Status.PROJECT_MISSING, "Missing catalog entry safely defers integration");
        } finally { TelemetryRuntimeLocator.clear(); }
        check(uploads.get() == 0 && forbiddenFlushes.get() == 0, "No upload client or flush called");
        Files.writeString(root.resolve("result.txt"), "PASS installed Beacon 2.0.1; actual descriptor/override/settings parsing, public API, durable local event filtering, opt-out, replacement and cleanup; upload calls=0\n");
        System.out.println("BEACON_INSTALLED_VERIFICATION_PASSED: actual 2.0.1 API/core, opt-out, allowlists, numeric aggregates, provider lifecycle; network calls=0");
    }

    private static RuntimeCase runtime(Path root, TelemetryProjectDescriptor descriptor, boolean enabled, boolean projectPresent) throws Exception {
        Files.createDirectories(root.resolve("Settings/projects"));
        Path settingsFile = root.resolve("Settings/runtime.json");
        Files.writeString(settingsFile, "{\"enabled\":" + enabled + "}");
        var settings = TelemetryRuntimeSettings.load(settingsFile, null);
        Path overrideFile = root.resolve("Settings/projects/" + ID + ".json");
        Files.writeString(overrideFile, """
                {"enabled":true,"destinationMode":"custom",
                 "customEndpoint":{"url":"http://127.0.0.1:1/never-submit","eventUrl":"http://127.0.0.1:1/never-submit"},
                 "usage":{"enabled":true},"performance":{"enabled":true},"stats":{"enabled":true}}
                """);
        var override = new TelemetryProjectOverrideStore(null).load(overrideFile);
        var registration = new TelemetryProjectRegistration(descriptor, "Hexvane:Strange Matter", "test-only", null, override);
        check(registration.isEnabled() && registration.usage().enabled(), "Documented saved override enables supported categories");
        var paths = new TelemetryDataPaths(root, settingsFile, root.resolve("Settings/projects"), root.resolve("Telemetry"),
                root.resolve("Telemetry/crash-reports"), root.resolve("Telemetry/events"), null);
        CrashReportClient neverUpload = (target, payload) -> {
            uploads.incrementAndGet();
            throw new AssertionError("Test must never request an upload");
        };
        // Null executor deliberately disables native asynchronous submissions. No start/shutdown calls
        // (native shutdown flushes); no HTTP client is constructed even if this invariant regresses.
        var engine = new TelemetryCoreEngine(settings, paths, projectPresent ? List.of(registration) : List.of(),
                List.of(), neverUpload, null, null);
        TelemetryRuntimeOperations operations = (TelemetryRuntimeOperations) Proxy.newProxyInstance(
                BeaconInstalledVerification.class.getClassLoader(), new Class<?>[]{TelemetryRuntimeOperations.class}, (proxy, method, args) -> {
                    if (method.getName().equals("requestFlush")) { forbiddenFlushes.incrementAndGet(); throw new AssertionError("No explicit flush"); }
                    return engine.getClass().getMethod(method.getName(), method.getParameterTypes()).invoke(engine, args);
                });
        TelemetryRuntimeApi api = new TelemetryRuntimeApiImpl(operations);
        return new RuntimeCase(engine, api, paths.pendingEventsDirectory(ID));
    }

    private static void advance(BeaconTelemetry bridge, AtomicLong clock) {
        clock.addAndGet(BeaconTelemetry.INTERVAL_NANOS);
        bridge.tick();
    }
    private static JsonObject find(List<JsonObject> events, String name) {
        return events.stream().filter(e -> e.has("eventName") && e.get("eventName").getAsString().equals(name)).findFirst().orElseThrow();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private record RuntimeCase(TelemetryCoreEngine engine, TelemetryRuntimeApi api, Path eventsDirectory) {
        List<JsonObject> events() throws Exception {
            if (!Files.isDirectory(eventsDirectory)) return List.of();
            ArrayList<JsonObject> result = new ArrayList<>();
            try (var files = Files.list(eventsDirectory)) {
                for (Path path : files.filter(p -> p.toString().endsWith(".json")).toList())
                    result.add(JsonParser.parseString(Files.readString(path)).getAsJsonObject());
            }
            return result;
        }
    }
}
