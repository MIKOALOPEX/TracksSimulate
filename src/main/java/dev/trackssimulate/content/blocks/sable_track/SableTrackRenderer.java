package dev.trackssimulate.content.blocks.sable_track;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.trackssimulate.client.RebuiltWheelMesh;
import dev.trackssimulate.client.AssemblyBeltCache;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Ordered assembly belt, independent of Trackwork artwork. */
public final class SableTrackRenderer implements BlockEntityRenderer<SableTrackBlockEntity> {
    private static final ResourceLocation BELT=ResourceLocation.parse("trackssimulate:textures/block/belt.png");
    private static final ResourceLocation STRUT=ResourceLocation.parse("create:textures/block/copycat_base.png");
    private final AssemblyBeltCache beltCache = new AssemblyBeltCache();
    public SableTrackRenderer(BlockEntityRendererProvider.Context context) {}
    @Override public void render(SableTrackBlockEntity be,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        var part=be.effectivePart(); if(!part.isWheel())return;
        var facing=be.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING);
        Vec3 center=center(be,partial).subtract(Vec3.atLowerCornerOf(be.getBlockPos()));
        long begin=System.nanoTime();int wheelQuads=0;
        double radius=be.wheelRadius();
        if(part.role()==SableTrackRole.SUSPENSION && !be.isVisualSuspensionHidden()) {
            var strut=buffers.getBuffer(net.minecraft.client.renderer.Sheets.cutoutBlockSheet());
            Vec3 mount=new Vec3(.5,.5,.5), side=Vec3.atLowerCornerOf(facing.getClockWise().getNormal()).scale(.065);
            quad(pose,strut,mount.subtract(side),mount.add(side),center.add(side),center.subtract(side),0,1,0xffffff,light,overlay);
        }
        if(!be.isWheelHidden()) {
        pose.pushPose();pose.translate(center.x,center.y,center.z);
        if(facing.getAxis()==Direction.Axis.Z)pose.mulPose(Axis.YP.rotationDegrees(-90));
        double scale=radius/RebuiltWheelMesh.radius(part.role());
        pose.scale((float)be.wheelWidth(),(float)scale,(float)scale);
        pose.mulPose(Axis.XP.rotation(be.getSharedTrackAngle(facing,partial)*(facing.getAxis()==Direction.Axis.X?1:-1)));
        wheelQuads=RebuiltWheelMesh.draw(part.role(),be.material(),be.getLevel(),be.getBlockPos(),pose,buffers,light,overlay);pose.popPose();
        }
        long wheelEnd=System.nanoTime();
        var group = beltCache.get(be, partial);
        if(group!=null)dev.trackssimulate.client.BeltRenderMesh.draw(group.mesh,pose,buffers.getBuffer(RenderType.entityCutoutNoCull(BELT)),group.phase,group.color,light,overlay);
        dev.trackssimulate.client.TrackRenderStats.record(be.getLevel(),begin,wheelEnd,System.nanoTime(),wheelQuads,group==null?0:group.mesh.size());
    }

    public static Vec3 center(SableTrackBlockEntity be, float partial) {
        return be.getWheelCenter(partial);
    }
    private static void quad(PoseStack pose,VertexConsumer out,Vec3 a,Vec3 b,Vec3 c,Vec3 d,float v0,float v1,int color,int light,int overlay) {
        var sprite=net.minecraft.client.Minecraft.getInstance().getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).apply(ResourceLocation.parse("create:block/copycat_base"));
        Vec3 normal=b.subtract(a).cross(c.subtract(a)).normalize();
        Vec3[] points={a,b,c,d};float[] u={0,1,1,0},v={v0,v0,v1,v1};
        for(int i=0;i<4;i++)out.addVertex(pose.last(),(float)points[i].x,(float)points[i].y,(float)points[i].z)
            .setColor((color>>16)&255,(color>>8)&255,color&255,255).setUv(sprite.getU(u[i]),sprite.getV(v[i])).setOverlay(overlay).setLight(light)
            .setNormal(pose.last(),(float)normal.x,(float)normal.y,(float)normal.z);
    }
    @Override public int getViewDistance(){return 96;}
}
