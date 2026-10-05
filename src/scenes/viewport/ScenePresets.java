package scenes.viewport;

import math.Vec3;
import scenes.viewport.objects.*;
import scenes.viewport.lights.PointLight;
import java.util.List;

public final class ScenePresets {
    private ScenePresets() {}
    public static void resetCamera(ViewportState state) {
        state.eye(new Vec3(0,0,-1));
        state.cameraSensor(new Rect(new Vec3(-.5f,-.5f,0),new Vec3(1,0,0),new Vec3(0,1,0)));
        state.restart();
    }
    public static void load(ViewportState state,String name) {
        if(!name.equals("playground")&&!name.equals("triangle")&&!name.equals("bounce-room")&&!name.equals("glass")&&!name.equals("glass-inside")&&!name.equals("rough-room")&&!name.equals("mesh-room")&&!name.equals("volume-room")) throw new IllegalArgumentException("Presets: playground, triangle, bounce-room, glass, glass-inside, rough-room, mesh-room, volume-room");
        state.instances().clear();state.objects().clear();state.lights().clear();resetCamera(state);state.exposure(0);state.preset(name);
        state.pathDepth(name.equals("bounce-room") ? 3 : 0);
        state.paused(false);state.sampleTarget(0);
        state.acceleration(true);
        if(name.equals("volume-room")) {volumeRoom(state);return;}
        if(name.equals("mesh-room")) {meshRoom(state);return;}
        if(name.equals("rough-room")) {roughRoom(state);return;}
        if(name.startsWith("glass")) { glass(state,name.equals("glass-inside"));return; }
        if(name.equals("bounce-room")) { bounceRoom(state);return; }
        if(name.equals("triangle")) {
            state.instances().add(new SceneInstance("triangle",List.of(new Tri(new Vec3(-2,-2,10),new Vec3(2,-2,10),new Vec3(0,2,10))),
                    Transform.IDENTITY,Material.srgb("white",0xffffff)));
            state.addLight(new PointLight(new Vec3(0,0,5),new Vec3(1,1,1),80));return;
        }
        state.instances().add(new SceneInstance("sphere",List.of(new Sphere(Vec3.ZERO,1)),
                new Transform(new Vec3(-1.2f,-.45f,6),Vec3.ZERO,new Vec3(1,1,1)),Material.srgb("coral",0xe95638)));
        state.instances().add(new SceneInstance("box",SceneInstance.box(),
                new Transform(new Vec3(1.3f,-.65f,7),new Vec3(0,25,0),new Vec3(.8f,.8f,.8f)),Material.srgb("blue",0x388de9)));
        state.instances().add(new SceneInstance("floor",List.of(new Rect(new Vec3(-8,-1.5f,1),new Vec3(0,0,18),new Vec3(16,0,0))),
                Transform.IDENTITY,Material.srgb("floor",0xbdbdbd)));
        state.instances().add(new SceneInstance("backdrop",List.of(new Rect(new Vec3(-5,-1.5f,11),new Vec3(0,6,0),new Vec3(10,0,0))),
                Transform.IDENTITY,Material.srgb("green",0x609a56)));
        state.instances().add(new SceneInstance("reference",List.of(new Sphere(Vec3.ZERO,1)),
                new Transform(new Vec3(.1f,.5f,10),Vec3.ZERO,new Vec3(.6f,.6f,.6f)),Material.srgb("gold",0xeec447)));
        state.addLight(new PointLight(new Vec3(-2,3,3),new Vec3(1,1,1),160));
    }
    private static void meshRoom(ViewportState state) {
        roughRoom(state);
        var mesh=IndexedMesh.sphere(12);
        String[] names={"mesh-diffuse","mesh-mirror","mesh-clear","mesh-frosted"};
        Material[] materials={Material.srgb(names[0],0xe95638),Material.srgb(names[1],0xf5f5f5).withKind(Material.Kind.MIRROR),
                new Material(names[2],new Vec3(1,1,1),Material.Kind.DIELECTRIC),
                new Material(names[3],new Vec3(1,1,1),Material.Kind.DIELECTRIC).withRoughness(.25f)};
        for(int i=0;i<4;i++)state.instances().set(i,new SceneInstance(names[i],mesh,
                new Transform(new Vec3(-2.4f+i*1.6f,-.5f,6),new Vec3(0,15,0),new Vec3(.65f,.85f,.65f)),materials[i]));
    }
    private static void volumeRoom(ViewportState state) {
        roughRoom(state);state.pathDepth(12);
        state.instances().subList(0,4).clear();
        var cloudy=new Material("cloudy",new Vec3(1,1,1),Material.Kind.DIELECTRIC)
                .withIor(1).withScattering(1.5f).withAbsorption(new Vec3(.03f,.03f,.03f));
        state.instances().addFirst(new SceneInstance("cloudy-box",SceneInstance.box(),
                new Transform(new Vec3(1.3f,-.3f,6),new Vec3(0,20,0),new Vec3(.8f,1,.6f)),cloudy.withColor(new Vec3(1,1,1))));
        state.instances().addFirst(new SceneInstance("cloudy-sphere",List.of(new Sphere(Vec3.ZERO,1)),
                new Transform(new Vec3(-1.3f,-.3f,6),Vec3.ZERO,new Vec3(1,1,1)),
                new Material("sphere-cloud",cloudy.color(),cloudy.kind(),cloudy.ior(),cloudy.absorption(),0,Vec3.ZERO,cloudy.scattering(),0)));
    }
    private static void roughRoom(ViewportState state) {
        state.pathDepth(8);
        var silver=Material.srgb("polished",0xf5f5f5).withKind(Material.Kind.MIRROR);
        var glass=new Material("clear",new Vec3(1,1,1),Material.Kind.DIELECTRIC);
        String[] names={"polished-sphere","rough-sphere","clear-box","frosted-box"};
        Material[] materials={silver,silver.withRoughness(.45f),glass,glass.withRoughness(.35f)};
        // Give the pairs independent material identities so edits remain directly comparable.
        for(int j=0;j<4;j++) {
            var m=materials[j];m=new Material(names[j],m.color(),m.kind(),m.ior(),m.absorption(),m.roughness(),m.emission());
            state.instances().add(new SceneInstance(names[j],j<2?List.of(new Sphere(Vec3.ZERO,1)):SceneInstance.box(),
                    new Transform(new Vec3(-2.4f+j*1.6f,-.5f,6),Vec3.ZERO,new Vec3(.65f,.85f,j<2?.65f:.35f)),m));
        }
        state.instances().add(new SceneInstance("floor",List.of(new Rect(new Vec3(-6,-1.5f,1),new Vec3(0,0,12),new Vec3(12,0,0))),Transform.IDENTITY,Material.srgb("floor",0xbdbdbd)));
        for(int j=0;j<12;j++)state.instances().add(new SceneInstance("stripe-"+j,List.of(new Rect(new Vec3(-6+j,-1.5f,9),new Vec3(0,5,0),new Vec3(1,0,0))),Transform.IDENTITY,
                Material.srgb(j%2==0?"red":"blue",j%2==0?0xe95638:0x388de9)));
        state.instances().add(new SceneInstance("area-light",List.of(new Rect(new Vec3(-.5f,0,-.5f),new Vec3(1,0,0),new Vec3(0,0,1))),
                new Transform(new Vec3(0,3,5),Vec3.ZERO,new Vec3(3,1,3)),new Material("emitter",Vec3.ZERO).withEmission(new Vec3(12,12,12))));
    }
    private static void glass(ViewportState state, boolean inside) {
        state.pathDepth(8);
        var clear=new Material("clear",new Vec3(1,1,1),Material.Kind.DIELECTRIC);
        state.instances().add(new SceneInstance("glass-sphere",List.of(new Sphere(Vec3.ZERO,1)),
                new Transform(new Vec3(-1.15f,0,5),Vec3.ZERO,new Vec3(1,1,1)),clear));
        state.instances().add(new SceneInstance("glass-box",SceneInstance.box(),
                new Transform(new Vec3(1.2f,-.15f,5.5f),new Vec3(0,20,0),new Vec3(.8f,1,.6f)),
                new Material("tinted",new Vec3(1,1,1),Material.Kind.DIELECTRIC,1.5f,new Vec3(.8f,.15f,.04f))));
        for(int i=0;i<10;i++) state.instances().add(new SceneInstance("stripe-"+i,
                List.of(new Rect(new Vec3(-5+i,-2,9),new Vec3(0,5,0),new Vec3(1,0,0))),Transform.IDENTITY,
                Material.srgb(i%2==0?"stripe-red":"stripe-blue",i%2==0?0xe95638:0x388de9)));
        state.instances().add(new SceneInstance("floor",List.of(new Rect(new Vec3(-6,-1.5f,1),new Vec3(0,0,12),new Vec3(12,0,0))),Transform.IDENTITY,Material.srgb("floor",0xbdbdbd)));
        state.addLight(new PointLight(new Vec3(0,4,7),new Vec3(1,1,1),220));
        if(inside) {
            state.eye(new Vec3(-1.15f,0,5));
            state.cameraSensor(new Rect(new Vec3(-1.65f,-.5f,6),new Vec3(1,0,0),new Vec3(0,1,0)));
        }
    }
    private static void bounceRoom(ViewportState state) {
        var neutral = Material.srgb("neutral", 0xbdbdbd);
        state.instances().add(new SceneInstance("mirror-sphere", List.of(new Sphere(new Vec3(1,-.5f,6),1)),
                Transform.IDENTITY, Material.srgb("silver",0xf5f5f5).withKind(Material.Kind.MIRROR)));
        state.instances().add(new SceneInstance("sphere", List.of(new Sphere(new Vec3(-1.1f,-.65f,5.6f),.85f)),
                Transform.IDENTITY, Material.srgb("gold",0xefbf35)));
        state.instances().add(new SceneInstance("floor", List.of(new Rect(new Vec3(-3,-1.5f,2),new Vec3(0,0,9),new Vec3(6,0,0))),
                Transform.IDENTITY,neutral));
        state.instances().add(new SceneInstance("red-wall", List.of(new Rect(new Vec3(-3,-1.5f,2),new Vec3(0,5,0),new Vec3(0,0,9))),
                Transform.IDENTITY,Material.srgb("red",0xdb3528)));
        state.instances().add(new SceneInstance("green-wall", List.of(new Rect(new Vec3(3,-1.5f,2),new Vec3(0,0,9),new Vec3(0,5,0))),
                Transform.IDENTITY,Material.srgb("green",0x43b45b)));
        state.instances().add(new SceneInstance("backdrop", List.of(new Rect(new Vec3(-3,-1.5f,11),new Vec3(0,5,0),new Vec3(6,0,0))),
                Transform.IDENTITY,neutral));
        state.instances().add(new SceneInstance("ceiling", List.of(new Rect(new Vec3(-3,3.5f,2),new Vec3(6,0,0),new Vec3(0,0,9))),
                Transform.IDENTITY,neutral));
        state.addLight(new PointLight(new Vec3(0,2.8f,5),new Vec3(1,1,1),100));
    }
}
