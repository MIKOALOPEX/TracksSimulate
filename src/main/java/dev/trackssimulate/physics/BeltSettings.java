package dev.trackssimulate.physics;

import dev.trackssimulate.physics.contact.*;

/** Entire-loop settings. The same schema drives persistence, packets and the editor. */
public record BeltSettings(double friction,double driveForce,double maxSpeed,double rollingDrag,double brake,double thickness,double widthScale,double lateralFriction,
    double contactOffset,double sideOffset,double predictionDistance,double maxAcceleration,double inertiaRatio,double sideGrip,double contactSoftness,double recoverySpeed,double debugContacts,double lateralSlipSpeed,double driveMultiplier) {
    public static final String[] KEYS={"Friction","DriveForce","MaxSpeed","RollingDrag","Brake","Thickness","WidthScale","LateralFriction","ContactOffset","SideOffset","PredictionDistance","MaxAcceleration","InertiaRatio","SideGrip","ContactSoftness","RecoverySpeed","DebugContacts","LateralSlipSpeed","DriveMultiplier"};
    private static final double[] MIN={0,0,.1,0,0,.02,.25,0,0,0,.005,.1,.005,0,.001,0,0,0,0};
    private static final double[] MAX={10,200000,16,1000,1,.25,3,10,.5,.5,.5,200,1,1,1,1,1,2,16};
    public static final BeltSettings DEFAULT=new BeltSettings(1.2,30000,8,10,0,.08,1,1.8);
    public BeltSettings(double friction,double driveForce,double maxSpeed,double rollingDrag,double brake,double thickness) {
        this(friction,driveForce,maxSpeed,rollingDrag,brake,thickness,1,Math.max(1.8,friction));
    }
    public BeltSettings(double friction,double driveForce,double maxSpeed,double rollingDrag,double brake,double thickness,double widthScale) {
        this(friction,driveForce,maxSpeed,rollingDrag,brake,thickness,widthScale,Math.max(1.8,friction));
    }
    public BeltSettings(double friction,double driveForce,double maxSpeed,double rollingDrag,double brake,double thickness,double widthScale,double lateralFriction) {
        this(friction,driveForce,maxSpeed,rollingDrag,brake,thickness,widthScale,lateralFriction,.025,0,.15,30,.05,.5,.1,.12,0,.15,1);
    }
    public BeltSettings {
        double[] v={friction,driveForce,maxSpeed,rollingDrag,brake,thickness,widthScale,lateralFriction,contactOffset,sideOffset,predictionDistance,maxAcceleration,inertiaRatio,sideGrip,contactSoftness,recoverySpeed,debugContacts,lateralSlipSpeed,driveMultiplier};
        for(int i=0;i<v.length;i++)if(!Double.isFinite(v[i])||v[i]<MIN[i]||v[i]>MAX[i])throw new IllegalArgumentException(KEYS[i]+" 范围 "+MIN[i]+"–"+MAX[i]);
        if(debugContacts!=0&&debugContacts!=1)throw new IllegalArgumentException("判定框显示只能为 0 或 1");
    }
    public double[] values() {return new double[]{friction,driveForce,maxSpeed,rollingDrag,brake,thickness,widthScale,lateralFriction,contactOffset,sideOffset,predictionDistance,maxAcceleration,inertiaRatio,sideGrip,contactSoftness,recoverySpeed,debugContacts,lateralSlipSpeed,driveMultiplier};}
    public static BeltSettings from(double[] v) {
        if(v.length!=KEYS.length)throw new IllegalArgumentException("履带参数数量错误");
        return new BeltSettings(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11],v[12],v[13],v[14],v[15],v[16],v[17],v[18]);
    }
    public double drivenSpeed(double inputSpeed) {return inputSpeed*driveMultiplier;}
    public double limitedSpeed(double target) {return Math.max(-maxSpeed,Math.min(maxSpeed,target));}
    public boolean showContacts() {return debugContacts==1;}
    public double equivalentMass(double vehicleMass,int loopCount) {return vehicleMass/Math.max(1,loopCount)*inertiaRatio;}
    public double effectiveDriveForce(double vehicleMass,int loopCount) {return Math.min(driveForce,vehicleMass/Math.max(1,loopCount)*maxAcceleration);}
    public ContactBox contactBox(V3 start,V3 end,V3 axle,double halfWidth) {
        V3 tangent=end.sub(start).unit();
        return new ContactBox(start.add(end).mul(.5),tangent,axle,tangent.cross(axle).unit(),new V3(end.sub(start).length()/2+.003,halfWidth+sideOffset,thickness/2+contactOffset));
    }
}
