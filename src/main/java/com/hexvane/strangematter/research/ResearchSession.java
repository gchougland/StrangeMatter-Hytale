package com.hexvane.strangematter.research;

import java.util.*;

/** Pure, deterministic 20 Hz simulation. Client messages can only operate controls, never award completion. */
public final class ResearchSession {
    public enum State { READY, RUNNING, SUCCESS, FAILURE }
    public static final class Panel {
        public boolean stable;
        public int stableTicks, cooldown, driftTicks;
        public double value, secondary, target, targetSecondary, velocity, position = .5;
        public double angle, targetAngle, drift, secondaryDrift, driftTarget, secondaryDriftTarget;
        public int[] pattern = new int[0], input = new int[0];
        public int inputCount, displayIndex, displayTicks, redisplayTicks;
        public boolean displaying, displayGap, cueStarted, cueEnded;
    }
    private final EnumMap<ResearchType, Panel> panels = new EnumMap<>(ResearchType.class);
    private final Random random;
    private final ResearchNode node;
    private final ResearchSettings settings;
    private State state = State.READY;
    private double instability = .5;
    private long ticks;
    private boolean externalInputClock;
    private long inputClockNanos;
    private boolean presentationClock;
    private long cueVisibleSince = Long.MIN_VALUE;
    private static final int CUE_GAP_TICKS = 6;
    public ResearchSession(ResearchNode node, long seed) {
        this(node, seed, new ResearchSettings());
    }
    public ResearchSession(ResearchNode node, long seed, ResearchSettings settings) {
        this.settings = Objects.requireNonNull(settings); settings.validate();
        this.node = Objects.requireNonNull(node); random = new Random(seed);
        if (node.costs().isEmpty()) throw new IllegalArgumentException("Research requires at least one discipline");
        for (ResearchType type : node.costs().keySet()) {
            var p = new Panel(); panels.put(type, p);
            switch (type) {
                case COGNITION -> newPattern(p);
                case ENERGY -> {
                    p.target = randomGrid(.5, 1.5, settings.energyAmplitudeStep);
                    p.targetSecondary = randomGrid(1, 1.25, settings.energyPeriodStep);
                    p.value = apart(p.target, .5, 1.5); p.secondary = apart(p.targetSecondary, 1, 1.25);
                }
                case GRAVITY -> { p.target = nonzeroGravity(); p.position = p.target > 0 ? .9 : .1; }
                case SHADOW -> {
                    double angle = randomGrid(-60, 60, settings.shadowRotationStep), distance = randomGrid(20, 50, 5);
                    p.target = angle + 180; p.targetSecondary = (70 - distance) * .8;
                    p.value = apart(angle, -60, 60); p.secondary = apart(distance, 20, 50);
                }
                case SPACE -> p.value = Math.min(1, Math.max(.65, settings.spaceStabilityThreshold + .2) + random.nextDouble() * .2);
                case TIME -> {
                    p.target = 1; double offset = Math.ceil(Math.max(.6, settings.timeSpeedThreshold + .2) / settings.timeSpeedAdjustment) * settings.timeSpeedAdjustment;
                    p.value = clamp(1 + (offset <= 1 && random.nextBoolean() ? offset : -offset), -2, 2);
                    p.angle = 180; p.targetAngle = 0;
                }
            }
        }
    }
    public void begin() {
        if (state != State.READY) return;
        state = settings.enableMinigames ? State.RUNNING : State.SUCCESS;
        var p = panels.get(ResearchType.COGNITION);
        if (p != null && state == State.RUNNING) startCue(p);
    }
    public State state() { return state; }
    public double instability() { return instability; }
    public long ticks() { return ticks; }
    public ResearchNode node() { return node; }
    public Panel panel(ResearchType type) { return panels.get(type); }
    public Set<ResearchType> activeTypes() { return Collections.unmodifiableSet(panels.keySet()); }

    /**
     * Live pages use wall-time debounce even when an unacknowledged visual frame pauses
     * physics. Offline simulations retain their original tick-based cooldowns by default.
     * Invoke before each input and pulse with a monotonic clock on the owning world thread.
     */
    public void advanceInputClock(long nowNanos) {
        if (!externalInputClock) { externalInputClock = true; inputClockNanos = nowNanos; return; }
        long elapsedTicks = Math.max(0, nowNanos - inputClockNanos) / 50_000_000L;
        if (elapsedTicks == 0) return;
        inputClockNanos += elapsedTicks * 50_000_000L;
        for (Panel panel : panels.values()) panel.cooldown = (int) Math.max(0, panel.cooldown - elapsedTicks);
    }

    public void tick() {
        if (state != State.RUNNING) return;
        ticks++;
        panels.forEach((type, p) -> {
            if (!externalInputClock && p.cooldown > 0) p.cooldown--;
            switch (type) {
                case COGNITION -> cognitionTick(p);
                case ENERGY -> energyTick(p);
                case GRAVITY -> gravityTick(p);
                case SHADOW -> shadowTick(p);
                case SPACE -> spaceTick(p);
                case TIME -> timeTick(p);
            }
        });
        boolean allStable = panels.values().stream().allMatch(p -> p.stable);
        instability = clamp(instability + (allStable ? -settings.instabilityDecreaseRate : settings.instabilityBaseIncreaseRate / panels.size()), 0, 1);
        if (instability <= .05) state = State.SUCCESS;
        else if (instability >= .95) state = State.FAILURE;
        if (state != State.RUNNING) {
            var cognition = panels.get(ResearchType.COGNITION);
            if (cognition != null) { cognition.displaying = false; cognition.displayGap = false; cognition.cueEnded = true; }
        }
    }

    /** Only fixed control names and bounded integer values are accepted. */
    public boolean control(ResearchType type, String control, int direction) {
        Panel p = panels.get(type);
        if (state != State.RUNNING || p == null || p.cooldown > 0 || control == null) return false;
        int sign = Integer.compare(direction, 0);
        boolean accepted = true;
        switch (type) {
            case COGNITION -> {
                if (!control.equals("symbol") || direction < 0 || direction > 8 || p.stable) return false;
                // Original game checks the entire entered pattern. A wrong attempt resets input.
                p.input[p.inputCount++] = direction;
                if (p.inputCount == p.pattern.length) {
                    p.stable = Arrays.equals(p.pattern, p.input);
                    if (p.stable) { p.driftTicks = 0; p.displaying = false; p.displayGap = false; cueVisibleSince = Long.MIN_VALUE; }
                    else p.inputCount = 0;
                }
            }
            case ENERGY -> {
                if (control.equals("amplitude")) p.value = stepGrid(p.value, sign, .5, 1.5, settings.energyAmplitudeStep);
                else if (control.equals("period")) p.secondary = stepGrid(p.secondary, sign, 1, 1.25, settings.energyPeriodStep);
                else accepted = false;
            }
            case GRAVITY -> {
                if (!control.equals("force") || direction < -5 || direction > 5) return false;
                p.value = direction; p.driftTicks = 0;
            }
            case SHADOW -> {
                if (control.equals("angle")) p.value = stepGrid(p.value, sign, -60, 60, settings.shadowRotationStep);
                else if (control.equals("distance")) p.secondary = stepGrid(p.secondary, sign, 20, 50, 5);
                else accepted = false;
                p.driftTicks = 0; p.drift = 0;
            }
            case SPACE -> {
                if (!control.equals("warp")) return false;
                p.value = clamp(p.value + sign * settings.spaceWarpAdjustment, 0, 1); p.drift = 0; p.driftTicks = 0;
            }
            case TIME -> {
                if (!control.equals("speed")) return false;
                p.value = clamp(p.value + sign * settings.timeSpeedAdjustment, -2, 2);
                if (Math.abs(p.value - p.target) < settings.timeSnapThreshold) { p.value = p.target; p.angle = p.targetAngle; }
            }
        }
        if (accepted) p.cooldown = type == ResearchType.ENERGY || type == ResearchType.GRAVITY ? 1 : 5;
        return accepted;
    }
    private void newPattern(Panel p) {
        p.pattern = new int[settings.cognitionDifficulty]; p.input = new int[p.pattern.length];
        List<Integer> available = new ArrayList<>(); for (int i = 0; i < 9; i++) available.add(i);
        for (int i = 0; i < p.pattern.length; i++) p.pattern[i] = available.remove(random.nextInt(available.size()));
        p.inputCount = 0; p.displayIndex = 0; p.displayTicks = 0; p.redisplayTicks = 0;
        p.displaying = false; p.displayGap = false; p.cueStarted = false; p.stable = false;
        cueVisibleSince = Long.MIN_VALUE;
        if (state == State.RUNNING) startCue(p);
    }
    private void startCue(Panel p) {
        p.displaying = true; p.displayGap = true; p.cueStarted = true;
        p.displayIndex = 0; p.displayTicks = 0; p.redisplayTicks = 0;
        cueVisibleSince = Long.MIN_VALUE;
    }
    private int cueDuration(Panel p) { return !p.displaying ? 100 : p.displayGap ? CUE_GAP_TICKS : settings.cognitionMatchDuration; }
    private void nextCuePhase(Panel p) {
        p.displayTicks = 0; p.redisplayTicks = 0; cueVisibleSince = Long.MIN_VALUE;
        if (!p.displaying) { startCue(p); return; }
        if (p.displayGap) { p.displayGap = false; return; }
        if (++p.displayIndex == p.pattern.length) { p.displaying = false; p.displayIndex = 0; p.displayGap = false; }
        else p.displayGap = true;
    }
    /**
     * Called only after the page's actual acknowledgement gate opens. The first such
     * callback confirms the current cue reached the client; its visible hold starts then.
     * Later callbacks may advance ONE phase, never skip unseen symbols or dark gaps.
     * Repeated visual packets do not multiply a cue's duration by network round trips.
     * Instability and instrument physics remain on their separate acknowledged tick clock.
     */
    public void advancePresentationClock(long nowNanos) {
        presentationClock = true;
        var p = panels.get(ResearchType.COGNITION);
        if (state != State.RUNNING || p == null || p.stable) return;
        if (cueVisibleSince == Long.MIN_VALUE) { cueVisibleSince = nowNanos; return; }
        long elapsed = Math.max(0, nowNanos - cueVisibleSince) / 50_000_000L;
        if (p.displaying) p.displayTicks = (int) Math.min(elapsed, cueDuration(p));
        else p.redisplayTicks = (int) Math.min(elapsed, cueDuration(p));
        if (elapsed >= cueDuration(p)) nextCuePhase(p);
    }
    private void cognitionTick(Panel p) {
        if (p.stable) { if (++p.driftTicks >= 600) { newPattern(p); p.driftTicks = 0; } return; }
        if (presentationClock) return;
        int elapsed = p.displaying ? ++p.displayTicks : ++p.redisplayTicks;
        if (elapsed >= cueDuration(p)) nextCuePhase(p);
    }
    private void energyTick(Panel p) {
        boolean aligned = Math.abs(p.value - p.target) < .1 && Math.abs(p.secondary - p.targetSecondary) < .1;
        if (!aligned) { p.stable = false; p.stableTicks = 0; p.driftTicks = 0; p.drift = 0; p.secondaryDrift = 0; return; }
        p.stable = ++p.stableTicks >= settings.energyRequiredAlignmentTicks;
        if (p.stable && ++p.driftTicks >= settings.energyDriftDelayTicks) {
            if (random.nextDouble() < .1) { p.driftTarget = (random.nextDouble() - .5) * .1; p.secondaryDriftTarget = (random.nextDouble() - .5) * .1; }
            p.drift += clamp(p.driftTarget - p.drift, -.005, .005);
            p.secondaryDrift += clamp(p.secondaryDriftTarget - p.secondaryDrift, -.005, .005);
            p.value = clamp(p.value + p.drift, .5, 1.5); p.secondary = clamp(p.secondary + p.secondaryDrift, 1, 1.25);
        }
    }
    private void gravityTick(Panel p) {
        double force = p.target + p.value;
        p.velocity += force == 0 ? (.5 - p.position) * .1 : force * .01;
        p.velocity *= force == 0 ? .9 : .95;
        p.position = clamp(p.position + p.velocity, 0, 1);
        if (p.position <= 0 || p.position >= 1) p.velocity = 0;
        boolean equilibrium = force == 0 && Math.abs(p.position - .5) < settings.gravityBalanceThreshold;
        if (equilibrium) { if (++p.stableTicks >= 100 && ++p.driftTicks >= settings.gravityDriftDelayTicks) { p.target = nonzeroGravity(); p.driftTicks = 0; } }
        else { p.stableTicks = 0; p.driftTicks = 0; }
        p.stable = equilibrium && p.stableTicks >= 100;
    }
    private void shadowTick(Panel p) {
        p.stable = Math.abs(p.value + 180 - p.target) < settings.shadowAlignmentThreshold && Math.abs(shadowLength(p) - p.targetSecondary) < settings.shadowAlignmentThreshold;
        if (p.stable) { if (++p.stableTicks > settings.shadowDriftDelayTicks) { p.drift = clamp(p.drift + (random.nextDouble() - .5) * .5, -2, 2); p.value = clamp(p.value + p.drift, -60, 60); } }
        else { p.stableTicks = 0; p.driftTicks = 0; p.drift = 0; }
    }
    private void spaceTick(Panel p) {
        if (random.nextDouble() < .01) p.value = clamp(p.value + (random.nextDouble() - .5) * .01, 0, 1);
        p.stable = p.value < settings.spaceStabilityThreshold;
        if (p.stable) { if (++p.stableTicks > settings.spaceDriftDelayTicks) { p.drift = clamp(p.drift + (random.nextDouble() - .5) * .02, -.2, .2); p.value = clamp(p.value + p.drift, 0, 1); } }
        else { p.stableTicks = 0; p.drift = 0; }
    }
    private void timeTick(Panel p) {
        p.angle = (p.angle + p.value * 6 + 360) % 360;
        p.targetAngle = (p.targetAngle + p.target * 6) % 360;
        boolean wasStable = p.stable;
        p.stable = Math.abs(p.value - p.target) < settings.timeSpeedThreshold;
        if (p.stable) {
            if (!wasStable) p.angle = p.targetAngle;
            if (++p.stableTicks > settings.timeDriftDelayTicks) p.target = clamp(p.target + (random.nextDouble() - .5) * .01, .1, 2);
        } else p.stableTicks = 0;
    }
    public static double shadowLength(Panel p) { return (70 - p.secondary) * .8; }
    private static double apart(double target, double min, double max) { return target < (min + max) / 2 ? max : min; }
    private double randomGrid(double min, double max, double step) {
        return clamp(min + random.nextInt((int) Math.ceil((max - min) / step) + 1) * step, min, max);
    }
    // Drift may leave a dial between stops. The next press selects the next physical stop
    // in that direction, so every generated target remains exactly reachable after drift.
    private static double stepGrid(double value, int direction, double min, double max, double step) {
        if (direction == 0) return value;
        double index = (value - min) / step;
        double next = direction > 0 ? Math.floor(index + 1e-8) + 1 : Math.ceil(index - 1e-8) - 1;
        return clamp(min + next * step, min, max);
    }
    private int nonzeroGravity() { int v; do { v = random.nextInt(11) - 5; } while (v == 0); return v; }
    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
}
