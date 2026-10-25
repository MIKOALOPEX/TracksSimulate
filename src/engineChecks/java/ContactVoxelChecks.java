import dev.trackssimulate.physics.ContactValidity;
import dev.trackssimulate.trackpath.BeltSurface.V;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import dev.trackssimulate.physics.SuspensionContactSolver;
import java.util.List;

/** Actual Minecraft VoxelShape.clip; no level, server or client is started. */
public class ContactVoxelChecks {
    public static void main(String[] args){
        var terrain=Shapes.box(0,0,0,1,1,1);var block=new BlockPos(0,-1,0);
        var outside=terrain.clip(new Vec3(.5,.39,.5),new Vec3(.5,-.05,.5),block);
        var inside=terrain.clip(new Vec3(.5,-.34,.5),new Vec3(.5,.1,.5),block);
        if(outside==null||outside.isInside()||outside.getDirection()!=net.minecraft.core.Direction.UP)throw new AssertionError("real ground entry");
        if(inside==null||!inside.isInside()||inside.getDirection()!=net.minecraft.core.Direction.DOWN)throw new AssertionError("reproduce synthetic reverse normal");
        if(ContactValidity.entry(inside.isInside(),new V(0,1,0),new V(0,-1,0)))throw new AssertionError("synthetic face accepted");
        var floor=contact(1,-.25,0);var falseCeiling=contact(-1,.25,1);
        SuspensionContactSolver.solve(List.of(floor,falseCeiling));double opposing=floor.supportImpulse()+falseCeiling.supportImpulse();
        var corrected=contact(1,-.25,0);SuspensionContactSolver.solve(List.of(corrected));
        if(Math.abs(corrected.supportImpulse()-9*.25)>1e-8||opposing<corrected.supportImpulse()*10)
            throw new AssertionError("flat-ground opposing contact regression");
        var deep=terrain.clip(new Vec3(.5,.34,.5),new Vec3(.5,-.54,.5),block);
        if(deep==null||deep.isInside()||deep.getDirection()!=net.minecraft.core.Direction.UP)throw new AssertionError("wheel-center cast recovers real surface beyond old .4 backtrack");
        System.out.println("Production solver with pseudo-ceiling impulse="+opposing+"; valid floor only="+corrected.supportImpulse());
        System.out.println("Actual Minecraft voxel check passed: exterior UP entry; interior DOWN pseudo-hit rejected. No game launched.");
    }
    static SuspensionContactSolver.Row row(double x,double y,double z,double v,double target){return new SuspensionContactSolver.Row(x,y,z,0,0,0,1./9,new double[]{1,0,0,0,1,0,0,0,1},v,target,0);}
    static SuspensionContactSolver.Contact contact(double y,double v,double target){return new SuspensionContactSolver.Contact(row(0,y,0,v,target),null,row(1,0,0,0,0),row(0,0,1,0,0),1000,0,0);}
}
