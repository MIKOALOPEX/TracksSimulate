package dev.trackssimulate.client;

/** Aggregate CPU submission cost. Does not claim to measure GPU time or server TPS. */
public final class TrackRenderStats {
    private static Object level;
    private static long start=System.nanoTime(),calls,wheelQuads,beltQuads,drawNanos,wheelNanos,beltNanos;
    public static void record(Object world,long begin,long wheelEnd,long end,int wheels,int belts) {
        if(level!=world){level=world;start=begin;calls=wheelQuads=beltQuads=drawNanos=wheelNanos=beltNanos=0;}
        calls++;wheelQuads+=wheels;beltQuads+=belts;drawNanos+=end-begin;wheelNanos+=wheelEnd-begin;beltNanos+=end-wheelEnd;
        if(end-start<5_000_000_000L)return;
        dev.trackssimulate.Tracks.LOGGER.info("[TS-RENDER] windowSeconds={} fps={} calls={} wheelQuads={} beltQuads={} cpuSubmitMs={} wheelMs={} beltMs={} materialCache={} (CPU submission only; not GPU/TPS)",
            (end-start)/1e9,net.minecraft.client.Minecraft.getInstance().getFps(),calls,wheelQuads,beltQuads,drawNanos/1e6,wheelNanos/1e6,beltNanos/1e6,RebuiltWheelMesh.preparedCount());
        start=end;calls=wheelQuads=beltQuads=drawNanos=wheelNanos=beltNanos=0;
    }
    private TrackRenderStats() {}
}
