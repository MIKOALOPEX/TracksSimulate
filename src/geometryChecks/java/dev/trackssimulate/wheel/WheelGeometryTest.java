package dev.trackssimulate.wheel;

import dev.trackssimulate.track.*;
import java.util.List;

public final class WheelGeometryTest {
    private static int checks;
    private static void check(boolean value,String message) { checks++;if(!value) throw new AssertionError(message); }
    private static void rejects(Runnable action,String message) {
        try { action.run();throw new AssertionError(message); } catch(IllegalArgumentException expected) { checks++; }
    }
    private static TrackPath.Wheel wheel(double x,double y,double radius) { return new TrackPath.Wheel(new TrackPath.Point(x,y),radius); }
    public static void main(String[] args) {
        var g=new WheelGeometry(2,1.5,1,2,3);
        var other=new WheelGeometry(.5,.75,-1,-2,-3);
        check(other.applyGroupDimensions(g,false,false).equals(new WheelGeometry(.5,1.5,-1,-2,-3)),"Shared width preserves other wheel radius and offsets");
        check(other.applyGroupDimensions(g,false,true).equals(new WheelGeometry(2,1.5,-1,-2,-3)),"Group radius changes dimensions without moving other wheels");
        check(other.applyGroupDimensions(g,true,false).equals(new WheelGeometry(.5,1.5,1,2,3)),"Group suspension/offset scope must not force radius sync");
        check(other.applyGroupDimensions(g,true,true).equals(g),"Current wheel gets all its edited dimensions");
        check(g.offset(0).equals(new WheelGeometry.Offset(3,2,1)),"X axle anchor coordinates");
        check(g.offset(1).equals(new WheelGeometry.Offset(1,3,2)),"Y axle anchor coordinates");
        check(g.offset(2).equals(new WheelGeometry.Offset(1,2,3)),"Z axle anchor coordinates");
        check(g.halfExtents(0,.5,.8).equals(new WheelGeometry.Offset(.8*1.5*.5,1,1)),"Width scales along X axle, radius across it");
        check(g.halfExtents(1,.5,.8).equals(new WheelGeometry.Offset(1,.8*1.5*.5,1)),"Vertical axle dimensions");
        check(g.halfExtents(2,.5,.8).equals(new WheelGeometry.Offset(1,1,.8*1.5*.5)),"Z axle dimensions");
        for(int index=0;index<5;index++) for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            double[] v={1,1,0,0,0};v[index]=bad;
            rejects(()->new WheelGeometry(v[0],v[1],v[2],v[3],v[4]),"Non-finite geometry accepted");
        }
        rejects(()->new WheelGeometry(.24,1,0,0,0),"Radius lower bound");
        rejects(()->new WheelGeometry(3.01,1,0,0,0),"Radius upper bound");
        rejects(()->new WheelGeometry(1,.24,0,0,0),"Width lower bound");
        rejects(()->new WheelGeometry(1,3.01,0,0,0),"Width upper bound");
        rejects(()->new WheelGeometry(1,1,4.1,0,0),"Forward bound");
        rejects(()->new WheelGeometry(1,1,0,-4.1,0),"Vertical bound");
        rejects(()->new WheelGeometry(1,1,0,0,4.1),"Axial bound");
        check(new WheelGeometry(.25,3,-4,4,0).radiusScale()==.25,"Boundary geometry accepted");
        var original=TrackPath.build(List.of(wheel(0,0,.5),wheel(4,0,.5)));
        var enlarged=TrackPath.withDirections(List.of(wheel(0,0,1),wheel(4,0,1)),original.wheelDirections());
        check(Math.abs(enlarged.length()-(8+2*Math.PI))<.02,"Scaled wheels change the actual belt perimeter");
        var translated=TrackLayoutValidation.validate(List.of(new TrackLayoutValidation.Wheel(3,wheel(2,-1,.5)),new TrackLayoutValidation.Wheel(3,wheel(6,-1,.5))),original.wheelDirections());
        check(Math.abs(translated.length()-original.length())<1e-8,"Group offset preserves the loop geometry");
        rejects(()->TrackLayoutValidation.validate(List.of(new TrackLayoutValidation.Wheel(0,wheel(0,0,.5)),new TrackLayoutValidation.Wheel(.25,wheel(4,0,.5))),original.wheelDirections()),"Single-wheel axial change must reject before commit");
        rejects(()->TrackLayoutValidation.validate(List.of(new TrackLayoutValidation.Wheel(Double.NaN,wheel(0,0,.5)),new TrackLayoutValidation.Wheel(0,wheel(4,0,.5))),original.wheelDirections()),"Invalid plane rejected");
        rejects(()->TrackLayoutValidation.validate(List.of(new TrackLayoutValidation.Wheel(0,wheel(0,0,.5)),new TrackLayoutValidation.Wheel(0,wheel(TrackPath.MAX_SPAN+1,0,.5))),original.wheelDirections()),"Offset cannot exceed span budget");
        System.out.println("WheelGeometry: "+checks+" checks passed");
    }
}
