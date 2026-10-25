import dev.trackssimulate.trackpath.OrderedBeltPath;
import java.util.*;
import static dev.trackssimulate.trackpath.OrderedBeltPath.*;

/** Headless checks of the actual routing core; no simulated game world. */
public final class OrderedBeltChecks {
    private static int assertions;
    static void require(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    static List<Wheel> wheels(double[][] values){var r=new ArrayList<Wheel>();for(var v:values)r.add(new Wheel(new P(v[0],v[1]),v.length>2?v[2]:.55));return r;}
    static void role(List<Wheel> wheels,int index,Role role){var w=wheels.get(index);wheels.set(index,new Wheel(w.center(),w.radius(),role));}
    static void inspect(Result r,String name) {
        require(r.valid(),name+": "+r.error());require(r.tangentError()<1e-8,name+" tangent");
        double length=0;
        for(int i=0;i<r.segments().size();i++) {
            var a=r.segments().get(i);var b=r.segments().get((i+1)%r.segments().size());
            require(a.b().sub(b.a()).length()<1e-7,name+" continuity");
            require(Double.isFinite(a.length())&&a.length()>=0,name+" finite");
            if(a.arc()) {
                var incoming=r.segments().get((i+r.segments().size()-1)%r.segments().size());
                var d=incoming.b().sub(incoming.a());var radial=a.a().sub(a.center());
                require(Math.abs(d.dot(radial))<1e-7,name+" incoming tangent");
            }
            length+=a.length();
        }
        require(Math.abs(length-r.length())<1e-7,name+" length");
    }
    static void cyclicInvariance(List<Wheel> installation,List<Wheel> pose,String name,boolean convex) {
        var baseline=planInstalled(installation,pose);inspect(baseline,name);
        for(int direction:new int[]{1,-1})for(int start=0;start<installation.size();start++) {
            var mounts=new ArrayList<Wheel>();var current=new ArrayList<Wheel>();
            for(int i=0;i<installation.size();i++) {
                int at=Math.floorMod(start+direction*i,installation.size());mounts.add(installation.get(at));current.add(pose.get(at));
            }
            var r=planInstalled(mounts,current);inspect(r,name+" start="+start+" dir="+direction);
            require(Math.abs(r.length()-baseline.length())<1e-7,name+" same physical perimeter");
            for(int i=0;i<mounts.size();i++) {
                int at=Math.floorMod(start+direction*i,mounts.size());
                require(r.sides().get(i)==direction*baseline.sides().get(at),name+" node winding invariant");
            }
            if(convex) {
                require(r.sides().stream().distinct().count()==1,name+" convex boundary cannot exclude a selected run");
                for(var wheel:current)require(contains(r,wheel.center()),name+" wheel centre must be inside belt");
            }
        }
    }
    // Independent geometric observation, not an assertion of the solver's side
    // values. Fine arc chords have error far smaller than the wheel radius.
    static boolean contains(Result path,P point) {
        int winding=0;
        for(var segment:path.segments()) {
            int steps=segment.arc()?Math.max(1,(int)Math.ceil(Math.abs(segment.sweep())/.04)):1;
            for(int i=0;i<steps;i++) {
                var a=segment.at((double)i/steps);var b=segment.at((double)(i+1)/steps);
                double cross=b.sub(a).cross(point.sub(a));
                if(a.y()<=point.y()&&b.y()>point.y()&&cross>0)winding++;
                if(a.y()>point.y()&&b.y()<=point.y()&&cross<0)winding--;
            }
        }
        return winding!=0;
    }
    static void realContact(List<Wheel> nodes,Result path,int index,String label) {
        var w=nodes.get(index);
        var incoming=path.segments().stream().filter(s->!s.arc()&&s.to()==index).findFirst().orElseThrow();
        var outgoing=path.segments().stream().filter(s->!s.arc()&&s.from()==index).findFirst().orElseThrow();
        require(Math.abs(incoming.b().sub(w.center()).length()-w.radius())<1e-7,label+" entry on wheel");
        require(Math.abs(outgoing.a().sub(w.center()).length()-w.radius())<1e-7,label+" exit on wheel");
        require(Math.abs(incoming.b().sub(incoming.a()).dot(incoming.b().sub(w.center())))<1e-7,label+" incoming tangent");
        require(Math.abs(outgoing.b().sub(outgoing.a()).dot(outgoing.a().sub(w.center())))<1e-7,label+" outgoing tangent");
    }
    public static void main(String[] args) {
        var pair=wheels(new double[][]{{0,0},{5,0}});var two=plan(pair);inspect(two,"two");
        require(Math.abs(two.length()-(10+2*Math.PI*.55))<1e-7,"stadium circumference");
        var triangle=wheels(new double[][]{{0,0},{1,-1},{2,-1},{3,-1},{4,-1},{5,0},{3,3}});
        var path=plan(triangle);inspect(path,"triangle road row");
        require(path.sides().stream().distinct().count()==1,"collinear road wheels must retain the common side");
        var concave=wheels(new double[][]{{0,0},{2,-1},{4,0},{6,-1},{8,0},{4,4}});
        role(concave,2,Role.GUIDE);
        var concavePath=plan(concave);inspect(concavePath,"concave");
        require(concavePath.sides().equals(List.of(1,1,-1,1,1,1)),"two-frontier reference fixture sides");
        var reverse=new ArrayList<>(triangle);Collections.reverse(reverse);
        var reversed=plan(reverse);inspect(reversed,"reversed");
        require(Math.abs(path.length()-reversed.length())<1e-7,"reverse perimeter");
        inspect(plan(wheels(new double[][]{{0,0,.6},{3,-1,.75},{6,0,1},{3,4,.55}})),"unequal radii");
        var shifted=triangle.stream().map(w->new Wheel(w.center().add(new P(2000,-800)),w.radius())).toList();
        var moved=resolve(shifted,path.sides());inspect(moved,"translation");
        require(Math.abs(moved.length()-path.length())<1e-6,"translation invariant");
        for(int step=0;step<=20;step++) {
            var pose=new ArrayList<>(triangle);pose.set(6,new Wheel(new P(3,3+step*.02),.55));
            var resolved=resolve(pose,path.sides());inspect(resolved,"continuous pose "+step);
            require(resolved.sides().equals(path.sides()),"pose must not reselect sides");
        }
        require(!plan(wheels(new double[][]{{0,0},{0,0}})).valid(),"coincident unsupported tangent must report failure");
        require(!plan(wheels(new double[][]{{0,0},{Double.NaN,1}})).valid(),"NaN rejected");
        require(!resolve(pair,List.of(0,1)).valid(),"bad topology rejected");
        require(!plan(wheels(new double[][]{{0,0},{400,0}})).valid(),"length budget enforced");
        require(!resolve(wheels(new double[][]{{0,0},{4,4},{0,4},{4,0}}),List.of(1,1,1,1)).valid(),"crossed order rejected");
        // 2026-10-13 repair workflow: a fresh wheel has no old offsets/travel.
        // The old live-pose planner commits an inward side; applying the old
        // settings then makes that internal tangent impossible at 1-block pitch.
        var repairedMounts=new ArrayList<Wheel>();var freshPose=new ArrayList<Wheel>();
        var settledPose=new ArrayList<Wheel>();
        for(int i=0;i<9;i++) {
            boolean drive=i==0||i==8;double radius=drive?.8:.75;
            repairedMounts.add(new Wheel(new P(i,drive?0:-.75),radius));
            freshPose.add(new Wheel(new P(i,drive||i==3?0:-1.35),radius));
            settledPose.add(new Wheel(new P(i,drive?0:-1.35),radius));
        }
        var obsolete=plan(freshPose);
        require(obsolete.sides().stream().distinct().count()==1,"road role never flips on a temporary high wheel");
        require(resolve(settledPose,obsolete.sides()).valid(),"role-constrained winding survives settling");
        var repaired=planInstalled(repairedMounts,settledPose);inspect(repaired,"repaired after explicit settings sync");
        require(repaired.sides().stream().distinct().count()==1,"sync must replace obsolete inward side");
        var duringSettle=planInstalled(repairedMounts,freshPose);
        require(duringSettle.sides().equals(repaired.sides()),"transient extension must not select permanent topology");
        for(int frame=0;frame<=20;frame++) {
            var pose=new ArrayList<>(settledPose);pose.set(3,new Wheel(new P(3,-.75-frame*.03),.75));
            var r=planInstalled(repairedMounts,pose);inspect(r,"post-sync settling "+frame);
            require(r.sides().equals(repaired.sides()),"settling keeps installation winding");
        }
        var reversedMounts=new ArrayList<>(repairedMounts);Collections.reverse(reversedMounts);
        var reversedPose=new ArrayList<>(settledPose);Collections.reverse(reversedPose);
        inspect(planInstalled(reversedMounts,reversedPose),"reverse repair order");
        require(planInstalled(concave,concave).sides().equals(concavePath.sides()),"deliberate installed concavity retains BnT sides");
        require(!planInstalled(pair,triangle).valid(),"pose member mismatch rejected");
        // Exact 14:42:33 log, including the seven collinear road wheels. Old
        // two-frontier logic chose [1,1,-1,-1,-1,-1,-1,-1,-1,1] and called it valid.
        var reportedMounts=wheels(new double[][]{{0,0,.6},{-5,-2,.6},{-4,-2.75},{-3,-2.75},{-2,-2.75},{-1,-2.75},{0,-2.75},{1,-2.75},{2,-2.75},{3,-2,.6}});
        var reportedPose=wheels(new double[][]{{0,0,.6},{-5,-2,.6},{-4,-3.3194094702017765},{-3,-3.317568340751137},{-2,-3.315727211300512},{-1,-3.3138860818498728},{0,-3.3120449523992335},{1,-3.3102038229486084},{2,-3.308362693497969},{3,-2,.6}});
        cyclicInvariance(reportedMounts,reportedPose,"reported triangle",true);
        // Second log: player started at the other end of the road row and
        // reversed the selection. Full extension at the time of the screenshot.
        var secondMounts=wheels(new double[][]{{0,-.75},{-1,-.75},{-2,-.75},{-3,-.75},{-4,-.75},{-5,-.75},{-6,-.75},{-7,0,.6},{-2,2,.6},{1,0,.6}});
        var secondPose=new ArrayList<>(secondMounts);
        for(int i=0;i<7;i++)secondPose.set(i,new Wheel(secondPose.get(i).center().add(new P(0,-.6)),.55));
        cyclicInvariance(secondMounts,secondPose,"reported reverse row",true);
        cyclicInvariance(concave,concave,"intentional concave guide",false);
        // A family, not just one screenshot: various row sizes up to 64 nodes,
        // paired sizes, overhangs and apex positions, plus every start/direction.
        for(int row:new int[]{2,7,16,61})for(double radius:new double[]{.25,.55,.95}) {
            var family=new ArrayList<Wheel>();family.add(new Wheel(new P(-1,0),radius+.05));
            for(int i=0;i<row;i++)family.add(new Wheel(new P(i,-.75),radius));
            family.add(new Wheel(new P(row,0),radius+.05));family.add(new Wheel(new P(row*.35,2),radius+.05));
            cyclicInvariance(family,family,"triangle row="+row+" r="+radius,true);
        }
        for(int frame=0;frame<=20;frame++) {
            var pose=new ArrayList<>(reportedMounts);
            for(int i=2;i<9;i++)pose.set(i,new Wheel(pose.get(i).center().add(new P(0,-frame*.03)),.55));
            var r=planInstalled(reportedMounts,pose);inspect(r,"triangle extension "+frame);
            require(r.sides().equals(plan(reportedMounts).sides()),"extension cannot invert road row");
        }
        // Actual failure class from 2026-10-12 latest.log: committed + sides,
        // almost-collinear suspension poses, 359.99-degree loops, meshSpans=0.
        for(int frame=0;frame<160;frame++) {
            var pose=new ArrayList<Wheel>();
            for(int i=0;i<8;i++) {
                double y=i==0||i==7?0:-.6+.15*Math.sin(i*1.8+frame*.025)+.08*i*Math.sin(frame*.012);
                pose.add(new Wheel(new P(i,y),i==0||i==7?.6:.55));
            }
            var r=resolve(pose,Collections.nCopies(8,1));inspect(r,"suspension replay "+frame);
            require(r.segments().stream().noneMatch(s->s.arc()&&s.from()>0&&s.from()<7&&Math.abs(s.sweep())>Math.PI),"spurious intermediate full turn");
        }
        for(double epsilon:new double[]{-1e-3,-1e-5,-1e-7,0,1e-7,1e-5,1e-3}) {
            var r=resolve(wheels(new double[][]{{0,0,.6},{1,-.6},{2,-.6+epsilon},{3,-.6},{4,0,.6}}),Collections.nCopies(5,1));
            inspect(r,"zero-angle crossing "+epsilon);
            require(r.segments().stream().noneMatch(s->s.arc()&&s.from()==2&&Math.abs(s.sweep())>Math.PI),"zero must not jump to full wrap");
        }
        // M2 continuous dimensions: actual kernel over the entire GUI radius
        // range, not fixed small/medium/large presets. Independent analytic
        // stadium perimeter and reversal checks catch radius propagation errors.
        for(int step=0;step<=35;step++) {
            double radius=.25+step*.05;
            var equal=wheels(new double[][]{{0,0,radius},{8,0,radius}});
            var r=plan(equal);inspect(r,"continuous radius "+radius);
            require(Math.abs(r.length()-(16+2*Math.PI*radius))<1e-7,"continuous circumference");
            var unequal=wheels(new double[][]{{0,0,radius},{8,0,.4},{4,5,.25}});
            cyclicInvariance(unequal,unequal,"mixed guide radii "+radius,true);
        }
        // A concave return roller remains a genuine reverse-wrap node.
        var guide=wheels(new double[][]{{0,0,.8},{2,-1,.75},{4,0,.4},{6,-1,.75},{8,0,.8},{4,4,.4}});
        role(guide,2,Role.GUIDE);
        var guided=plan(guide);inspect(guided,"return roller mixed-size concavity");
        require(guided.sides().get(2)!=guided.sides().get(1),"guide may reverse wrap");
        // Unsupported containment is rejected, rather than producing NaN meshes.
        require(!plan(wheels(new double[][]{{0,0,2},{.5,0,.25}})).valid(),"oversized radius must reject contained tangent");
        // 2026-10-14 real assembly c4684998, guide selected first.
        // Centre polygon is convex here, but the small guide is entirely
        // below the neighbours' upper tangent. It must PRESS the belt down.
        var pressingGuide=wheels(new double[][]{{0,0,.4},{3,1,1},{6,-1,1},
            {5,-1.75,.55},{4,-1.75,.55},{3,-1.75,.55},{2,-1.75,.55},
            {1,-1.75,.55},{0,-1.75,.55},{-1,-1.75,.55},{-2,-1,.6}});
        role(pressingGuide,0,Role.GUIDE);role(pressingGuide,1,Role.DRIVE);role(pressingGuide,2,Role.DRIVE);role(pressingGuide,10,Role.DRIVE);
        var pressingPose=new ArrayList<>(pressingGuide);
        for(int i=3;i<=9;i++)pressingPose.set(i,new Wheel(pressingPose.get(i).center().add(new P(0,-.6)),.55));
        var pressed=planInstalled(pressingGuide,pressingPose);inspect(pressed,"reported pressing guide");
        require(pressed.sides().get(0)==1&&pressed.sides().get(1)==-1&&pressed.sides().get(10)==-1,
            "selected inset guide must reverse wrap rather than stick to upper tangent");
        var guideArcs=pressed.segments().stream().filter(seg->seg.arc()&&seg.from()==0).toList();
        require(guideArcs.size()==1,"selected guide remains an actual circular contact, not skipped or cornered");
        require(guideArcs.getFirst().at(.5).y()<0,"guide contact must run underneath");
        require(Math.abs(guideArcs.getFirst().sweep())<Math.PI,"guide must not add an extra loop");
        cyclicInvariance(pressingGuide,pressingPose,"reported pressing guide invariance",false);
        // Independent horizontal reference: neighbour upper tangent is y=1.
        // Sweep radius and height on either side of contact, including tangent.
        for(double radius:new double[]{.25,.4,.8,1.2})for(double clearance:new double[]{-.5,-.05,0,.05,.5}) {
            double height=1-radius+clearance;
            var nodes=wheels(new double[][]{{-4,0,1},{0,height,radius},{4,0,1},{4,-4,1},{-4,-4,1}});
            role(nodes,1,Role.GUIDE);
            var r=plan(nodes);inspect(r,"guide clearance r="+radius+" c="+clearance);
            require(r.sides().get(1)==(clearance<0?1:-1),"reference tangent chooses pressure/contact side");
            realContact(nodes,r,1,"clearance guide");

            if(clearance<0) {
                var arcs=r.segments().stream().filter(seg->seg.arc()&&seg.from()==1).toList();
                require(arcs.size()==1&&arcs.getFirst().at(.5).y()<height,"inset wheel has underside circular wrap");
            }
            cyclicInvariance(nodes,nodes,"guide clearance invariance",false);
        }
        // No world-down special case: rotate, translate and uniformly scale
        // the log fixture; the guide must press along the transformed underside.
        for(double theta:new double[]{Math.PI/2,Math.PI,-.7})for(double scale:new double[]{.7,1.5}) {
            var transformed=pressingGuide.stream().map(w->{var q=w.center();return new Wheel(
                new P(scale*(q.x()*Math.cos(theta)-q.y()*Math.sin(theta))+123,
                      scale*(q.x()*Math.sin(theta)+q.y()*Math.cos(theta))-51),w.radius()*scale,w.role());}).toList();
            var r=plan(transformed);inspect(r,"transformed pressing guide");
            require(r.sides().equals(plan(pressingGuide).sides()),"guide side independent of world orientation/scale");
            require(Math.abs(r.length()-plan(pressingGuide).length()*scale)<1e-7,"transformed perimeter");
        }
        // 2026-10-15 09:31:26 exact logged installation: same-height smaller
        // road wheels overlap their neighbours. A preferred inner tangent may
        // not exist; that is NOT evidence that the entire outer route is invalid.
        var reported17=wheels(new double[][]{{0,0,.6},{-1,0,.55},{-2,0,.55},{-3,0,.55},
            {-4,0,.55},{-5,0,.55},{-6,0,.55},{-7,0,.55},{-8,0,.6},{-6,1,.4},{-3,2,.6}});
        role(reported17,0,Role.DRIVE);role(reported17,8,Role.DRIVE);role(reported17,9,Role.GUIDE);role(reported17,10,Role.DRIVE);
        cyclicInvariance(reported17,reported17,"2026-10-15 logged full selection",false);
        var withoutGuide=new ArrayList<>(reported17);withoutGuide.remove(9);
        cyclicInvariance(withoutGuide,withoutGuide,"2026-10-15 no guide",true);
        // All ordered subsets of the seven road wheels, including the 3-node
        // upper path and progressively populated return run. No node-count rule.
        for(int mask=0;mask<128;mask++) {
            var subset=new ArrayList<Wheel>();subset.add(reported17.get(0));
            for(int i=0;i<7;i++)if((mask&(1<<i))!=0)subset.add(reported17.get(i+1));
            subset.addAll(reported17.subList(8,11));
            cyclicInvariance(subset,subset,"road subset "+mask,false);
            var r=plan(subset);
            int guideIndex=subset.size()-2;
            require(r.sides().get(guideIndex)!=r.sides().get(guideIndex-1),"inset guide keeps opposite side across return-run selection changes");
            require(r.segments().stream().anyMatch(seg->seg.arc()&&seg.from()==guideIndex&&Math.abs(seg.sweep())<Math.PI),"guide keeps circular contact");
            for(int j=0;j<subset.size();j++)if(j!=guideIndex)require(r.sides().get(j).equals(r.sides().get(0)),"return run never inverts mask="+mask+" sides="+r.sides());
        }
        var clickOrder=new ArrayList<Wheel>();
        clickOrder.add(reported17.get(8));clickOrder.add(reported17.get(9));clickOrder.add(reported17.get(10));
        clickOrder.add(reported17.get(0));for(int i=1;i<=7;i++)clickOrder.add(reported17.get(i));
        for(int count=3;count<=clickOrder.size();count++) {
            var prefix=List.copyOf(clickOrder.subList(0,count));
            cyclicInvariance(prefix,prefix,"incremental clicks "+count,false);
        }
        for(int step=0;step<=20;step++) {
            var pose=new ArrayList<>(reported17);
            for(int i=1;i<=7;i++)pose.set(i,new Wheel(pose.get(i).center().add(new P(0,-step*.03)),.55));
            inspect(planInstalled(reported17,pose),"new log suspension "+step);
        }
        // 09:47:55 exact 12-wheel report: the lower collinear guide used
        // to be silently replaced by a zero-wrap external corner.
        var reported18=wheels(new double[][]{{0,0,.4},{3,0,.6},{6,-2,.6},
            {5,-2,.55},{4,-2,.55},{3,-2,.55},{2,-2,.55},{1,-2,.55},{0,-2,.55},{-1,-2,.55},
            {-2,-2,.6},{-1,-1,.4}});
        role(reported18,0,Role.GUIDE);role(reported18,11,Role.GUIDE);
        role(reported18,1,Role.DRIVE);role(reported18,2,Role.DRIVE);role(reported18,10,Role.DRIVE);
        var contactPath=plan(reported18);inspect(contactPath,"latest two-guide log");
        require(contactPath.sides().get(0)==-1&&contactPath.sides().get(11)==1,"outer guide and inset pressing guide have distinct contact sides");
        for(int i=0;i<12;i++)if(reported18.get(i).role()!=Role.ROAD)realContact(reported18,contactPath,i,"latest anchor "+i);
        require(!resolve(reported18,Collections.nCopies(12,-1)).valid(),"old zero-contact guide topology must not be accepted");
        var reported18Pose=new ArrayList<>(reported18);
        for(int i=3;i<=9;i++)reported18Pose.set(i,new Wheel(reported18.get(i).center().add(new P(0,-.6)),.55));
        cyclicInvariance(reported18,reported18Pose,"latest log installed/pose",false);
        for(int mask=0;mask<128;mask++) {
            var subset=new ArrayList<>(reported18.subList(0,3));
            for(int i=0;i<7;i++)if((mask&(1<<i))!=0)subset.add(reported18.get(3+i));
            subset.addAll(reported18.subList(10,12));
            var r=plan(subset);inspect(r,"two guides road subset "+mask);
            int last=subset.size()-1;
            require(r.sides().get(0)==-1&&r.sides().get(last)==1,"guide topology independent of road count "+mask);
            realContact(subset,r,0,"outer guide subset");realContact(subset,r,last,"pressing guide subset");
        }
        // Consecutive guides share one reference run, including straight
        // zero-angle contact. They are not classified using a neighbour whose
        // winding was already changed by the previous decision.
        var guideRun=wheels(new double[][]{{-4,0,1},{-1,.5,.25},{1,.5,.25},{4,0,1},{4,-4,1},{-4,-4,1}});
        role(guideRun,1,Role.GUIDE);role(guideRun,2,Role.GUIDE);
        var run=plan(guideRun);inspect(run,"consecutive inset guides");
        require(run.sides().get(1)==1&&run.sides().get(2)==1,"both selected inset guides press");
        realContact(guideRun,run,1,"first guide in run");realContact(guideRun,run,2,"second guide in run");
        cyclicInvariance(guideRun,guideRun,"consecutive guide invariance",false);
        for(double theta:new double[]{Math.PI/2,Math.PI,-.7})for(double scale:new double[]{.7,1.5}) {
            var transformed=reported18.stream().map(w->{var q=w.center();return new Wheel(
                new P(scale*(q.x()*Math.cos(theta)-q.y()*Math.sin(theta))+123,
                      scale*(q.x()*Math.sin(theta)+q.y()*Math.cos(theta))-51),w.radius()*scale,w.role());}).toList();
            var r=plan(transformed);inspect(r,"transformed two-guide run");
            require(r.sides().equals(contactPath.sides()),"two-guide reference independent of orientation/scale");
            for(int index:new int[]{0,11})realContact(transformed,r,index,"transformed guide contact");
        }
        // Same coordinates and radii, different explicit role: the road must
        // not acquire the guide's reverse wrap merely because it is small.
        var roadVersion=new ArrayList<>(reported18);role(roadVersion,11,Role.ROAD);
        var roadPath=plan(roadVersion);inspect(roadPath,"same shape different role");
        require(roadPath.sides().get(11).equals(roadPath.sides().get(10)),"road keeps carrier side");
        require(!roadPath.sides().equals(contactPath.sides()),"role participates in topology");
        require(!resolve(pair,List.of(1,-1)).valid(),"two-wheel internal tangent bow tie rejected");
        // Equal-height rows at the supported node budget: count and wheel
        // radius differences must not invert a selected straight return run.
        for(int count:new int[]{2,7,16,61})for(double radius:new double[]{.25,.55,.95}) {
            var line=new ArrayList<Wheel>();line.add(new Wheel(new P(-1,0),radius+.05));
            for(int i=0;i<count;i++)line.add(new Wheel(new P(i,0),radius));
            line.add(new Wheel(new P(count,0),radius+.05));
            line.add(new Wheel(new P(count*.4,3),radius+.05));
            cyclicInvariance(line,line,"collinear selection count="+count+" r="+radius,true);
        }
        System.out.println("Ordered belt checks passed: "+assertions+" assertions. These are geometry checks, not gameplay acceptance.");
        // Explicitly retain the user's unresolved high-displacement scenario.
        var severe=wheels(new double[][]{{0,0,.6},{1,-1.347},{2,-.763},{3,-1.35},{4,-.824},{5,-1.307},{6,-1.35},{7,0,.6}});
        var severePath=plan(severe);
        System.out.println("OPEN PATH-002 high-displacement fixture: maxArcDegrees="+severePath.segments().stream().filter(Segment::arc).mapToDouble(s->Math.toDegrees(Math.abs(s.sweep()))).max().orElse(0)+"; not visually accepted.");
    }
}
