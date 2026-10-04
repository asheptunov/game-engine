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
    }
    public static void load(ViewportState state,String name) {
        if(!name.equals("playground")&&!name.equals("triangle")) throw new IllegalArgumentException("Presets: playground, triangle");
        state.instances().clear();state.objects().clear();state.lights().clear();resetCamera(state);state.exposure(0);state.preset(name);
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
}
