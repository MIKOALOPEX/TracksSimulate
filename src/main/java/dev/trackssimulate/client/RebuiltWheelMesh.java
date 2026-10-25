package dev.trackssimulate.client;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import java.io.IOException;
import java.util.*;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.*;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.model.data.ModelData;
import dev.trackssimulate.content.blocks.sable_track.SableTrackRole;

/** Renders only the project owner's supplied meshes, without reference-mod art. */
public final class RebuiltWheelMesh {
    private record Mesh(String[] textures, float[][] quads, double radius) {}
    private static final Map<String, Mesh> MESHES = new HashMap<>();
    private record Key(SableTrackRole role,BlockState material) {}
    private record BatchKey(RenderType layer,int tint) {}
    private record Batch(BatchKey key,List<float[]> quads) {}
    private record Paint(RenderType layer,TextureAtlasSprite sprite,int tint,float[] uv) {}
    private static final Map<Key,List<Batch>> PREPARED=new LinkedHashMap<>(16,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key,List<Batch>> e){return size()>64;}
    };
    public static void clear() { MESHES.clear();PREPARED.clear(); }
    public static int preparedCount(){return PREPARED.size();}
    private static Mesh mesh(dev.trackssimulate.content.blocks.sable_track.SableTrackRole role) {
        String name=switch(role) {case DRIVE -> "drive_wheel";case GUIDE -> "return_wheel";default -> "road_wheel";};
        return MESHES.computeIfAbsent(name,RebuiltWheelMesh::load);
    }
    public static double radius(dev.trackssimulate.content.blocks.sable_track.SableTrackRole role) {return mesh(role).radius;}
    /** Immutable material UVs and render-layer batches are built once, not per wheel/frame. */
    public static int draw(SableTrackRole role,BlockState material,BlockAndTintGetter level,BlockPos pos,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        var batches=PREPARED.computeIfAbsent(new Key(role,material),RebuiltWheelMesh::prepare);int count=0;
        for(var batch:batches) {
            var out=buffers.getBuffer(batch.key.layer);int tint=batch.key.tint;
            int color=tint<0?0xffffff:Minecraft.getInstance().getBlockColors().getColor(material,level,pos,tint);
            for(var q:batch.quads) {
                count++;
                for(int v=0;v<4;v++)out.addVertex(pose.last(),q[3+3*v],q[4+3*v],q[5+3*v])
                    .setColor((color>>16)&255,(color>>8)&255,color&255,255).setUv(q[15+2*v],q[16+2*v])
                    .setOverlay(overlay).setLight(light).setNormal(pose.last(),q[0],q[1],q[2]);
            }
        }
        return count;
    }
    private static List<Batch> prepare(Key key) {
        Mesh mesh=mesh(key.role);var paints=material(key.material);
        Map<BatchKey,List<float[]>> groups=new LinkedHashMap<>();
        for(var source:mesh.quads) {
            String name=mesh.textures[(int)source[23]];
            var selected=name.equals("copycat_base")?paints.get(Direction.getNearest(source[0],source[1],source[2])):
                List.of(new Paint(Sheets.cutoutBlockSheet(),Minecraft.getInstance().getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
                    .apply(ResourceLocation.fromNamespaceAndPath("trackssimulate","block/"+name)),-1,new float[]{0,0,0,1,1,1,1,0}));
            for(var paint:selected) {
                float[] q=source.clone(),uv=paint.uv;
                for(int i=0;i<4;i++) {
                    float u=source[15+2*i],v=source[16+2*i];
                    float mu=(1-u)*(1-v)*uv[0]+(1-u)*v*uv[2]+u*v*uv[4]+u*(1-v)*uv[6];
                    float mv=(1-u)*(1-v)*uv[1]+(1-u)*v*uv[3]+u*v*uv[5]+u*(1-v)*uv[7];
                    q[15+2*i]=paint.sprite.getU(mu);q[16+2*i]=paint.sprite.getV(mv);
                }
                groups.computeIfAbsent(new BatchKey(paint.layer,paint.tint),k->new ArrayList<>()).add(q);
            }
        }
        return groups.entrySet().stream().map(e->new Batch(e.getKey(),List.copyOf(e.getValue()))).toList();
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
    private static Mesh load(String name) {
        var location = ResourceLocation.fromNamespaceAndPath("trackssimulate", "meshes/" + name + ".json");
        try (var reader = Minecraft.getInstance().getResourceManager().getResourceOrThrow(location).openAsReader()) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            var rows = root.getAsJsonArray("quads");
            float[][] quads = new float[rows.size()][24];
            for (int i=0;i<rows.size();i++) for(int j=0;j<24;j++) quads[i][j]=rows.get(i).getAsJsonArray().get(j).getAsFloat();
            var data = root.getAsJsonArray("textures");
            String[] textures=new String[data.size()];
            for(int i=0;i<data.size();i++)textures[i]=data.get(i).getAsString();
            return new Mesh(textures,quads,root.get("radius").getAsDouble());
        } catch(IOException ex) { throw new IllegalStateException("Cannot load user wheel mesh " + location,ex); }
    }
    private RebuiltWheelMesh() {}
}
