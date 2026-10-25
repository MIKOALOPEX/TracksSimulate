import dev.trackssimulate.config.*;
import java.util.*;

/** Production policy/scale checks only. Does not simulate a Minecraft world or a GUI. */
public final class TrackSettingsChecks {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static boolean select(OffsetScope scope,UUID a,UUID b,boolean self,boolean role,boolean body,double distance) {
        return TrackSyncPolicy.includes(scope,a,b,self,role,body,distance);
    }
    public static void main(String[] args) {
        var belt=UUID.randomUUID();var other=UUID.randomUUID();
        for(var scope:OffsetScope.values())for(UUID source:Arrays.asList(null,belt))for(UUID candidate:Arrays.asList(null,belt,other))
            for(boolean self:new boolean[]{false,true})for(boolean role:new boolean[]{false,true})for(double distance:new double[]{0,63.999,64,64.001,10000}) {
                check(!select(scope,source,candidate,self,role,false,distance),"never cross body boundary");
                boolean expected=switch(scope) {
                    case WHEEL -> self;
                    case ROLE -> role&&(source==null?candidate==null&&distance<=64:source.equals(candidate));
                    case ASSEMBLY -> source!=null&&source.equals(candidate);
                };
                check(select(scope,source,candidate,self,role,true,distance)==expected,"scope/belt/role/range matrix");
            }
        check(OffsetScope.WHEEL.next(false)==OffsetScope.ROLE,"unlinked role available");
        check(OffsetScope.ROLE.next(false)==OffsetScope.WHEEL,"unlinked has no group choice");
        check(OffsetScope.ROLE.next(true)==OffsetScope.ASSEMBLY,"linked group available");
        check(OffsetScope.ASSEMBLY.next(true)==OffsetScope.WHEEL,"group cycles to current");
        check(TrackSyncPolicy.RANGE==8&&TrackSyncPolicy.MAX_TARGETS==64,"bounded search contract");
        for(var field:TrackSetting.values()) {
            boolean driveOnly=field==TrackSetting.THROUGH_AXLE||field==TrackSetting.SPEED;
            check(field.supports(true,false)==!driveOnly,"road skips drive-only settings");
            boolean universal=Set.of(TrackSetting.LATERAL,TrackSetting.LONGITUDINAL,TrackSetting.HEIGHT,TrackSetting.SCALE,TrackSetting.WIDTH,TrackSetting.WHEEL_HIDDEN,
                TrackSetting.CONNECTED_CONTACTS,TrackSetting.FRICTION_FORWARD,TrackSetting.FRICTION_SIDE,TrackSetting.GRIP,TrackSetting.DRIVE).contains(field);
            check(field.supports(false,false)==universal,"fixed wheels expose surface traction but skip suspension fields");
            check(field.supports(false,true)==(universal||driveOnly),"drive role support");
            check(!field.accepts(Double.NaN)&&!field.accepts(Double.POSITIVE_INFINITY),"nonfinite input rejected");
            check(field.accepts(field.defaultValue),"valid defaults");
        }
        // Different legacy/default wheel sizes retain their ratios when synchronizing the SAME multiplier.
        double[] defaults={.4,.55,.75,.95,.6,.8,1};
        for(double base:defaults) {
            check(Math.abs(2*TrackSetting.scaledRadius(base,2)-4*base)<1e-12,"2x doubles diameter");
            check(TrackSetting.scaledRadius(base,1)==base,"1x restores per-role registered size");
            double oldRadius=2.0,scale=oldRadius/base;
            check(Math.abs(TrackSetting.scaledRadius(base,scale)-oldRadius)<1e-12,"old absolute radius roundtrip");
        }
        check(TrackSetting.scaledRadius(.5,2)*2==2,"one block default diameter becomes two");
        for(double invalid:new double[]{0,-1,.249,5.001,Double.NaN,Double.POSITIVE_INFINITY}) {
            boolean rejected=false;try{TrackSetting.scaledRadius(.5,invalid);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"invalid scale rejected");
        }
        check(TrackSetting.SCALE.parse("2.0")==2,"manual multiplier entry");
        check(TrackSetting.HIDDEN.parse("0.5")==null,"visibility is boolean");
        // Saved legacy booleans must retain the same ports when new properties
        // load with their default; new NONE/RIGHT must not inherit an old FULL.
        check(DriveAxleMode.fromSaved(0,false)==DriveAxleMode.LEFT,"old half axle keeps rear port");
        check(DriveAxleMode.fromSaved(0,true)==DriveAxleMode.FULL,"old full axle retains both ports");
        for(var mode:DriveAxleMode.values()) {
            check(DriveAxleMode.fromSaved(mode.id,mode==DriveAxleMode.FULL)==mode,"new axle save round trip");
            check(TrackSetting.THROUGH_AXLE.accepts(mode.id),"editor accepts all four axle states");
            check(mode.back==(mode==DriveAxleMode.LEFT||mode==DriveAxleMode.FULL),"rear shaft port contract");
            check(mode.front==(mode==DriveAxleMode.RIGHT||mode==DriveAxleMode.FULL),"front shaft port contract");
        }
        check(DriveAxleMode.fromSaved(3,true)==DriveAxleMode.NONE,"NONE overrides stale full boolean");
        check(DriveAxleMode.fromSaved(2,true)==DriveAxleMode.RIGHT,"RIGHT overrides stale full boolean");
        check(TrackSetting.THROUGH_AXLE.parse("0.5")==null&&TrackSetting.THROUGH_AXLE.parse("4")==null,"invalid axle IDs rejected");
        System.out.println("Track settings checks passed: "+checks+". GUI, server transactions and game behavior still require user testing.");
    }
}
