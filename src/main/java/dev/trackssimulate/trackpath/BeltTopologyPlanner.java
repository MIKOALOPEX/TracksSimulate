package dev.trackssimulate.trackpath;

import java.util.*;
import static dev.trackssimulate.trackpath.OrderedBeltPath.*;

/** Role-based contact topology, separate from tangent tessellation.
 * ROAD keeps the carrying side; DRIVE anchors the oriented loop.
 * GUIDE supports the unpressed reference route if it contacts that route;
 * otherwise it presses inward. The reference settles the entire guide run
 * before any pressing side is assigned. Every selected guide stays a node.
 * No weighted side votes, shortest-route substitutions or candidate-count cap.
 */
final class BeltTopologyPlanner {
    static Result plan(List<Wheel> wheels) {
        int carry=carryingSide(wheels);
        boolean[] contacts=referenceContacts(wheels,carry);
        var sides=new ArrayList<Integer>(wheels.size());
        for(int i=0;i<wheels.size();i++)sides.add(contacts[i]?carry:-carry);
        // Failure is a real diagnostic for this role intent, not permission to
        // silently turn a pressing guide into a supporting guide (or vice versa).
        return resolve(wheels,sides);
    }
    /** Establish the unpressed carrying route BEFORE inserting inward guides.
     * This prevents one guide's reversal from reclassifying the next guide.
     * Remove only guides whose external tangents already meet without wheel
     * contact; retain all carrying/drive anchors. Repeat to settle an entire
     * run of guides, not independent three-wheel votes. Removed guides remain
     * mandatory nodes in the final route and must obtain real contact there.
     */
    private static boolean[] referenceContacts(List<Wheel> wheels,int carry) {
        var active=new ArrayList<Integer>();for(int i=0;i<wheels.size();i++)active.add(i);
        boolean changed=true;
        while(changed&&active.size()>2) {
            changed=false;
            for(int at=0;at<active.size();at++) {
                int index=active.get(at);var mid=wheels.get(index);
                if(mid.role()!=Role.GUIDE)continue;
                var before=wheels.get(active.get(Math.floorMod(at-1,active.size())));
                var after=wheels.get(active.get((at+1)%active.size()));
                Edge in=tangent(before,mid,carry,carry),out=tangent(mid,after,carry,carry);
                if(in==null||out==null)continue;
                P entry=in.b().sub(mid.center()),exit=out.a().sub(mid.center());
                double sweep=positive(carry*(Math.atan2(exit.y(),exit.x())-Math.atan2(entry.y(),entry.x())));
                if(sweep>Math.PI&&intersection(in,out)!=null) {
                    active.remove(at);changed=true;break;
                }
            }
        }
        boolean[] anchors=new boolean[wheels.size()];for(int i:active)anchors[i]=true;
        return anchors;
    }
    private static int carryingSide(List<Wheel> wheels) {
        // Translation-invariant signed area; canonical caller order resolves
        // zero-area straight rows deterministically. No world-down side lock.
        P origin=wheels.getFirst().center();double area=0,scale=0;
        for(int i=0;i<wheels.size();i++) {
            var a=wheels.get(i).center().sub(origin);var b=wheels.get((i+1)%wheels.size()).center().sub(origin);
            area+=a.cross(b);scale+=a.length()*b.length();
        }
        return area < -1e-10*Math.max(1,scale)?-1:1;
    }
}
