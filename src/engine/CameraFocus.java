package engine;

import math.Vec3;
import java.util.*;

/** Query-owned geometry cache and BVH traversal; never uses transport scratch or RNG. */
public final class CameraFocus {
    public record Scene(List<SceneInstance> instances,List<engine.objects.RenderPrimitive> objects,long revision) {
        public static Scene capture(ViewportState state) {return new Scene(List.copyOf(state.instances()),List.copyOf(state.objects()),state.focusSceneRevision());}
        @Override public boolean equals(Object other) {
            if(!(other instanceof Scene s)||revision!=s.revision||instances.size()!=s.instances.size()||objects.size()!=s.objects.size())return false;
            for(int i=0;i<instances.size();i++) {
                var a=instances.get(i);var b=s.instances.get(i);
                if(!a.name().equals(b.name())||a.geometry()!=b.geometry()||a.transform()!=b.transform())return false;
            }
            for(int i=0;i<objects.size();i++)if(objects.get(i)!=s.objects.get(i))return false;
            return true;
        }
        @Override public int hashCode() {return instances.size()*31+objects.size();}
    }
    public record Subject(String name,Object geometry) {
        @Override public boolean equals(Object other) {return other instanceof Subject s && name.equals(s.name)&&geometry==s.geometry;}
        @Override public int hashCode() {return name.hashCode()*31+System.identityHashCode(geometry);}
    }
    public record Measurement(FocusTargetSource source,Subject subject,Vec3 hit,float distance,long captured,long completed,
                       long epoch,Scene scene,Camera camera,String error) {}
    private record Entry(SceneInstance instance,PreparedObject prepared,Subject subject) {}
    private List<Entry> cache=List.of();
    private Scene cachedScene;
    long preparations,queries,primitiveTests,queryNanos;
    public Measurement resolve(FocusTargetSource source,Camera camera,Scene scene,long epoch,long captured,
                        java.util.function.LongSupplier clock) {
        long start=System.nanoTime();queries++;
        try {
            if(!(source instanceof FocusTargetSource.Screen screen))throw new IllegalArgumentException("No resolver for focus source");
            prepare(scene);
            var ray=new Camera.RaySample();var compiled=camera.compile();compiled.reference(screen.u(),screen.v(),ray);
            float nearest=Float.POSITIVE_INFINITY;Subject subject=null;
            for(var entry:cache) {
                float before=nearest;var object=entry.prepared();
                if(!object.overlaps(ray.ox,ray.oy,ray.oz,ray.dx,ray.dy,ray.dz,nearest))continue;
                if(object.bvh==null) {
                    for(var primitive:object.primitives)nearest=distance(primitive,ray,nearest);
                } else {
                    var bvh=object.bvh;int i=0;
                    while(i<bvh.nodes.length) {
                        var node=bvh.nodes[i];
                        if(!node.bounds.overlaps(ray.ox,ray.oy,ray.oz,ray.dx,ray.dy,ray.dz,nearest)) {i=node.escape;continue;}
                        if(node.leaf())for(int p=node.from;p<node.to;p++)nearest=distance(object.primitives[bvh.order[p]],ray,nearest);
                        i++;
                    }
                }
                if(nearest<before)subject=entry.subject();
            }
            if(subject==null)throw new IllegalArgumentException("Focus query missed; distance unchanged");
            var hit=new Vec3(ray.ox+ray.dx*nearest,ray.oy+ray.dy*nearest,ray.oz+ray.dz*nearest);
            float axial=nearest*(ray.dx*compiled.forward.x()+ray.dy*compiled.forward.y()+ray.dz*compiled.forward.z());
            camera.withFocus(axial);
            return new Measurement(source,subject,hit,axial,captured,clock.getAsLong(),epoch,scene,camera,null);
        } catch(IllegalArgumentException e) {
            return new Measurement(source,null,null,0,captured,clock.getAsLong(),epoch,scene,camera,e.getMessage());
        } finally {queryNanos+=System.nanoTime()-start;}
    }
    private float distance(PreparedPrimitive p,Camera.RaySample ray,float nearest) {
        primitiveTests++;float d=p.distance(ray.ox,ray.oy,ray.oz,ray.dx,ray.dy,ray.dz);
        return d>0 && d<nearest?d:nearest;
    }
    private void prepare(Scene scene) {
        if(scene.equals(cachedScene))return;
        var instances=new ArrayList<>(scene.instances());
        for(int i=0;i<scene.objects().size();i++)instances.add(new SceneInstance("legacy-"+i,PreparedGeometry.canonical(scene.objects().get(i)),
                Transform.IDENTITY,new Material("focus",new Vec3(1,1,1))));
        var next=new ArrayList<Entry>(instances.size());
        for(var instance:instances) {
            var prior=cache.stream().filter(e->e.instance().name().equals(instance.name())
                    && e.instance().geometry()==instance.geometry()
                    && e.instance().transform()==instance.transform()).findFirst();
            if(prior.isPresent())next.add(prior.get());
            else {next.add(new Entry(instance,new PreparedObject(instance),new Subject(instance.name(),instance.geometry())));preparations++;}
        }
        cache=List.copyOf(next);
        cachedScene=scene;
    }
    public static float distance(ViewportState snapshot) {
        var result=new CameraFocus().resolve(FocusTargetSource.CENTER,snapshot.camera(),Scene.capture(snapshot),0,0,System::nanoTime);
        if(result.error()!=null)throw new IllegalArgumentException(result.error());
        return result.distance();
    }
}
