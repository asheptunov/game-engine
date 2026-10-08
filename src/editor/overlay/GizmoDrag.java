package editor.overlay;

import engine.Camera;
import engine.CameraProjector;
import engine.RayQuery;
import math.Vec3;

import java.util.Objects;
import java.util.Optional;

/** Stable drag solvers driven by the same sharp reference rays used for scene picking. */
public final class GizmoDrag {
    public sealed interface Delta permits Translation,Rotation {}
    public record Translation(Vec3 worldDelta,float axisDistance) implements Delta {}
    public record Rotation(float degrees) implements Delta {}
    private enum Solver { AXIS_RAYS,SCREEN_AXIS,RING_PLANE }

    private final CameraProjector projector;
    private final Vec3 pivot,axis,startRadial;
    private final Solver solver;
    private final int width,height;
    private final double startValue,screenDx,screenDy,unitsPerPixel;
    private double previousRaw,accumulated;

    private GizmoDrag(CameraProjector projector,Vec3 pivot,Vec3 axis,Solver solver,int width,int height,
                      double startValue,double screenDx,double screenDy,double unitsPerPixel,Vec3 startRadial) {
        this.projector=projector;this.pivot=pivot;this.axis=axis;this.solver=solver;this.width=width;this.height=height;
        this.startValue=startValue;this.screenDx=screenDx;this.screenDy=screenDy;this.unitsPerPixel=unitsPerPixel;
        this.startRadial=startRadial;
    }

    public static Optional<GizmoDrag> beginTranslation(Camera camera,Vec3 pivot,Vec3 worldAxis,
                                                        double u,double v,int pixelWidth,int pixelHeight) {
        checkScreen(u,v,pixelWidth,pixelHeight);var projector=CameraProjector.of(camera);var axis=unit(worldAxis);
        var closest=axisValue(projector.referenceRay(u,v),pivot,axis);
        if(closest.isPresent())return Optional.of(new GizmoDrag(projector,pivot,axis,Solver.AXIS_RAYS,pixelWidth,pixelHeight,
                closest.get(),0,0,0,null));
        var base=projector.project(pivot);var heightAt=projector.screenHeightAt(pivot);
        if(base.isEmpty()||heightAt.isEmpty())return Optional.empty();
        double reference=heightAt.getAsDouble()*.25;
        var tip=projector.project(pivot.add(axis.scale((float)reference)));if(tip.isEmpty())return Optional.empty();
        double dx=(tip.get().u()-base.get().u())*pixelWidth,dy=(tip.get().v()-base.get().v())*pixelHeight;
        double pixels=Math.hypot(dx,dy);if(!(pixels>=2))return Optional.empty();
        return Optional.of(new GizmoDrag(projector,pivot,axis,Solver.SCREEN_AXIS,pixelWidth,pixelHeight,
                u*pixelWidth*dx/pixels+v*pixelHeight*dy/pixels,dx/pixels,dy/pixels,reference/pixels,null));
    }

    public static Optional<GizmoDrag> beginRotation(Camera camera,Vec3 pivot,Vec3 worldAxis,double u,double v) {
        checkScreen(u,v,1,1);var projector=CameraProjector.of(camera);var axis=unit(worldAxis);
        var radial=ringRadial(projector.referenceRay(u,v),pivot,axis);if(radial.isEmpty())return Optional.empty();
        return Optional.of(new GizmoDrag(projector,pivot,axis,Solver.RING_PLANE,1,1,0,0,0,0,radial.get()));
    }

    public Optional<Delta> update(double u,double v) {
        checkScreen(u,v,width,height);
        if(solver==Solver.AXIS_RAYS) {
            var value=axisValue(projector.referenceRay(u,v),pivot,axis);if(value.isEmpty())return Optional.empty();
            float distance=(float)(value.get()-startValue);return Optional.of(new Translation(axis.scale(distance),distance));
        }
        if(solver==Solver.SCREEN_AXIS) {
            double current=u*width*screenDx+v*height*screenDy;
            float distance=(float)((current-startValue)*unitsPerPixel);return Optional.of(new Translation(axis.scale(distance),distance));
        }
        var radial=ringRadial(projector.referenceRay(u,v),pivot,axis);if(radial.isEmpty())return Optional.empty();
        double raw=Math.atan2(axis.dot(startRadial.cross(radial.get())),startRadial.dot(radial.get()));
        double step=raw-previousRaw;
        if(step>Math.PI)step-=2*Math.PI;else if(step< -Math.PI)step+=2*Math.PI;
        accumulated+=step;previousRaw=raw;
        return Optional.of(new Rotation((float)Math.toDegrees(accumulated)));
    }

    private static Optional<Double> axisValue(RayQuery ray,Vec3 pivot,Vec3 axis) {
        var origin=ray.origin();var direction=ray.direction();double b=axis.dot(direction),denominator=1-b*b;
        if(!(denominator>1e-4))return Optional.empty();
        var w=pivot.sub(origin);double value=(b*direction.dot(w)-axis.dot(w))/denominator;
        double along=b*value+direction.dot(w);return along>0&&Double.isFinite(value)?Optional.of(value):Optional.empty();
    }
    private static Optional<Vec3> ringRadial(RayQuery ray,Vec3 pivot,Vec3 axis) {
        double denominator=axis.dot(ray.direction());if(Math.abs(denominator)<1e-4)return Optional.empty();
        double distance=axis.dot(pivot.sub(ray.origin()))/denominator;if(!(distance>0)||!Double.isFinite(distance))return Optional.empty();
        var radial=ray.origin().add(ray.direction().scale((float)distance)).sub(pivot);double length=length(radial);
        return length>1e-6&&Double.isFinite(length)?Optional.of(radial.scale((float)(1/length))):Optional.empty();
    }
    private static Vec3 unit(Vec3 value) {
        Objects.requireNonNull(value,"axis");double length=length(value);
        if(!(length>0)||!Double.isFinite(length))throw new IllegalArgumentException("Gizmo axis must be finite and nonzero");
        return new Vec3((float)(value.x()/length),(float)(value.y()/length),(float)(value.z()/length));
    }
    private static double length(Vec3 v) {
        double scale=Math.max(Math.abs((double)v.x()),Math.max(Math.abs((double)v.y()),Math.abs((double)v.z())));
        if(scale==0)return 0;double x=v.x()/scale,y=v.y()/scale,z=v.z()/scale;return scale*Math.sqrt(x*x+y*y+z*z);
    }
    private static void checkScreen(double u,double v,int width,int height) {
        if(!Double.isFinite(u)||!Double.isFinite(v)||u<0||u>1||v<0||v>1||width<=0||height<=0)
            throw new IllegalArgumentException("Screen coordinates must be 0..1 with positive dimensions");
    }
}
