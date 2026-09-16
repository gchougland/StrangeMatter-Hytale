package com.hexvane.strangematter.research;

import java.util.*;

/** Original foundation map and tiered Forge branches with orthogonal PCB routing. */
public final class ResearchTreeLayout {
    public static final int WIDTH=728, NODE_WIDTH=128, NODE_HEIGHT=68, GRID=4;
    private static final Set<String> GENERAL=Set.of("research","field_scanner","anomaly_shards","anomaly_types","resonite","resonant_energy","tinfoil_hat","anomaly_resonator","reality_forge","gravity_anomalies","temporal_anomalies","spatial_anomalies","energy_anomalies","shadow_anomalies","cognitive_anomalies");
    private static final Map<String,int[]> ORIGINAL=Map.ofEntries(
        at("research",0,0),at("field_scanner",-80,0),at("anomaly_shards",80,0),at("anomaly_types",0,-80),
        at("resonite",-80,80),at("resonant_energy",80,80),at("tinfoil_hat",-160,80),at("anomaly_resonator",-160,0),at("reality_forge",-80,160),
        at("gravity_anomalies",-80,-160),at("temporal_anomalies",0,-160),at("spatial_anomalies",80,-160),
        at("energy_anomalies",-80,-240),at("shadow_anomalies",0,-240),at("cognitive_anomalies",80,-240),
        at("reality_forge_category",-80,240),at("resonance_condenser",-160,160),at("containment_basics",-80,400),
        at("echoform_imprinter",80,400),at("warp_gun",0,320),at("chrono_blister",0,480),at("graviton_hammer",-80,480),
        at("stasis_projector",80,240),at("rift_stabilizer",0,160),at("levitation_pad",-240,240),at("hoverboard",-240,400));
    // Power and field equipment stay at the left. Containment equipment and
    // transport machinery have separate lanes, with descendants below their parent.
    private static final Map<String,int[]> FORGE=Map.ofEntries(
        at("reality_forge_category",300,16),
        at("resonance_condenser",12,144),at("containment_basics",300,144),at("gravitic_transport",588,144),
        at("rift_stabilizer",12,280),at("stasis_projector",12,416),at("levitation_pad",12,552),
        at("echoform_imprinter",156,280),at("warp_gun",300,280),
        at("chrono_blister",156,416),at("graviton_hammer",300,416),at("hoverboard",228,552),
        at("resonant_separation",588,280),at("flux_smelting",588,416),at("pattern_assembly",588,552),
        at("resonant_battery_pack",156,688),at("arc_projection",300,688),at("gravitic_manipulation",12,824));
    private static Map.Entry<String,int[]> at(String id,int x,int y){return Map.entry(id,new int[]{x,y});}
    public record Node(ResearchNode research,int x,int y){public int cx(){return x+64;}public int cy(){return y+24;}}
    /** parent owns research state; source is only the visible routing anchor. */
    public record Segment(String parent,String child,String source,int x,int y,int width,int height){}
    public record Plan(List<Node> nodes,List<Segment> traces,int height){}
    public static Plan arrange(List<ResearchNode> catalog,String category){
        var nodes=new ArrayList<Node>();int extra=0;boolean general=category.equals("general");
        for(var node:catalog){
            if(!node.category().equals(category))continue;
            int[] original=general?(GENERAL.contains(node.id())?ORIGINAL.get(node.id()):null):FORGE.get(node.id());int x,y;
            if(original!=null){x=general?36+(original[0]/80+2)*176:original[0];y=general?8+(original[1]/80+3)*76:original[1];}
            else{x=12+(extra%5)*144;y=(general?484:960)+(extra/5)*92;extra++;}
            nodes.add(new Node(node,x,y));
        }
        int height=Math.max(464,nodes.stream().mapToInt(n->n.y+NODE_HEIGHT+16).max().orElse(464));
        var byId=new HashMap<String,Node>();for(var node:nodes)byId.put(node.research.id(),node);
        var segments=new ArrayList<Segment>();
        for(var child:nodes)for(String parentId:child.research.prerequisites()){
            var parent=byId.get(visibleParent(parentId,byId.keySet()));if(parent==null||parent==child)continue;
            var path=general?null:branchRoute(parent,child,nodes);
            if(path==null)path=route(parent,child,nodes,height);
            for(int i=1;i<path.size();i++){
                var a=path.get(i-1);var b=path.get(i);
                segments.add(new Segment(parentId,child.research.id(),parent.research.id(),Math.min(a[0],b[0]),Math.min(a[1],b[1]),Math.max(2,Math.abs(a[0]-b[0])+2),Math.max(2,Math.abs(a[1]-b[1])+2)));
                if(i<path.size()-1)segments.add(new Segment(parentId,child.research.id(),parent.research.id(),b[0]-2,b[1]-2,6,6));
            }
        }
        return new Plan(List.copyOf(nodes),List.copyOf(segments),height);
    }
    /** The category proxy never becomes a new gameplay prerequisite. */
    public static String visibleParent(String prerequisite,Set<String> visible){
        return !visible.contains(prerequisite)&&prerequisite.equals("reality_forge")&&visible.contains("reality_forge_category")?"reality_forge_category":prerequisite;
    }
    /** Separate buses make the root, containment and transport dependencies unambiguous. */
    private static List<int[]> branchRoute(Node start,Node end,List<Node> nodes){
        List<int[]> path;
        // The manipulator needs two independent technologies. Bring stasis in from
        // above and containment from the right; the traces meet only inside its card.
        if(end.research.id().equals("gravitic_manipulation")&&start.research.id().equals("stasis_projector"))
            path=List.of(new int[]{start.cx(),start.cy()},new int[]{148,start.cy()},new int[]{148,end.y-32},new int[]{end.cx(),end.y-32},new int[]{end.cx(),end.cy()});
        else if(end.research.id().equals("gravitic_manipulation")&&start.research.id().equals("containment_basics"))
            path=List.of(new int[]{start.cx(),start.cy()},new int[]{436,start.cy()},new int[]{436,end.cy()},new int[]{end.cx(),end.cy()});
        else if(start.research.id().equals("reality_forge_category")&&Set.of("resonance_condenser","rift_stabilizer","stasis_projector","levitation_pad").contains(end.research.id()))
            path=List.of(new int[]{start.cx(),start.cy()},new int[]{start.cx(),104},new int[]{4,104},new int[]{4,end.cy()},new int[]{end.cx(),end.cy()});
        else if(start.research.id().equals("containment_basics")||start.research.id().equals("gravitic_transport")){
            int lane=start.research.id().equals("containment_basics")?436:548;
            path=List.of(new int[]{start.cx(),start.cy()},new int[]{lane,start.cy()},new int[]{lane,end.y-32},new int[]{end.cx(),end.y-32},new int[]{end.cx(),end.cy()});
        }else return null;
        // A pack may add or move research cards. Use the general obstacle router
        // whenever a routing hint would intersect one of those unrelated cards.
        for(int i=1;i<path.size();i++){
            var a=path.get(i-1);var b=path.get(i);int x=Math.min(a[0],b[0]),y=Math.min(a[1],b[1]),right=Math.max(a[0],b[0])+2,bottom=Math.max(a[1],b[1])+2;
            if(x<4||right>WIDTH-4||y<4)return null;
            for(var other:nodes)if(other!=start&&other!=end&&x<other.x+NODE_WIDTH+4&&right>other.x-4&&y<other.y+NODE_HEIGHT+4&&bottom>other.y-4)return null;
        }
        return path;
    }
    /** Routes around unrelated complete node cards, including their labels. */
    private static List<int[]> route(Node start,Node end,List<Node> nodes,int height){
        int columns=WIDTH/GRID,rows=height/GRID;boolean[] blocked=new boolean[columns*rows];
        for(var node:nodes){
            if(node==start||node==end)continue;
            for(int y=Math.max(0,(node.y-4)/GRID);y<=Math.min(rows-1,(node.y+NODE_HEIGHT+4)/GRID);y++)
                for(int x=Math.max(0,(node.x-4)/GRID);x<=Math.min(columns-1,(node.x+NODE_WIDTH+4)/GRID);x++)blocked[y*columns+x]=true;
        }
        int from=(start.cy()/GRID)*columns+start.cx()/GRID,to=(end.cy()/GRID)*columns+end.cx()/GRID;
        int[] previous=new int[blocked.length];Arrays.fill(previous,-1);previous[from]=from;
        var queue=new ArrayDeque<Integer>();queue.add(from);
        while(!queue.isEmpty()&&previous[to]<0){
            int cell=queue.removeFirst(),x=cell%columns,y=cell/columns;
            for(int[] direction:new int[][]{{1,0},{0,1},{-1,0},{0,-1}}){
                int nx=x+direction[0],ny=y+direction[1];if(nx<1||ny<1||nx>=columns-1||ny>=rows-1)continue;
                int next=ny*columns+nx;if(blocked[next]||previous[next]>=0)continue;previous[next]=cell;queue.addLast(next);
            }
        }
        if(previous[to]<0)throw new IllegalStateException("Cannot route research connection "+start.research.id()+" to "+end.research.id());
        var cells=new ArrayList<Integer>();for(int current=to;;current=previous[current]){cells.add(current);if(current==from)break;}Collections.reverse(cells);
        var result=new ArrayList<int[]>();result.add(new int[]{start.cx(),start.cy()});
        for(int i=1;i<cells.size()-1;i++){
            int before=cells.get(i)-cells.get(i-1),after=cells.get(i+1)-cells.get(i);
            if(before!=after){int cell=cells.get(i);result.add(new int[]{cell%columns*GRID,cell/columns*GRID});}
        }
        result.add(new int[]{end.cx(),end.cy()});return result;
    }
    public static String icon(ResearchNode node){
        return switch(node.id()){
            case "resonant_separation"->"SM_Resonant_Separator";case "flux_smelting"->"SM_Flux_Furnace";case "gravitic_transport"->"SM_Gravitic_Tube";case "pattern_assembly"->"SM_Pattern_Assembler";
            case "gravitic_manipulation"->"SM_Gravitic_Manipulator";case "arc_projection"->"SM_Arc_Projector";
            case "research"->"SM_Research_Notes";case "anomaly_types"->"SM_Research_Tablet";case "anomaly_shards","gravity_anomalies"->"SM_Gravitic_Shard";
            case "temporal_anomalies"->"SM_Chrono_Shard";case "spatial_anomalies"->"SM_Spatial_Shard";case "energy_anomalies"->"SM_Energetic_Shard";
            case "shadow_anomalies"->"SM_Shade_Shard";case "cognitive_anomalies"->"SM_Insight_Shard";case "resonite"->"SM_Raw_Resonite";
            case "resonant_energy"->"SM_Resonant_Burner";case "reality_forge_category"->"SM_Reality_Forge";case "containment_basics"->"SM_Echo_Vacuum";
            default->"SM_"+Arrays.stream(node.id().split("_")).map(s->Character.toUpperCase(s.charAt(0))+s.substring(1)).collect(java.util.stream.Collectors.joining("_"));
        };
    }
    /** Compact card caption; the complete research title remains in details and native hover text. */
    public static String caption(ResearchNode node){
        return node.id().equals("resonant_energy")&&node.name().equals("Resonant Energy Fundamentals")?"Energy Fundamentals":node.name();
    }
}
