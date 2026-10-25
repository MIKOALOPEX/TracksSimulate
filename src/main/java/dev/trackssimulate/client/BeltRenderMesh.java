package dev.trackssimulate.client;

import com.mojang.blaze3d.vertex.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Cached geometry only. Scrolling texture phase and lighting remain per draw. */
public final class BeltRenderMesh {
    public static List<float[]> bake(dev.trackssimulate.trackpath.BeltSurface surface) {
        var result=new ArrayList<float[]>(surface.spans().size()*4);
        for(var span:surface.spans())for(var face:surface.faces(span)) {
            Vec3 a=dev.trackssimulate.assembly.AssemblyGeometry.vector(face[0]).add(.5,.5,.5);
            Vec3 b=dev.trackssimulate.assembly.AssemblyGeometry.vector(face[1]).add(.5,.5,.5);
            Vec3 c=dev.trackssimulate.assembly.AssemblyGeometry.vector(face[2]).add(.5,.5,.5);
            Vec3 d=dev.trackssimulate.assembly.AssemblyGeometry.vector(face[3]).add(.5,.5,.5);
            quad(result,a,b,c,d,span.start(),span.end());
        }
        return List.copyOf(result);
    }
    private static void quad(List<float[]> result,Vec3 a,Vec3 b,Vec3 c,Vec3 d,double start,double end) {
        Vec3 n=b.subtract(a).cross(c.subtract(a)).normalize();
        float[] q=new float[23];q[0]=(float)n.x;q[1]=(float)n.y;q[2]=(float)n.z;
        Vec3[] points={a,b,c,d};
        for(int i=0;i<4;i++){q[3+3*i]=(float)points[i].x;q[4+3*i]=(float)points[i].y;q[5+3*i]=(float)points[i].z;q[15+2*i]=i==1||i==2?1:0;q[16+2*i]=(float)(i<2?start:end);}
        result.add(q);
    }
    public static void draw(List<float[]> mesh,PoseStack pose,VertexConsumer out,float phase,int color,int light,int overlay) {
        for(var q:mesh)for(int i=0;i<4;i++)out.addVertex(pose.last(),q[3+3*i],q[4+3*i],q[5+3*i])
            .setColor((color>>16)&255,(color>>8)&255,color&255,255).setUv(q[15+2*i],q[16+2*i]+phase)
            .setOverlay(overlay).setLight(light).setNormal(pose.last(),q[0],q[1],q[2]);
    }
    private BeltRenderMesh() {}
}
