package com.hexvane.strangematter.research;

import java.util.*;

/** Original tablet coordinates, fitted to a native screen with orthogonal PCB routing. */
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
    private static Map.Entry<String,int[]> at(String id,int x,int y){return Map.entry(id,new int[]{x,y});}
    public record Node(ResearchNode research,int x,int y){public int cx(){return x+64;}public int cy(){return y+24;}}
    public record Segment(String parent,String child,int x,int y,int width,int height){}
    public record Plan(List<Node> nodes,List<Segment> traces,int height){}
    public static Plan arrange(List<ResearchNode> catalog,String category){
        var nodes=new ArrayList<Node>();int extra=0;boolean general=category.equals("general");
        for(var node:catalog){
            if(!node.category().equals(category))continue;
            int[] original=GENERAL.contains(node.id())==general?ORIGINAL.get(node.id()):null;int x,y;
            if(original!=null){x=general?36+(original[0]/80+2)*176:12+(original[0]/80+3)*144;y=general?8+(original[1]/80+3)*76:48+(original[1]/80-2)*76;}
            else{x=12+(extra%5)*144;y=484+(extra/5)*92;extra++;}
            nodes.add(new Node(node,x,y));
        }
        int height=Math.max(464,nodes.stream().mapToInt(n->n.y+NODE_HEIGHT+16).max().orElse(464));
        var byId=new HashMap<String,Node>();for(var node:nodes)byId.put(node.research.id(),node);
        var segments=new ArrayList<Segment>();
        for(var child:nodes)for(String parentId:child.research.prerequisites()){
            var parent=byId.get(parentId);if(parent==null)continue;
            var path=route(parent,child,nodes,height);
            for(int i=1;i<path.size();i++){
                var a=path.get(i-1);var b=path.get(i);
                segments.add(new Segment(parentId,child.research.id(),Math.min(a[0],b[0]),Math.min(a[1],b[1]),Math.max(2,Math.abs(a[0]-b[0])+2),Math.max(2,Math.abs(a[1]-b[1])+2)));
                if(i<path.size()-1)segments.add(new Segment(parentId,child.research.id(),b[0]-2,b[1]-2,6,6));
            }
        }
        return new Plan(List.copyOf(nodes),List.copyOf(segments),height);
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
            case "research"->"SM_Research_Notes";case "anomaly_types"->"SM_Research_Tablet";case "anomaly_shards","gravity_anomalies"->"SM_Gravitic_Shard";
            case "temporal_anomalies"->"SM_Chrono_Shard";case "spatial_anomalies"->"SM_Spatial_Shard";case "energy_anomalies"->"SM_Energetic_Shard";
            case "shadow_anomalies"->"SM_Shade_Shard";case "cognitive_anomalies"->"SM_Insight_Shard";case "resonite"->"SM_Raw_Resonite";
            case "resonant_energy"->"SM_Resonant_Burner";case "reality_forge_category"->"SM_Reality_Forge";case "containment_basics"->"SM_Echo_Vacuum";
            default->"SM_"+Arrays.stream(node.id().split("_")).map(s->Character.toUpperCase(s.charAt(0))+s.substring(1)).collect(java.util.stream.Collectors.joining("_"));
        };
    }
}
