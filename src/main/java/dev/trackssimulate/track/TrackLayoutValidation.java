package dev.trackssimulate.track;

import java.util.List;

/** Pure preflight used before an entire parameter edit is committed. */
public final class TrackLayoutValidation {
    public record Wheel(double axial,TrackPath.Wheel planar) {}
    public static TrackPath.Path validate(List<Wheel> wheels,List<Integer> directions) {
        if(wheels.size()<2) throw new IllegalArgumentException("履带至少需要两个轮子");
        double plane=wheels.getFirst().axial;
        for(Wheel wheel:wheels) if(!Double.isFinite(wheel.axial)||Math.abs(wheel.axial-plane)>1e-6)
            throw new IllegalArgumentException("轴向偏移会使履带不共面；请保持同一平面，或拆带后调整");
        return TrackPath.withDirections(wheels.stream().map(Wheel::planar).toList(),directions);
    }
    private TrackLayoutValidation() {}
}
