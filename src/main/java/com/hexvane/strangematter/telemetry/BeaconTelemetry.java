package com.hexvane.strangematter.telemetry;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/** Optional Beacon 2.x public API bridge. Never owns a runtime, endpoint or upload queue. */
public final class BeaconTelemetry implements AutoCloseable {
    public static final String PROJECT_ID = "strange-matter";
    static final long INTERVAL_NANOS = 60_000_000_000L;
    private static final long MAX_COUNT = 1_000_000_000L;
    private static final long MAX_DURATION_NANOS = 3_600_000_000_000L;

    public enum Feature { SCAN, RESEARCH_UNLOCKED }
    public enum Subsystem { ANOMALIES, ITEM_TUBES, MACHINES, EQUIPMENT, SCIENTISTS, PROGRESSION }
    public enum Status { WAITING, ABSENT, DISABLED, PROJECT_MISSING, ACTIVE, UNAVAILABLE, CLOSED }

    private final ClassLoader loader;
    private final LongSupplier clock;
    private final Executor worker;
    private final ExecutorService ownedWorker;
    private final long[] uses = new long[Feature.values().length];
    private final long[] samples = new long[Subsystem.values().length];
    private final double[] totalNanos = new double[Subsystem.values().length];
    private final long[] maximumNanos = new long[Subsystem.values().length];
    private volatile Status status = Status.WAITING;
    private volatile boolean closed;
    private boolean pending;
    private long windowStarted;
    private long nextWindow;
    private Api api;
    private Object previousRuntime;

    public BeaconTelemetry(ClassLoader loader) {
        this(loader, System::nanoTime, newWorker(), true);
        tick();
    }

    BeaconTelemetry(ClassLoader loader, LongSupplier clock, Executor worker) {
        this(loader, clock, worker, false);
    }

    private BeaconTelemetry(ClassLoader loader, LongSupplier clock, Executor worker, boolean owned) {
        this.loader = Objects.requireNonNull(loader);
        this.clock = Objects.requireNonNull(clock);
        this.worker = Objects.requireNonNull(worker);
        this.ownedWorker = owned ? (ExecutorService) worker : null;
        windowStarted = clock.getAsLong();
        nextWindow = windowStarted;
    }

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "StrangeMatter-Beacon");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Cheap timing guard. Beacon remains authoritative about current per-category consent. */
    public boolean isActive() { return !closed && status == Status.ACTIVE; }
    public Status status() { return status; }

    public void usage(Feature feature) { usage(feature, 1); }

    /** Completed actions only. Identifiers and arbitrary detail strings cannot enter this API. */
    public synchronized void usage(Feature feature, long count) {
        if (!isActive() || feature == null || count <= 0) return;
        int index = feature.ordinal();
        uses[index] = Math.min(MAX_COUNT, uses[index] + Math.min(MAX_COUNT, count));
    }

    public synchronized void performance(Subsystem subsystem, long elapsedNanos) {
        if (!isActive() || subsystem == null || elapsedNanos < 0 || elapsedNanos > MAX_DURATION_NANOS) return;
        int index = subsystem.ordinal();
        if (samples[index] >= MAX_COUNT) return;
        samples[index]++;
        totalNanos[index] += elapsedNanos;
        maximumNanos[index] = Math.max(maximumNanos[index], elapsedNanos);
    }

    /** Safe to call from every world. One real-time interval and at most one pending worker task. */
    public void tick() {
        Snapshot snapshot;
        synchronized (this) {
            long now = clock.getAsLong();
            if (closed || pending || now - nextWindow < 0) return;
            pending = true;
            nextWindow = now + INTERVAL_NANOS;
            snapshot = new Snapshot(uses.clone(), samples.clone(), totalNanos.clone(), maximumNanos.clone(),
                    Math.max(0, (now - windowStarted) / 1_000_000_000.0));
            clearWindow(now);
        }
        try {
            worker.execute(() -> {
                try { publish(snapshot); }
                finally { synchronized (this) { pending = false; } }
            });
        } catch (RuntimeException rejected) {
            synchronized (this) {
                pending = false;
                if (!closed) status = Status.UNAVAILABLE;
            }
        }
    }

    private void publish(Snapshot snapshot) {
        if (closed) return;
        try {
            if (api == null) api = new Api(loader);
            Object runtime = api.locate.invoke(null);
            if (runtime == null || !Boolean.TRUE.equals(api.runtimeEnabled.invoke(runtime))) {
                inactive(Status.DISABLED);
                return;
            }
            Object project = api.findProject.invoke(runtime, PROJECT_ID);
            if (project == null) { inactive(Status.PROJECT_MISSING); return; }
            if (!Boolean.TRUE.equals(api.projectEnabled.invoke(project))) { inactive(Status.DISABLED); return; }
            // A stopped/replaced provider must never inherit the preceding provider's samples.
            boolean sameRuntime = runtime == previousRuntime;
            previousRuntime = runtime;
            synchronized (this) {
                if (closed) return;
                if (!sameRuntime) clearWindow(clock.getAsLong());
                status = Status.ACTIVE;
            }
            if (!sameRuntime) return;
            if (Arrays.stream(snapshot.uses).anyMatch(value -> value > 0) && !closed) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("scans", snapshot.uses[Feature.SCAN.ordinal()]);
                details.put("research_unlocks", snapshot.uses[Feature.RESEARCH_UNLOCKED.ordinal()]);
                details.put("window_seconds", snapshot.seconds);
                api.usage.invoke(project, "research_activity", api.context(false, details));
            }
            for (Subsystem subsystem : Subsystem.values()) {
                int index = subsystem.ordinal();
                if (closed || snapshot.samples[index] == 0) continue;
                double meanMs = snapshot.totalNanos[index] / snapshot.samples[index] / 1_000_000.0;
                double maxMs = snapshot.maximumNanos[index] / 1_000_000.0;
                Map<String, Object> details = Map.of("subsystem", subsystem.name().toLowerCase(java.util.Locale.ROOT),
                        "samples", snapshot.samples[index], "mean_ms", meanMs, "max_ms", maxMs,
                        "window_seconds", snapshot.seconds);
                api.performance.invoke(project, "subsystem_tick", (int) Math.ceil(meanMs), maxMs,
                        api.context(true, details));
            }
        } catch (ClassNotFoundException missing) {
            inactive(Status.ABSENT);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException incompatible) {
            api = null;
            inactive(Status.UNAVAILABLE);
        }
    }

    private synchronized void inactive(Status value) {
        if (closed) return;
        status = value;
        previousRuntime = null;
        clearWindow(clock.getAsLong());
    }

    private void clearWindow(long now) {
        Arrays.fill(uses, 0);
        Arrays.fill(samples, 0);
        Arrays.fill(totalNanos, 0);
        Arrays.fill(maximumNanos, 0);
        windowStarted = now;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        status = Status.CLOSED;
        clearWindow(clock.getAsLong());
        if (ownedWorker != null) ownedWorker.shutdownNow();
        // Descriptor lifetime belongs to Beacon's catalog. Never clear its global locator,
        // retire other projects, request uploads, or wait on a world thread during shutdown.
    }

    private record Snapshot(long[] uses, long[] samples, double[] totalNanos, long[] maximumNanos, double seconds) {}

    /** Only public API methods, resolved through their public interfaces, never implementation internals. */
    private static final class Api {
        final Method locate, runtimeEnabled, findProject, projectEnabled, usage, performance;
        final Method usageContext, performanceContext, detail, runtimeSide, build;
        Api(ClassLoader loader) throws ReflectiveOperationException {
            String prefix = "com.alechilles.beacon.api.";
            Class<?> locator = Class.forName(prefix + "TelemetryRuntimeLocator", true, loader);
            Class<?> runtime = Class.forName(prefix + "TelemetryRuntimeApi", true, loader);
            Class<?> project = Class.forName(prefix + "TelemetryProjectHandle", true, loader);
            Class<?> context = Class.forName(prefix + "TelemetryEventContext", true, loader);
            Class<?> builder = Class.forName(prefix + "TelemetryEventContext$Builder", true, loader);
            locate = locator.getMethod("tryGet");
            runtimeEnabled = runtime.getMethod("isEnabled");
            findProject = runtime.getMethod("findProject", String.class);
            projectEnabled = project.getMethod("isEnabled");
            usage = project.getMethod("recordUsageWithContext", String.class, context);
            performance = project.getMethod("recordPerformanceWithContext", String.class, int.class, Double.class, context);
            usageContext = context.getMethod("usage");
            performanceContext = context.getMethod("performance");
            detail = builder.getMethod("detail", String.class, Object.class);
            runtimeSide = builder.getMethod("runtimeSide", String.class);
            build = builder.getMethod("build");
        }
        Object context(boolean timing, Map<String, Object> details) throws ReflectiveOperationException {
            Object builder = (timing ? performanceContext : usageContext).invoke(null);
            runtimeSide.invoke(builder, "server");
            for (var entry : details.entrySet()) detail.invoke(builder, entry.getKey(), entry.getValue());
            return build.invoke(builder);
        }
    }
}
