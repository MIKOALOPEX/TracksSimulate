package dev.trackssimulate.track;

import dev.trackssimulate.physics.contact.V3;

public final class BeltSurfaceNormalTest {
    public static void main(String[] args) {
        // A physical rectangle/triangle edge must have the same lighting normal when
        // traversed backwards. Include all three axis embeddings and sloping edges.
        double[][] edges={{1,0},{0,1},{-1,0},{0,-1},{3,2},{-2,3}};
        int checks=0;
        for(int axis=0;axis<3;axis++) for(double[] edge:edges) for(int winding:new int[]{-1,1}) {
            V3 n=BeltSurfaceNormal.outward(edge[0],edge[1],winding,axis);
            V3 reverse=BeltSurfaceNormal.outward(-edge[0],-edge[1],-winding,axis);
            if(n.sub(reverse).length()>1e-12||Math.abs(n.length()-1)>1e-12)throw new AssertionError("Reversing belt changed normal");
            V3 tangent=switch(axis){case 0->new V3(0,edge[1],edge[0]);case 1->new V3(edge[0],0,edge[1]);default->new V3(edge[0],edge[1],0);};
            if(Math.abs(n.dot(tangent))>1e-12)throw new AssertionError("Not perpendicular");
            checks++;
        }
        if(!BeltSurfaceNormal.outward(1,0,1,2).equals(new V3(0,-1,0)))throw new AssertionError("Bottom edge must face down");
        var wheels=java.util.List.of(wheel(0,0),wheel(5,0),wheel(5,3),wheel(0,3));
        for(int axis=0;axis<3;axis++) {
            var expected=normals(TrackPath.build(wheels),axis);
            for(int start=0;start<4;start++) for(int direction:new int[]{-1,1}) {
                var ordered=new java.util.ArrayList<TrackPath.Wheel>();
                for(int i=0;i<4;i++) ordered.add(wheels.get(Math.floorMod(start+i*direction,4)));
                var actual=normals(TrackPath.build(ordered),axis);
                if(!expected.keySet().equals(actual.keySet()))throw new AssertionError("Changed physical belt surface");
                expected.forEach((key,n)->{if(n.sub(actual.get(key)).length()>1e-8)throw new AssertionError("Changed lighting after reordering selection");});
                checks++;
            }
        }
        System.out.println("BeltSurfaceNormalTest: "+checks+" reversed-edge checks passed");
    }
    private static TrackPath.Wheel wheel(double x,double y) {
        return new TrackPath.Wheel(new TrackPath.Point(x,y),.6,0,0,false,true);
    }
    private static java.util.Map<String,V3> normals(TrackPath.Path path,int axis) {
        var result=new java.util.HashMap<String,V3>();
        for(int i=0;i<path.points().size();i++) {
            var p=path.points().get(i);var q=path.points().get((i+1)%path.points().size());
            if(q.sub(p).length()<1e-8)continue;
            String key=Math.round((p.x()+q.x())*50000)+":"+Math.round((p.y()+q.y())*50000);
            result.put(key,BeltSurfaceNormal.outward(q.x()-p.x(),q.y()-p.y(),path.winding(),axis));
        }
        return result;
    }
}
