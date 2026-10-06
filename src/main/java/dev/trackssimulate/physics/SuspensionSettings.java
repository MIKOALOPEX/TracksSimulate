package dev.trackssimulate.physics;

/** Distances are downward from the shaft centre, in blocks. Motor gains are engine coefficients, not SI spring constants. */
public record SuspensionSettings(double minimum,double maximum,double rest,double stiffness,double damping,double mass) {
    public static final SuspensionSettings DEFAULT=new SuspensionSettings(0,1,.5,100,20,100);
    public SuspensionSettings {
        if(!Double.isFinite(minimum)||!Double.isFinite(maximum)||!Double.isFinite(rest)
            ||!Double.isFinite(stiffness)||!Double.isFinite(damping)||!Double.isFinite(mass)
            ||minimum<0||maximum>4||maximum-minimum<.01||rest<minimum||rest>maximum
            ||stiffness<0||stiffness>10000||damping<0||damping>1000||mass<1||mass>10000)
            throw new IllegalArgumentException("行程需满足 0 ≤ 压缩端 ≤ 自然位置 ≤ 伸长端 ≤ 4，行程至少 0.01；刚度 0–10000，阻尼 0–1000，质量 1–10000");
    }
    public double clamp(double extension) { return Math.max(minimum,Math.min(maximum,extension)); }
    public double jointMinimum() { return -maximum; }
    public double jointMaximum() { return -minimum; }
    public double jointRest() { return -rest; }
}
