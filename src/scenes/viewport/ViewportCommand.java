package scenes.viewport;

import math.Vec3;
import misc.monads.Result;
import scenes.viewport.lights.PointLight;
import ui.console.Command;
import java.util.Arrays;

/** Console edits and rendering share the state monitor, so a frame observes one complete edit. */
public final class ViewportCommand implements Command {
    private final ViewportState state;
    private String selected="sphere";
    public ViewportCommand(ViewportState state) { this.state=state; }
    public static final String HELP="view status | preset playground/triangle/bounce-room/glass/glass-inside/rough-room | reset | camera reset\n"
            +"view select <object> | material <name> | color <RRGGBB>\n"
            +"view type diffuse/mirror/dielectric (glass requires a closed sphere/box)\n"
            +"view ior <1..3> | absorption <red> <green> <blue> (0..100 per scene unit)\n"
            +"view roughness <0..1> (mirror/glass; 0 ideal) | emission <r> <g> <b> (linear radiance 0..10000; rect only)\n"
            +"Glass uses distance absorption, not base color; nested nonintersecting solids supported.\n"
            +"Glass blocks direct shadow queries; focused refractive caustics are not guaranteed.\n"
            +"view move/rotate/scale <x> <y> <z> (absolute; degrees; positive scale)\n"
            +"view light position <x> <y> <z> | light color <RRGGBB> | light intensity <n>\n"
            +"In rough-room these edit the area emitter; light size <width> <depth> (fixed radiance, more area = more power).\n"
            +"view exposure <-16..16 stops>; Esc closes console; WASD moves; Space/Ctrl up/down\n"
            +"Mouse movement looks around (no button needed); R resets camera; movement follows the view.\n"
            +"view resolution <64..1600> (square sensor; default 1600, try 400 for editing)\n"
            +"view depth <0..32> | samples <1..8 per batch> | seed <integer> | restart\n"
            +"view target <spp; 0 continuous> | pause | resume; F3 metrics, F4 CPU samples.\n"
            +"50ms batch budget checked after each full sensor sample; depth 0 uses pixel centers.";
    @Override public Result<String,String> run(String... raw) {
        var args=Arrays.stream(raw).filter(s->!s.isBlank()).toArray(String[]::new);
        synchronized(state) {
            try {
                if(args.length<2||args[1].equals("help")) return Result.success(HELP);
                String op=args[1];
                switch(op) {
                    case "status" -> require(args,2);
                    case "preset" -> {require(args,3);ScenePresets.load(state,args[2]);selected=state.instances().getFirst().name();}
                    case "reset" -> {require(args,2);ScenePresets.load(state,state.preset());selected=state.instances().getFirst().name();}
                    case "camera" -> {require(args,3);if(!args[2].equals("reset")) throw new IllegalArgumentException("view camera reset");ScenePresets.resetCamera(state);}
                    case "exposure" -> {require(args,3);state.exposure(number(args[2]));}
                    case "resolution" -> {require(args,3);state.resolution(Integer.parseInt(args[2]));}
                    case "depth" -> {require(args,3);state.pathDepth(Integer.parseInt(args[2]));}
                    case "samples" -> {require(args,3);state.samplesPerFrame(Integer.parseInt(args[2]));}
                    case "seed" -> {require(args,3);state.seed(Long.parseLong(args[2]));}
                    case "target" -> {require(args,3);state.sampleTarget(Long.parseLong(args[2]));}
                    case "restart" -> {require(args,2);state.restart();}
                    case "pause", "resume" -> {require(args,2);state.paused(op.equals("pause"));}
                    case "select" -> {require(args,3);index(args[2]);selected=args[2];}
                    case "color" -> {
                        require(args,3);int index=index(selected);var object=state.instances().get(index);
                        var material=object.material().withColor(Material.srgb(object.material().name(),rgb(args[2])).color());
                        // Shared material edits affect every referencing object.
                        editMaterial(material);
                    }
                    case "type" -> {
                        require(args,3);var object=state.instances().get(index(selected));
                        var material=object.material().withKind(Material.Kind.valueOf(args[2].toUpperCase(java.util.Locale.ROOT)));
                        editMaterial(material);
                    }
                    case "ior" -> {require(args,3);editMaterial(state.instances().get(index(selected)).material().withIor(number(args[2])));}
                    case "absorption" -> {require(args,5);editMaterial(state.instances().get(index(selected)).material().withAbsorption(vector(args,2)));}
                    case "roughness" -> {require(args,3);editMaterial(state.instances().get(index(selected)).material().withRoughness(number(args[2])));}
                    case "emission" -> {require(args,5);editMaterial(state.instances().get(index(selected)).material().withEmission(vector(args,2)));}
                    case "material" -> {
                        require(args,3);var material=state.instances().stream().map(SceneInstance::material).filter(m->m.name().equals(args[2])).findFirst()
                                .orElseThrow(()->new IllegalArgumentException("Unknown material: "+args[2]));
                        int index=index(selected);state.instances().set(index,state.instances().get(index).withMaterial(material));
                    }
                    case "move", "rotate", "scale" -> {
                        require(args,5);int index=index(selected);var object=state.instances().get(index);var t=object.transform();var v=vector(args,2);
                        var next=new Transform(op.equals("move")?v:t.position,op.equals("rotate")?v:t.rotation,op.equals("scale")?v:t.scale);
                        state.instances().set(index,object.withTransform(next));
                    }
                    case "light" -> {
                        if(args.length<3) throw new IllegalArgumentException("view light position/color/intensity ...");
                        if(state.instances().stream().anyMatch(o->o.name().equals("area-light"))) {editAreaLight(args);break;}
                        if(state.lights().isEmpty())throw new IllegalArgumentException("No point light; select a rectangular object and edit emission");
                        var light=(PointLight)state.lights().getFirst();
                        PointLight next=switch(args[2]) {
                            case "position" -> {require(args,6);yield new PointLight(vector(args,3),light.color(),light.intensity());}
                            case "color" -> {require(args,4);yield new PointLight(light.position(),Material.srgb("light",rgb(args[3])).color(),light.intensity());}
                            case "intensity" -> {require(args,4);yield new PointLight(light.position(),light.color(),number(args[3]));}
                            default -> throw new IllegalArgumentException("view light position/color/intensity ...");
                        };
                        state.lights().set(0,next);
                    }
                    default -> throw new IllegalArgumentException("Unknown view command; view help");
                }
                return Result.success(status());
            } catch(IllegalArgumentException e) { return Result.failure(e.getMessage()); }
        }
    }
    private String status() {
        var names=state.instances().stream().map(SceneInstance::name).toList();
        var materials=state.instances().stream().map(o->o.material().name()).distinct().toList();
        String object=state.instances().stream().filter(o->o.name().equals(selected)).findFirst().map(o->o.name()+" "+o.transform()+" material="+o.material().name()+" type="+o.material().kind()+" linear RGB="+o.material().color()+" IOR="+o.material().ior()+" absorption="+o.material().absorption()+" roughness="+o.material().roughness()+" emission="+o.material().emission()).orElse("none");
        return "Preset="+state.preset()+" exposure="+state.exposure()+" stops; sensor="+state.sensorPixelsW()+"x"+state.sensorPixelsH()
                +"; depth="+state.pathDepth()+"; spp/batch max="+state.samplesPerFrame()+"; accumulated="+state.accumulatedSamples()
                +"; "+state.samplingStatus()+"; target="+state.sampleTarget()+"; seed="+state.seed()
                +"\nObjects="+names+" materials="+materials+"\nSelected: "+object+"\nPoint lights: "+state.lights()
                +"\nEmitters: "+state.instances().stream().filter(o->o.material().emissive()).map(o->o.name()+" "+o.transform()+" radiance="+o.material().emission()).toList()+"\nview help for controls";
    }
    private void editAreaLight(String[] args) {
        int index=index("area-light");var object=state.instances().get(index);var t=object.transform();var m=object.material();
        switch(args[2]) {
            case "position" -> {require(args,6);object=object.withTransform(new Transform(vector(args,3),t.rotation,t.scale));}
            case "size" -> {require(args,5);object=object.withTransform(new Transform(t.position,t.rotation,new Vec3(number(args[3]),t.scale.y(),number(args[4]))));}
            case "color", "intensity" -> {
                require(args,4);float peak=Math.max(m.emission().x(),Math.max(m.emission().y(),m.emission().z()));
                Vec3 color=peak>0?m.emission().scale(1/peak):new Vec3(1,1,1);
                if(args[2].equals("color")) color=Material.srgb("light",rgb(args[3])).color();
                else peak=number(args[3]);
                editMaterial(m.withEmission(color.scale(peak)));return;
            }
            default -> throw new IllegalArgumentException("view light position/color/intensity/size ...");
        }
        state.instances().set(index,object);
    }
    private void editMaterial(Material material) {
        // Validate every affected instance before publishing any shared edit.
        var next=state.instances().stream().map(o->o.material().name().equals(material.name())?o.withMaterial(material):o).toList();
        for(int i=0;i<next.size();i++) state.instances().set(i,next.get(i));
    }
    private int index(String name) {
        for(int i=0;i<state.instances().size();i++) if(state.instances().get(i).name().equals(name)) return i;
        throw new IllegalArgumentException("Unknown object: "+name);
    }
    private static void require(String[] args,int length) {if(args.length!=length) throw new IllegalArgumentException("Invalid arguments; view help");}
    private static float number(String s) {float n=Float.parseFloat(s);if(!Float.isFinite(n)) throw new IllegalArgumentException("Value must be finite");return n;}
    private static Vec3 vector(String[] args,int start) {return new Vec3(number(args[start]),number(args[start+1]),number(args[start+2]));}
    private static int rgb(String s) {if(!s.matches("#?[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Color needs six hex digits");return Integer.parseInt(s.replace("#",""),16);}
}
