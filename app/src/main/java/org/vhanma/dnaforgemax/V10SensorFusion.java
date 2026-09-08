package org.vhanma.dnaforgemax;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/** Lightweight synchronized sensor state for Hunter Ω. */
final class V10SensorFusion implements SensorEventListener {
    static final class Snapshot {
        final long timeNs;
        final float accel, gyro, magnetic, light;
        Snapshot(long t,float a,float g,float m,float l){timeNs=t;accel=a;gyro=g;magnetic=m;light=l;}
    }

    private final SensorManager sm;
    private Sensor accelSensor,gyroSensor,magSensor,lightSensor;
    private volatile float accel=0f,gyro=0f,magnetic=0f,light=0f;
    private float gravity=9.81f;
    private boolean running;

    V10SensorFusion(Context c){
        sm=(SensorManager)c.getSystemService(Context.SENSOR_SERVICE);
        if(sm!=null){
            accelSensor=sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyroSensor=sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            magSensor=sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            lightSensor=sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        }
    }

    void start(){
        if(sm==null||running)return;running=true;
        register(accelSensor,SensorManager.SENSOR_DELAY_GAME);
        register(gyroSensor,SensorManager.SENSOR_DELAY_GAME);
        register(magSensor,SensorManager.SENSOR_DELAY_NORMAL);
        register(lightSensor,SensorManager.SENSOR_DELAY_NORMAL);
    }
    void stop(){if(sm!=null&&running)sm.unregisterListener(this);running=false;}
    Snapshot snapshot(){return new Snapshot(System.nanoTime(),accel,gyro,magnetic,light);}
    private void register(Sensor s,int delay){if(s!=null)sm.registerListener(this,s,delay);}

    @Override public void onSensorChanged(SensorEvent e){
        if(e==null||e.sensor==null)return;
        int type=e.sensor.getType();
        if(type==Sensor.TYPE_ACCELEROMETER){
            float m=mag(e.values); gravity=gravity*.985f+m*.015f; accel=smooth(accel,Math.abs(m-gravity),.20f);
        } else if(type==Sensor.TYPE_GYROSCOPE){
            gyro=smooth(gyro,mag(e.values),.28f);
        } else if(type==Sensor.TYPE_MAGNETIC_FIELD){
            magnetic=smooth(magnetic,mag(e.values),.12f);
        } else if(type==Sensor.TYPE_LIGHT){
            light=smooth(light,e.values.length>0?Math.max(0,e.values[0]):0,.15f);
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
    private static float mag(float[]v){if(v==null)return 0;float s=0;for(int i=0;i<Math.min(3,v.length);i++)s+=v[i]*v[i];return (float)Math.sqrt(s);}
    private static float smooth(float old,float v,float a){return old==0?v:old+(v-old)*a;}
}
