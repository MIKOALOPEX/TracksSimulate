import dev.trackssimulate.physics.*;
import dev.trackssimulate.trackpath.BeltSurface.V;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ContactValidityChecks {
    static int checks;
    static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    public static void main(String[] args){
        var up=new V(0,1,0);var down=up.mul(-1);var box=new ContactValidity.Box(new V(0,-1,0),new V(1,0,1));
        check(!ContactValidity.entry(true,up,down),"internal origin cannot fabricate downward face against flat ground");
        check(ContactValidity.entry(false,down,up),"external downward ray supports wheel");
        check(!ContactValidity.entry(false,up,up),"back face cannot push into solid");
        var top=new V(.5,0,.5);
        check(ContactValidity.face(box,top,up),"real top face");
        check(!ContactValidity.face(box,new V(.5,-.4,.5),down),"interior ray-start location is not block bottom face");
        check(ContactValidity.covers(box,new V(.5,-.1,.5),top,up),"penetrating contact still covers finite face");
        check(!ContactValidity.covers(box,new V(1.2,-.1,.5),top,up),"moving past block edge invalidates old plane");
        var links=new ConstraintLinks<String>();var valid=new AtomicBoolean(true);
        links.add("A","B",valid::get);links.add("B","C",()->true);links.add("X",null,()->true);links.add("Y",null,()->true);
        check(links.from("A",256,2048).nodes().equals(Set.of("A","B","C")),"transitive constraint connectivity");
        check(links.from("X",256,2048).nodes().equals(Set.of("X")),"ground does not connect unrelated vehicles");
        check(!links.from("A",2,2048).complete(),"node budget exhaustion explicit");
        check(!links.from("A",256,1).complete(),"edge budget exhaustion explicit");
        valid.set(false);check(links.from("A",256,2048).nodes().equals(Set.of("A")),"removed joint immediately excluded");
        links.prune(64);check(links.from("B",256,2048).nodes().equals(Set.of("B","C")),"pruning preserves remaining cluster");
        links.add("C","B",()->true);check(links.from("B",256,2048).complete(),"cycles terminate");
        System.out.println("Contact validity and constraint graph checks passed: "+checks);
    }
}
