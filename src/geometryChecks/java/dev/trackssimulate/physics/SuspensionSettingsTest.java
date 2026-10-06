package dev.trackssimulate.physics;

public final class SuspensionSettingsTest {
    private static int checks;
    private static void check(boolean value,String message) { checks++;if(!value) throw new AssertionError(message); }
    private static void rejects(double min,double max,double rest,double k,double c,double mass) {
        try { new SuspensionSettings(min,max,rest,k,c,mass);throw new AssertionError("Invalid suspension accepted"); }
        catch(IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) {
        var defaults=SuspensionSettings.DEFAULT;
        check(defaults.jointMinimum()==-1&&defaults.jointMaximum()==0,"Downward travel reverses and swaps native limits");
        check(defaults.jointRest()==-.5,"Rest motor target points down");
        check(defaults.clamp(-10)==0&&defaults.clamp(10)==1&&defaults.clamp(.3)==.3,"Reload position remains in travel range");
        var asymmetric=new SuspensionSettings(.2,1.7,.8,300,40,200);
        check(asymmetric.jointMinimum()==-1.7&&asymmetric.jointMaximum()==-.2,"Asymmetric compression/extension stops");
        rejects(-.1,1,.5,100,20,100);rejects(0,5,.5,100,20,100);rejects(1,0,.5,100,20,100);
        rejects(0,.005,0,100,20,100);rejects(.2,1,0,100,20,100);rejects(0,1,2,100,20,100);
        rejects(0,1,.5,-1,20,100);rejects(0,1,.5,10001,20,100);rejects(0,1,.5,100,-1,100);
        rejects(0,1,.5,100,1001,100);rejects(0,1,.5,100,20,0);rejects(0,1,.5,100,20,10001);
        for(int field=0;field<6;field++) for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            double[] v={0,1,.5,100,20,100};v[field]=invalid;rejects(v[0],v[1],v[2],v[3],v[4],v[5]);
        }
        check(new SuspensionSettings(0,4,4,0,0,1).rest()==4,"Boundary configuration accepted");
        System.out.println("SuspensionSettings: "+checks+" checks passed");
    }
}
