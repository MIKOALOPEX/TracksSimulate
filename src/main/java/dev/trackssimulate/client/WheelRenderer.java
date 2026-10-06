package dev.trackssimulate.client;

import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import dev.trackssimulate.track.TrackPath;
import dev.trackssimulate.physics.contact.*;
import dev.trackssimulate.wheel.WheelBlockEntity;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.*;
import java.util.List;
import java.util.ArrayList;

public final class WheelRenderer implements BlockEntityRenderer<WheelBlockEntity> {
    private static final ResourceLocation BELT=ResourceLocation.parse("trackssimulate:textures/block/belt.png");
    public WheelRenderer(BlockEntityRendererProvider.Context context) {}
    @Override public void render(WheelBlockEntity wheel,float partial,PoseStack stack,MultiBufferSource buffers,int light,int overlay) {
        Vec3 centre=wheel.wheelCenter(partial).subtract(Vec3.atLowerCornerOf(wheel.getBlockPos()));
        Vec3 mount=new Vec3(.5,.5,.5),delta=centre.subtract(mount);
        if(wheel.strutVisible()&&delta.lengthSqr()>.0016) {
            VertexConsumer strut=buffers.getBuffer(RenderType.entitySolid(WheelMeshes.BASE));
            Vec3 direction=delta.normalize(),side=direction.cross(Math.abs(direction.y)>.9?new Vec3(1,0,0):new Vec3(0,1,0)).normalize().scale(.06);
            Vec3 up=direction.cross(side).normalize().scale(.06);
            Vec3 a=mount.subtract(side).subtract(up),b=mount.add(side).subtract(up),c=mount.add(side).add(up),d=mount.subtract(side).add(up);
            Vec3 e=a.add(delta),f=b.add(delta),g=c.add(delta),h=d.add(delta);
            face(stack,strut,a,b,f,e,0,1,light,overlay);face(stack,strut,b,c,g,f,0,1,light,overlay);
            face(stack,strut,c,d,h,g,0,1,light,overlay);face(stack,strut,d,a,e,h,0,1,light,overlay);
            face(stack,strut,d,c,b,a,0,1,light,overlay);face(stack,strut,e,f,g,h,0,1,light,overlay);
        }
        if(wheel.wheelVisible()) {
            stack.pushPose();stack.translate(centre.x,centre.y,centre.z);
            if(wheel.axis()==Direction.Axis.Z) stack.mulPose(Axis.YP.rotationDegrees(-90));
            if(wheel.axis()==Direction.Axis.Y) stack.mulPose(Axis.ZP.rotationDegrees(90));
            stack.scale((float)wheel.geometry().widthScale(),(float)wheel.geometry().radiusScale(),(float)wheel.geometry().radiusScale());
            stack.mulPose(Axis.XP.rotationDegrees((float)(wheel.renderAngle(partial)%360)));
            WheelMeshes.draw(wheel.kind(),wheel.material(),wheel.getLevel(),wheel.getBlockPos(),stack,buffers,light,overlay);
            stack.popPose();
        }
        if(!wheel.controller()) return;
        TrackPath.Path path=wheel.renderPath(partial);if(path==null) return;
        VertexConsumer out=buffers.getBuffer(RenderType.entityCutoutNoCull(BELT));
        // A different first-selected wheel must not select a different light sample.
        var lightPos=wheel.nodes().stream().min(java.util.Comparator.comparingLong(net.minecraft.core.BlockPos::asLong)).orElse(wheel.getBlockPos());
        int beltLight=LevelRenderer.getLightColor(wheel.getLevel(),lightPos);
        List<TrackPath.Point> points=path.points();double distance=0,halfWidth=wheel.beltWidth()/2;
        Vec3 axle=switch(wheel.axis()) { case X->new Vec3(halfWidth,0,0);case Y->new Vec3(0,halfWidth,0);case Z->new Vec3(0,0,halfWidth); };
        Vec3 plane=switch(wheel.axis()) {case X->new Vec3(wheel.geometry().axial(),0,0);case Y->new Vec3(0,wheel.geometry().axial(),0);case Z->new Vec3(0,0,wheel.geometry().axial());};
        for(int i=0;i<points.size();i++) {
            TrackPath.Point p=points.get(i),q=points.get((i+1)%points.size()),d=q.sub(p);
            double length=d.length();if(length<1e-8) continue;
            Vec3 a=point(p,wheel.axis()).add(plane),b=point(q,wheel.axis()).add(plane);
            V3 normal=dev.trackssimulate.track.BeltSurfaceNormal.outward(d.x(),d.y(),path.winding(),wheel.axis().ordinal());
            Vec3 outward=new Vec3(normal.x(),normal.y(),normal.z());
            Vec3 n=outward.scale(wheel.beltSettings().thickness()/2);
            Vec3 a0=a.subtract(axle).subtract(n),a1=a.add(axle).subtract(n),a2=a.add(axle).add(n),a3=a.subtract(axle).add(n);
            Vec3 b0=b.subtract(axle).subtract(n),b1=b.add(axle).subtract(n),b2=b.add(axle).add(n),b3=b.subtract(axle).add(n);
            float u=(float)((distance-wheel.renderPhase(partial))%1),v=u+(float)length;
            face(stack,out,a3,a2,b2,b3,u,v,beltLight,overlay,outward);
            face(stack,out,a1,a0,b0,b1,u,v,beltLight,overlay,outward.scale(-1));
            face(stack,out,a0,a3,b3,b0,u,v,beltLight,overlay,axle.normalize().scale(-1));
            face(stack,out,a2,a1,b1,b2,u,v,beltLight,overlay,axle.normalize());
            distance+=length;
        }
        if(wheel.beltSettings().showContacts()) drawContacts(wheel,partial,path,plane,stack,buffers);
    }
    private static void drawContacts(WheelBlockEntity root,float partial,TrackPath.Path path,Vec3 plane,PoseStack stack,MultiBufferSource buffers) {
        if(root.getLevel()==null)return;
        List<TrackPath.Wheel> wheels=new ArrayList<>();
        for(var pos:root.nodes()) {
            if(!(root.getLevel().getBlockEntity(pos) instanceof WheelBlockEntity member))return;
            wheels.add(member.pathWheel(root.getBlockPos(),partial,0));
        }
        List<TrackPath.ContactSpan> spans;
        try {spans=TrackPath.contactSpans(wheels,path.wheelDirections(),.45);}catch(IllegalArgumentException ex){return;}
        V3 axle=switch(root.axis()){case X->V3.X;case Y->V3.Y;case Z->V3.Z;};
        VertexConsumer lines=buffers.getBuffer(RenderType.lines());
        for(var span:spans) {
            Vec3 a=point(span.start(),root.axis()).add(plane),b=point(span.end(),root.axis()).add(plane);
            ContactBox box=root.beltSettings().contactBox(new V3(a.x,a.y,a.z),new V3(b.x,b.y,b.z),axle,root.beltWidth()/2);
            V3[] corners=new V3[8];
            for(int i=0;i<8;i++)corners[i]=box.center().add(box.x().mul((i&1)==0?-box.half().x():box.half().x()))
                .add(box.y().mul((i&2)==0?-box.half().y():box.half().y())).add(box.z().mul((i&4)==0?-box.half().z():box.half().z()));
            for(int i=0;i<8;i++)for(int bit=1;bit<=4;bit<<=1)if((i&bit)==0) {
                V3 from=corners[i],to=corners[i|bit],n=to.sub(from).unit();
                for(V3 p:new V3[]{from,to})lines.addVertex(stack.last(),(float)p.x(),(float)p.y(),(float)p.z()).setColor(70,190,255,255)
                    .setNormal(stack.last(),(float)n.x(),(float)n.y(),(float)n.z());
            }
        }
    }
    private static Vec3 vector(TrackPath.Point p,Direction.Axis axis) {
        return switch(axis) { case X->new Vec3(0,p.y(),p.x());case Y->new Vec3(p.x(),0,p.y());case Z->new Vec3(p.x(),p.y(),0); };
    }
    private static Vec3 point(TrackPath.Point p,Direction.Axis axis) { return vector(p,axis).add(.5,.5,.5); }
    private static void face(PoseStack stack,VertexConsumer out,Vec3 a,Vec3 b,Vec3 c,Vec3 d,float u,float v,int light,int overlay) {
        face(stack,out,a,b,c,d,u,v,light,overlay,b.subtract(a).cross(c.subtract(a)).normalize());
    }
    private static void face(PoseStack stack,VertexConsumer out,Vec3 a,Vec3 b,Vec3 c,Vec3 d,float u,float v,int light,int overlay,Vec3 n) {
        Vec3[] vertices={a,b,c,d};
        for(int i=0;i<4;i++) {
            Vec3 p=vertices[i];out.addVertex(stack.last(),(float)p.x,(float)p.y,(float)p.z).setColor(255,255,255,255)
                .setUv(i<2?u:v,i==0||i==3?0:1).setOverlay(overlay).setLight(light).setNormal(stack.last(),(float)n.x,(float)n.y,(float)n.z);
        }
    }
    @Override public boolean shouldRenderOffScreen(WheelBlockEntity be) { return true; }
    @Override public int getViewDistance() { return 192; }
    @Override public AABB getRenderBoundingBox(WheelBlockEntity be) {
        AABB box=new AABB(be.getBlockPos()).inflate(6).expandTowards(0,-4,0);
        if(be.controller()) for(var pos:be.nodes()) box=box.minmax(new AABB(pos).inflate(6).expandTowards(0,-4,0));
        return box;
    }
}
