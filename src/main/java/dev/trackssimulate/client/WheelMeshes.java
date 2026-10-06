package dev.trackssimulate.client;

import com.google.gson.*;
import com.mojang.blaze3d.vertex.*;
import dev.trackssimulate.wheel.WheelKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.model.data.ModelData;
import java.io.*;
import java.util.*;

final class WheelMeshes {
    private record Mesh(String[] textures,float[][] quads) {}
    private record Paint(RenderType layer,TextureAtlasSprite sprite,int tint,float[] uv) {}
    private static final Map<WheelKind,Mesh> CACHE=new EnumMap<>(WheelKind.class);
    private static final Map<BlockState,Map<Direction,List<Paint>>> MATERIALS=new HashMap<>();
    static final ResourceLocation BASE=ResourceLocation.parse("create:textures/block/copycat_base.png");
    static void clear() { CACHE.clear();MATERIALS.clear(); }
    static void draw(WheelKind kind,BlockState material,BlockAndTintGetter level,BlockPos pos,PoseStack stack,MultiBufferSource buffers,int light,int overlay) {
        Mesh mesh=CACHE.computeIfAbsent(kind,WheelMeshes::load);
        Map<Direction,List<Paint>> paints=MATERIALS.computeIfAbsent(material,WheelMeshes::material);
        for(int slot=0;slot<mesh.textures.length;slot++) {
            boolean copycat=mesh.textures[slot].equals("copycat_base");
            VertexConsumer fixed=copycat?null:buffers.getBuffer(RenderType.entityCutoutNoCull(ResourceLocation.fromNamespaceAndPath("trackssimulate","textures/block/"+mesh.textures[slot]+".png")));
            for(float[] q:mesh.quads)if((int)q[23]==slot) {
                if(!copycat)drawQuad(q,stack,fixed,null,0xFFFFFF,light,overlay);
                else for(Paint paint:paints.get(Direction.getNearest(q[0],q[1],q[2]))) {
                    int color=paint.tint<0?0xFFFFFF:Minecraft.getInstance().getBlockColors().getColor(material,level,pos,paint.tint);
                    drawQuad(q,stack,buffers.getBuffer(paint.layer),paint,color,light,overlay);
                }
            }
        }
    }
    private static void drawQuad(float[] q,PoseStack stack,VertexConsumer out,Paint paint,int color,int light,int overlay) {
        float shade=.72f+.28f*Math.max(0,q[1]);
        for(int i=0;i<4;i++) {
            float u=q[15+i*2],v=q[16+i*2];
            if(paint!=null) {
                float[] uv=paint.uv;
                float mappedU=(1-u)*(1-v)*uv[0]+(1-u)*v*uv[2]+u*v*uv[4]+u*(1-v)*uv[6];
                float mappedV=(1-u)*(1-v)*uv[1]+(1-u)*v*uv[3]+u*v*uv[5]+u*(1-v)*uv[7];
                u=paint.sprite.getU(mappedU);v=paint.sprite.getV(mappedV);
            }
            out.addVertex(stack.last(),q[3+i*3],q[4+i*3],q[5+i*3])
                .setColor(((color>>16)&255)/255f*shade,((color>>8)&255)/255f*shade,(color&255)/255f*shade,1)
                .setUv(u,v).setOverlay(overlay).setLight(light).setNormal(stack.last(),q[0],q[1],q[2]);
        }
    }
    private static Map<Direction,List<Paint>> material(BlockState state) {
        var model=Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        Map<Direction,List<Paint>> result=new EnumMap<>(Direction.class);
        for(Direction face:Direction.values()) {
            List<Paint> paints=new ArrayList<>();
            for(RenderType layer:model.getRenderTypes(state,RandomSource.create(42),ModelData.EMPTY)) {
                List<BakedQuad> quads=new ArrayList<>(model.getQuads(state,face,RandomSource.create(42),ModelData.EMPTY,layer));
                for(var quad:model.getQuads(state,null,RandomSource.create(42),ModelData.EMPTY,layer))if(quad.getDirection()==face)quads.add(quad);
                Set<String> seen=new HashSet<>();
                for(var quad:quads) {
                    var sprite=quad.getSprite();if(!seen.add(sprite.contents().name()+":"+quad.getTintIndex()))continue;
                    int[] vertices=quad.getVertices();int stride=vertices.length/4;float[] uv=new float[8];
                    for(int i=0;i<4;i++) {
                        uv[i*2]=(Float.intBitsToFloat(vertices[i*stride+4])-sprite.getU0())/(sprite.getU1()-sprite.getU0());
                        uv[i*2+1]=(Float.intBitsToFloat(vertices[i*stride+5])-sprite.getV0())/(sprite.getV1()-sprite.getV0());
                    }
                    paints.add(new Paint(RenderTypeHelper.getEntityRenderType(layer,false),sprite,quad.getTintIndex(),uv));
                }
            }
            if(paints.isEmpty())paints.add(new Paint(Sheets.cutoutBlockSheet(),model.getParticleIcon(ModelData.EMPTY),-1,new float[]{0,0,0,1,1,1,1,0}));
            result.put(face,List.copyOf(paints));
        }
        return result;
    }
    private static Mesh load(WheelKind kind) {
        ResourceLocation id=ResourceLocation.fromNamespaceAndPath("trackssimulate","meshes/"+kind.id+".json");
        try(Reader r=Minecraft.getInstance().getResourceManager().getResourceOrThrow(id).openAsReader()) {
            var object=JsonParser.parseReader(r).getAsJsonObject();
            if(object.get("format").getAsInt()!=2)throw new IllegalArgumentException("Unsupported mesh format");
            JsonArray data=object.getAsJsonArray("quads");float[][] quads=new float[data.size()][24];
            for(int i=0;i<data.size();i++)for(int j=0;j<24;j++)quads[i][j]=data.get(i).getAsJsonArray().get(j).getAsFloat();
            String[] textures=new String[object.getAsJsonArray("textures").size()];
            for(int i=0;i<textures.length;i++)textures[i]=object.getAsJsonArray("textures").get(i).getAsString();
            return new Mesh(textures,quads);
        } catch(IOException|RuntimeException e) {throw new IllegalStateException("Cannot load wheel mesh "+id,e);}
    }
    private WheelMeshes() {}
}
