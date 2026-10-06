package dev.trackssimulate.track;

import java.util.*;

/** Regression for selected road wheels which do not actually touch the taut lower run. */
public final class TrackSupportTest {
    private static int checks;
    private static TrackPath.Wheel road(double x,double y,double radius,int hint) { return new TrackPath.Wheel(new TrackPath.Point(x,y),radius,hint,3,true); }
    private static TrackPath.Wheel roller(double x,double y,int hint) { return new TrackPath.Wheel(new TrackPath.Point(x,y),.2,hint); }
    private static void check(boolean pass,String message) { if(!pass)throw new AssertionError(message);checks++; }
    private static void checkSurface(List<TrackPath.Wheel> wheels,TrackPath.Path path) {
        var spans=TrackPath.contactSpans(wheels,path.wheelDirections(),.45);
        double length=0;
        for(int i=0;i<spans.size();i++) {
            var span=spans.get(i);length+=span.end().sub(span.start()).length();
            if(!path.contactWheels().contains(span.first())||!path.contactWheels().contains(span.second())) throw new AssertionError("Non-contact member receives belt load");
            if(span.end().sub(spans.get((i+1)%spans.size()).start()).length()>1e-7) throw new AssertionError("Physical belt gap");
        }
        check(Math.abs(length-path.length())<.05,"Render and physics follow the same contact subset");
        check(!path.crossing(),"Support path must not cross itself");
        check(path.wheelDirections().size()==wheels.size(),"All selected members retain saved direction slots");
    }
    public static void main(String[] args) {
        triangleGuides();
        for(int hint=0;hint<=4;hint++) for(boolean reverse:new boolean[]{false,true}) {
            List<TrackPath.Wheel> wheels=new ArrayList<>(List.of(road(-2,0,1,hint),road(0,.2,.45,hint),road(2,0,1,hint)));
            if(reverse)Collections.reverse(wheels);
            var path=TrackPath.build(wheels);
            check(path.contactWheels().equals(List.of(0,2)),"Smaller/raised middle road wheel detaches even with old click hints");
            check(TrackPath.contactSpans(wheels,path.wheelDirections(),.45).stream().filter(s->s.first()==0&&s.second()==2)
                .allMatch(s->Math.abs(s.start().y()+1)<1e-7&&Math.abs(s.end().y()+1)<1e-7),"Lower run bridges endpoints without upward deflection");
            checkSurface(wheels,path);
            int direction=reverse?-1:1;
            var restored=TrackPath.withDirections(wheels,List.of(direction,-direction,direction));
            check(restored.contactWheels().equals(path.contactWheels())&&restored.wheelDirections().get(1)==direction,"Legacy upper-wrapped middle road wheel corrected without unlinking");
            checkSurface(wheels,restored);
        }
        var three=List.of(road(-2,0,1,0),road(0,1,.6,0),road(2,0,1,0));
        TrackPath.Path last=TrackPath.build(three);
        for(int step=0;step<=40;step++) {
            double y=1-step*.05;
            var moving=List.of(three.get(0),road(0,y,.6,0),three.get(2));
            var path=TrackPath.deform(moving,last);
            check(path.contactWheels().contains(1)==(y<=-.4+1e-7),"Suspension enters contact only when lower surface reaches belt, y="+y);
            check(path.wheelDirections().equals(last.wheelDirections()),"Travel does not flip wheel wrap sign");
            checkSurface(moving,path);last=path;
        }
        var released=TrackPath.deform(three,last);
        check(!released.contactWheels().contains(1),"Compression releases the wheel again");checkSurface(three,released);
        var touching=List.of(road(-2,0,1,0),road(0,0,1,0),road(2,0,1,0));
        check(TrackPath.build(touching).contactWheels().equals(List.of(0,1,2)),"Collinear tangent road wheel remains load-bearing");
        var enlarged=List.of(three.get(0),road(0,.2,1.5,0),three.get(2));
        var largerPath=TrackPath.deform(enlarged,released);
        check(largerPath.contactWheels().contains(1),"Enlarged middle wheel pushes the run downward");checkSurface(enlarged,largerPath);
        for(boolean reverse:new boolean[]{false,true}) {
            var imageLayout=new ArrayList<>(List.of(road(0,0,.7,1),road(2,.25,.4,1),road(4,0,1,1),roller(4,3,0),roller(2,1.8,3),roller(0,3,0)));
            if(reverse)Collections.reverse(imageLayout);
            var path=TrackPath.build(imageLayout);
            int detached=reverse?4:1,returnRoller=reverse?1:4;
            check(!path.contactWheels().contains(detached),"Unequal-radius screenshot-like lower row bridges middle wheel");
            check(path.contactWheels().contains(returnRoller),"Upper routing roller and concave custom layout are retained");
            checkSurface(imageLayout,path);
            var detachedWheel=imageLayout.get(detached);
            for(int start=0;start<imageLayout.size();start++) {
                var shifted=new ArrayList<>(imageLayout);Collections.rotate(shifted,-start);
                var shiftedPath=TrackPath.build(shifted);
                check(!shiftedPath.contactWheels().contains(shifted.indexOf(detachedWheel)),"Selection may start at a detached controller wheel");
                check(Math.abs(shiftedPath.length()-path.length())<1e-7,"Changing the first selected wheel preserves route length");
                checkSurface(shifted,shiftedPath);
            }
        }
        List<TrackPath.Wheel> many=new ArrayList<>();many.add(road(0,0,1,0));
        for(int i=1;i<7;i++)many.add(road(i,.3+(i%2)*.1,.3,0));many.add(road(7,0,1,0));
        var manyPath=TrackPath.build(many);
        check(manyPath.contactWheels().equals(List.of(0,7)),"An entire lifted row detaches without renumbering endpoint ownership");checkSurface(many,manyPath);
        System.out.println("Track support: "+checks+" checks passed");
    }
    private static boolean inside(List<TrackPath.Point> polygon,TrackPath.Point p) {
        boolean inside=false;
        for(int i=0,j=polygon.size()-1;i<polygon.size();j=i++) {
            var a=polygon.get(i);var b=polygon.get(j);
            if((a.y()>p.y())!=(b.y()>p.y())&&p.x()<(b.x()-a.x())*(p.y()-a.y())/(b.y()-a.y())+a.x())inside=!inside;
        }
        return inside;
    }
    private static void triangleGuides() {
        var internalGuide=List.of(new TrackPath.Wheel(new TrackPath.Point(0,0),.4),
            new TrackPath.Wheel(new TrackPath.Point(2,1),.4,1,0,false,true),
            new TrackPath.Wheel(new TrackPath.Point(4,0),.4),new TrackPath.Wheel(new TrackPath.Point(4,4),.4),new TrackPath.Wheel(new TrackPath.Point(0,4),.4));
        var internalPath=TrackPath.build(internalGuide);
        check(internalPath.wheelDirections().get(1)==-internalPath.winding(),"Interior guide retains explicit reverse wrap");
        checkSurface(internalGuide,internalPath);
        for(boolean reverse:new boolean[]{false,true})for(int start=0;start<8;start++) {
            // Previously the shorter non-crossing solution excluded the right-hand guide.
            List<TrackPath.Wheel> wheels=new ArrayList<>();
            var left=new TrackPath.Wheel(new TrackPath.Point(0,0),.45,1,0,false,true);
            var right=new TrackPath.Wheel(new TrackPath.Point(5.5,0),.45,0,0,false,true);
            var top=roller(4,2,0);
            wheels.add(left);for(int i=1;i<=5;i++)wheels.add(road(i,-1,.61772,0));wheels.add(right);wheels.add(top);
            if(reverse)Collections.reverse(wheels);Collections.rotate(wheels,-start);
            var path=TrackPath.build(wheels);
            for(var guide:List.of(left,right,top))check(inside(path.points(),guide.center()),"Triangle encloses exposed guide regardless of start/order");
            checkSurface(wheels,path);
            List<Integer> legacy=new ArrayList<>(path.wheelDirections());legacy.set(wheels.indexOf(right),-path.winding());
            var restored=TrackPath.withDirections(wheels,legacy);
            check(inside(restored.points(),right.center()),"Saved incorrect outer wrap repairs without reconnecting");
            checkSurface(wheels,restored);
            for(double y:new double[]{-.5,0,.2,0,-.5,-1}) {
                List<TrackPath.Wheel> moving=new ArrayList<>();
                for(var wheel:wheels)moving.add(wheel.roadSupport()?road(wheel.center().x(),y,wheel.radius(),0):wheel);
                path=TrackPath.deform(moving,path);
                for(var guide:List.of(left,right,top))check(inside(path.points(),guide.center()),"Suspension detach/rejoin retains exposed guides, y="+y);
                checkSurface(moving,path);
            }
        }
    }
}
