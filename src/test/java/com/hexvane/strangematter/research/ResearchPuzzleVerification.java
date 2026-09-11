package com.hexvane.strangematter.research;

import java.util.*;

/** Exhaustive control reachability, using production transitions rather than a copy of puzzle math. */
public final class ResearchPuzzleVerification {
    private record Move(int axis, int direction) {}
    private record Edge(int from, Move move) {}
    private static final Map<Integer, Move> SPACE = spacePolicy(false), NO_CLIP = spacePolicy(true);
    private static ResearchNode node(ResearchType type) { return new ResearchNode("puzzle_test", "general", "Instrument", "", Map.of(type, 1), List.of()); }
    private static int key(ResearchSession.Panel p) { return ((int) p.value + 6) * 13 + (int) p.secondary + 6; }

    private static Map<Integer, Move> spacePolicy(boolean avoidClipping) {
        var reverse = new HashMap<Integer, List<Edge>>();
        var probe = new ResearchSession(node(ResearchType.SPACE), 1); probe.begin();
        var p = probe.panel(ResearchType.SPACE);
        for (int x = -6; x <= 6; x++) for (int y = -6; y <= 6; y++) for (int axis = 0; axis < 2; axis++) for (int direction : new int[]{-1, 1}) {
            p.value = x; p.secondary = y; p.cooldown = 0;
            int from = key(p);
            require(probe.control(ResearchType.SPACE, "axis", axis), "Real axis selection");
            require(probe.control(ResearchType.SPACE, "warp", direction), "Real coupled warp control");
            require(p.value == Math.rint(p.value) && p.secondary == Math.rint(p.secondary) && Math.abs(p.value) <= 6 && Math.abs(p.secondary) <= 6,
                    "All controls and boundary overshoots stay on bounded exact stops");
            // Leave clipped moves out of the solver: the tolerance must make the actual
            // lattice solvable without visiting a wall to change its arithmetic residue.
            if (!avoidClipping || (p.value - x == direction * (axis == 0 ? 2 : 1) && p.secondary - y == direction * (axis == 1 ? 2 : 1)))
                reverse.computeIfAbsent(key(p), ignored -> new ArrayList<>()).add(new Edge(from, new Move(axis, direction)));
        }
        var policy = new HashMap<Integer, Move>(); var seen = new HashSet<Integer>(); var queue = new ArrayDeque<Integer>();
        for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++){int goal=(x+6)*13+y+6;seen.add(goal);queue.add(goal);}
        while (!queue.isEmpty()) for (var edge : reverse.getOrDefault(queue.removeFirst(), List.of())) if (seen.add(edge.from)) {
            policy.put(edge.from, edge.move); queue.addLast(edge.from);
        }
        if(!avoidClipping)require(seen.size() == 169, "Every possible lattice, including corners reached by overshoot, reaches the accepted margin");
        return policy;
    }
    static void controlSpace(ResearchSession game) {
        var p = game.panel(ResearchType.SPACE); var move = NO_CLIP.getOrDefault(key(p),SPACE.get(key(p)));
        if (move != null && p.cooldown == 0) {
            game.control(ResearchType.SPACE, "axis", move.axis);
            require(game.control(ResearchType.SPACE, "warp", move.direction), "Reachability path uses a real accepted control");
        }
    }
    static boolean controlSpaceOneClick(ResearchSession game) {
        var p=game.panel(ResearchType.SPACE);var move=NO_CLIP.getOrDefault(key(p),SPACE.get(key(p)));
        if(move==null||p.cooldown>0)return false;
        return game.control(ResearchType.SPACE,p.selectedAxis==move.axis?"warp":"axis",p.selectedAxis==move.axis?move.direction:move.axis);
    }
    public static void main(String[] args) { verify(); }
    public static void verify() {
        int longest = 0;
        for (int x = -6; x <= 6; x++) for (int y = -6; y <= 6; y++) {
            var game = new ResearchSession(node(ResearchType.SPACE), 21); game.begin(); var p = game.panel(ResearchType.SPACE);
            p.value = x; p.secondary = y; int presses = 0;
            while (key(p) != 84 && presses++ < 25) { controlSpace(game); for (int tick = 0; tick < 5; tick++) game.tick(); }
            require(key(p) == 84, "Overshoot remains solvable through real debounced input: " + x + "," + y);
            longest = Math.max(longest, presses);
            for (int tick = 0; tick < 1000 && game.state() == ResearchSession.State.RUNNING; tick++) {
                if(tick%5==0)controlSpace(game);game.tick();
            }
            require(game.state() == ResearchSession.State.SUCCESS, "An aligned lattice completes with normal corrective input if a random disturbance occurs");
        }
        int longestStart=0;
        for (int seed = 0; seed < 1000; seed++) {
            var game = new ResearchSession(node(ResearchType.SPACE), seed); game.begin(); var p = game.panel(ResearchType.SPACE);
            require(p.value * p.secondary < 0 && Math.abs(p.value) >= 2 && Math.abs(p.secondary) >= 2, "Every start has meaningful opposing bends");
            var directions = new HashSet<Integer>(); var axes = new HashSet<Integer>();int presses=0;
            while (key(p) != 84) {
                var move = NO_CLIP.get(key(p));
                require(move!=null||(Math.abs(p.value)<=1&&Math.abs(p.secondary)<=1),"Every generated start has a route which avoids boundary clipping");
                if(move!=null){
                    presses++;
                    directions.add(move.direction); axes.add(move.axis);double oldX=p.value,oldY=p.secondary;
                    controlSpace(game);
                    require(p.value-oldX==move.direction*(move.axis==0?2:1)&&p.secondary-oldY==move.direction*(move.axis==1?2:1),"Generated puzzles solve without boundary clipping or hidden residue resets");
                }
                for (int i = 0; i < 5; i++) game.tick();
            }
            require(directions.size() == 2 && axes.size() == 2, "Every generated solution needs both directions and both axes");
            require(presses<=6,"Every new puzzle reaches its margin in at most six bends without hitting a wall");longestStart=Math.max(longestStart,presses);
            for (int direction : new int[]{-1, 1}) {
                var spam = new ResearchSession(node(ResearchType.SPACE), seed); spam.begin(); var q = spam.panel(ResearchType.SPACE);
                for (int press = 0; press < 40; press++) {
                    spam.control(ResearchType.SPACE, "axis", press % 2); spam.control(ResearchType.SPACE, "warp", direction);
                    for (int tick = 0; tick < 5; tick++) spam.tick();
                    require(!q.stable, "One direction cannot win, even while switching axes");
                }
            }
        }
        for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++){
            var game=new ResearchSession(node(ResearchType.SPACE),9);game.begin();var p=game.panel(ResearchType.SPACE);p.value=x;p.secondary=y;game.tick();
            require(p.stable&&p.value==0&&p.secondary==0,"Every accepted one-stop near alignment visibly settles to exact zero");
        }
        for(var outside:new int[][]{{2,0},{0,2},{-2,0},{0,-2},{1,2},{2,1}}){
            var game=new ResearchSession(node(ResearchType.SPACE),9);game.begin();var p=game.panel(ResearchType.SPACE);p.value=outside[0];p.secondary=outside[1];game.tick();
            require(!p.stable,"Tolerance does not accept a bend two stops away");
        }
        var targets = new HashSet<Double>();
        for (double step : new double[]{.1, .07, .3, 1}) for (int seed = 0; seed < 500; seed++) {
            var settings = new ResearchSettings(); settings.timeSpeedAdjustment = step;
            var game = new ResearchSession(node(ResearchType.TIME), seed, settings); game.begin(); var p = game.panel(ResearchType.TIME);
            targets.add(p.target);
            require(Math.abs(p.target) >= .399 && Math.abs(p.value - p.target) >= .599 && Math.abs(p.value-p.target)<=1+1e-8 && p.angle != p.targetAngle, "Chronal starts have moving targets and a nontrivial reachable speed gap of at most 1x");
            for (int press = 0; press < 100 && Math.abs(p.value - p.target) > 1e-8; press++) {
                require(game.control(ResearchType.TIME, "speed", p.value < p.target ? 1 : -1), "A real speed control reaches the target stop");
                for (int tick = 0; tick < 5; tick++) game.tick();
            }
            require(Math.abs(p.value - p.target) < 1e-8 && Math.abs(p.angle - p.targetAngle) < 1e-8, "Every generated speed and exact hand overlay is reachable, including nondividing step sizes");
        }
        require(targets.stream().anyMatch(t -> t < 0) && targets.stream().anyMatch(t -> t > 1.5) && targets.size() > 20, "Chronal targets vary in speed and direction");
        require(new ResearchSettings().cognitionCueTicks() == 20, "Default cognition cue lasts one second, with the independent dark gap retained");
        verifyBoundedClockRelapses();verifyImmediateWaves();
        System.out.println("PASS: all 169 lattice states recover in at most " + longest + " presses; 1,000 two-direction/two-axis starts need at most "+longestStart+" bends without clipping and reject spam; 2,000 chronal targets reach exact phase and speed.");
    }
    private static void verifyBoundedClockRelapses(){
        for(double step:new double[]{.1,.07,.3,1})for(double tolerance:new double[]{.15,1})for(int seed=0;seed<128;seed++){
            var settings=new ResearchSettings();settings.timeSpeedAdjustment=step;settings.timeSpeedThreshold=tolerance;
            try{settings.validate();}catch(IllegalArgumentException rejected){
                require(tolerance==1&&step!=1&&rejected.getMessage().contains("within 1x"),"Incompatible custom dial/tolerance combinations fail clearly rather than violating the speed bound: "+rejected.getMessage());continue;
            }
            var game=new ResearchSession(node(ResearchType.TIME),seed,settings);game.begin();var p=game.panel(ResearchType.TIME);
            double initialGap=Math.abs(p.value-p.target);
            require(initialGap+1e-8>=tolerance&&initialGap<=1+1e-8,"Every supported custom dial starts within the hard 1x bound");
            game.tick();require(!p.stable,"Custom boundary tolerances do not create a solved start through floating point rounding");
            p.value=p.target;p.angle=p.targetAngle;p.stable=true;p.stableTicks=100;p.driftTicks=100_000;
            double matched=p.value;
            for(int tick=0;tick<20&&p.stable;tick++)game.tick();
            require(!p.stable&&Math.abs(p.target-matched)+1e-8>=tolerance,"A stochastic clock disturbance really exits the configured tolerance");
            require(Math.abs(p.target-matched)<=1+1e-8,"Every later clock disturbance respects the hard 1x bound");
        }
    }
    private static void verifyImmediateWaves(){
        for(int seed=0;seed<500;seed++){
            var game=new ResearchSession(node(ResearchType.ENERGY),seed);game.begin();var p=game.panel(ResearchType.ENERGY);
            for(int press=0;press<40&&!p.stable;press++){
                if(Math.abs(p.value-p.target)>=.1)require(game.control(ResearchType.ENERGY,"amplitude",p.value<p.target?1:-1),"Real amplitude control");
                else require(game.control(ResearchType.ENERGY,"period",p.secondary<p.targetSecondary?1:-1),"Real period control");
                boolean aligned=Math.abs(p.value-p.target)<.1&&Math.abs(p.secondary-p.targetSecondary)<.1;
                game.tick();require(p.stable==aligned,"The first aligned wave tick is stable, without qualification time");
            }
            require(p.stable&&p.stableTicks==1&&game.destabilizationTicks(ResearchType.ENERGY)==120,"Wave locks immediately and uses the slightly longer six-second mean");
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

