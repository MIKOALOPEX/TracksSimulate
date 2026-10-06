package dev.trackssimulate.physics;

import dev.trackssimulate.physics.contact.*;

/** Geometry regression: a tiled solid floor must not manufacture side walls at internal seams. */
public final class VoxelContactTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    public static void main(String[] args) {
        int oldSideNormals=0,contacts=0;
        for(double yaw:new double[]{.07,.63,1.25})for(double tilt:new double[]{-.12,.04,.10})for(int xi=-10;xi<=10;xi++)for(int zi=-10;zi<=10;zi++) {
            V3 t=new V3(Math.cos(yaw)*Math.cos(tilt),Math.sin(tilt),Math.sin(yaw)*Math.cos(tilt));
            V3 axle=new V3(-Math.sin(yaw),0,Math.cos(yaw));
            var strip=new ContactBox(new V3(xi*.06,.06,zi*.06),t,axle,t.cross(axle),new V3(.225,.37355,.125));
            for(int x=-1;x<=0;x++)for(int z=-1;z<=0;z++) {
                var tile=new ContactBox(new V3(x+.5,-.5,z+.5),V3.X,V3.Y,V3.Z,new V3(.5,.5,.5));
                var old=strip.surfaceContact(tile,.025);
                if(old!=null&&Math.abs(old.normal().y())<.7)oldSideNormals++;
                var hit=strip.surfaceContact(tile,.025,ContactBox.POS_Y);
                if(hit!=null){contacts++;check(hit.normal().sub(V3.Y).length()<1e-8,"Interior floor seams must resolve along exposed top face");}
            }
        }
        check(oldSideNormals>0,"Fixture must reproduce the previous incorrect side normals");
        check(contacts>1000,"The fix must retain floor support, not discard all contacts");
        var cube=new ContactBox(new V3(0,0,0),V3.X,V3.Y,V3.Z,new V3(.5,.5,.5));
        var sideStrip=new ContactBox(new V3(.53,0,0),V3.Z,V3.X,V3.Y,new V3(.2,.1,.04));
        var wall=sideStrip.surfaceContact(cube,0,ContactBox.POS_X);
        check(wall!=null&&wall.normal().sub(V3.X).length()<1e-8,"A real exposed wall still provides side collision");
        check(sideStrip.surfaceContact(cube,0,0)==null,"Fully buried voxel has no external collision surface");
        var separated=new ContactBox(new V3(4,.05,0),V3.Z,V3.X,V3.Y,new V3(.2,.1,.04));
        check(separated.surfaceContact(cube,.025,ContactBox.POS_Y)==null,"All SAT separation axes remain active");
        V3 x=new V3(.8,.6,0),y=new V3(-.6,.8,0);
        var rotatedCube=new ContactBox(V3.ZERO,x,y,V3.Z,new V3(.5,.5,.5));
        var rotatedStrip=new ContactBox(y.mul(.53),V3.Z,x,y,new V3(.2,.1,.04));
        var rotatedHit=rotatedStrip.surfaceContact(rotatedCube,.025,ContactBox.POS_Y);
        check(rotatedHit!=null&&rotatedHit.normal().sub(y).length()<1e-8,"Exposed-face masks are in the obstacle local frame");
        System.out.println("Voxel geometry: "+checks+" assertions; previous side normals="+oldSideNormals+", retained top contacts="+contacts+". Not a vehicle simulation.");
    }
}
