package engine;

import java.util.function.LongSupplier;

/** Live-state policy and time. Call under the state monitor; workers see only realized optics. */
public final class FocusController implements AutoCloseable {
    enum Curve { LINEAR, SMOOTH }
    private final ViewportState state;
    private final LongSupplier clock;
    private FocusQueryService service;
    private FocusTargetSource source=FocusTargetSource.CENTER;
    private boolean auto,live,suspended,closed,wasActive=true;
    private long epoch,wall,active,nextQuery,candidateSince,acceptedAt=-1;
    private double duration=300,delay=150,tolerance=1;
    private Curve curve=Curve.SMOOTH;
    private CameraFocus.Scene scene;
    private CameraFocus.Subject candidate,subject;
    private float start,target;
    private long segmentStart;
    private boolean pulling;
    private String phase="settled",message="";
    private Pending pending;
    private record Pending(FocusTargetSource source,boolean pull,long epoch) {}
    FocusController(ViewportState state) {this(state,System::nanoTime);}
    FocusController(ViewportState state,LongSupplier clock) {
        this.state=state;this.clock=clock;wall=clock.getAsLong();target=state.camera().focus();
    }
    public void enableLive() {live=true;}
    void service(FocusQueryService value) {service=value;live=true;}
    public FocusTargetSource source() {return source;}
    public long epoch() {return epoch;}
    boolean isAuto() {return auto;}
    float target() {return target;}
    boolean pulling() {return pulling;}
    private boolean active() {return !suspended&&!closed&&!state.paused();}
    private void time() {
        long now=clock.getAsLong();boolean enabled=active();
        if(wasActive&&enabled)active+=Math.max(0,now-wall);
        if(wasActive!=enabled) {
            invalidate();
            if(auto) {pulling=false;target=state.camera().focus();}
            phase=enabled?(auto?"waiting":pulling?"pulling":"settled"):"suspended";
        }
        wall=now;wasActive=enabled;
    }
    private void evaluate() {
        if(!pulling)return;
        double t=duration==0?1:Math.min(1,(active-segmentStart)/(duration*1e6));
        double c=curve==Curve.LINEAR?t:t*t*(3-2*t);
        float value=t>=1?target:(float)(1/(1.0/start+(1.0/target-1.0/start)*c));
        if(state.camera().focus()!=value)state.camera(state.camera().withFocus(value));
        if(t>=1) {pulling=false;phase="settled";}
    }
    private void segment(float distance) {
        state.camera().withFocus(distance);
        evaluate();
        if(target==distance && (pulling || state.camera().focus()==distance)) {phase=pulling?"pulling":"settled";return;}
        start=state.camera().focus();target=distance;segmentStart=active;
        pulling=start!=target;phase=pulling?"pulling":"settled";evaluate();
    }
    public void manual(float distance,boolean pull) {
        state.camera().withFocus(distance);time();evaluate();
        auto=false;invalidate();message="";
        if(pull)segment(distance);
        else {pulling=false;target=distance;state.camera(state.camera().withFocus(distance));phase="settled";}
    }
    public void mode(String value) {
        if(!value.equals("auto")&&!value.equals("manual"))throw new IllegalArgumentException("Focus mode must be manual or auto");
        boolean next=value.equals("auto");
        if(next==auto && (next||!pulling&&pending==null))return;
        time();evaluate();auto=next;invalidate();pulling=false;target=state.camera().focus();phase=auto?"waiting":"settled";
    }
    public void source(FocusTargetSource value) {
        if(source.equals(value))return;
        time();evaluate();source=value;invalidate();
        if(auto) {pulling=false;target=state.camera().focus();phase="waiting";}
    }
    public void duration(double value) {
        range(value,0,10000,"Duration");if(duration==value)return;
        time();evaluate();duration=value;restartSegment();
    }
    public void curve(String value) {
        Curve next;
        try {next=Curve.valueOf(value.toUpperCase(java.util.Locale.ROOT));}
        catch(IllegalArgumentException e) {throw new IllegalArgumentException("Curve must be linear or smooth");}
        if(curve==next)return;time();evaluate();curve=next;restartSegment();
    }
    private void restartSegment() {if(pulling) {start=state.camera().focus();segmentStart=active;evaluate();}}
    public void delay(double value) {range(value,0,2000,"Delay");delay=value;}
    public void tolerance(double value) {range(value,0,25,"Tolerance");tolerance=value;}
    private static void range(double value,double min,double max,String label) {
        if(!Double.isFinite(value)||value<min||value>max)throw new IllegalArgumentException(label+" must be "+min+".."+max);
    }
    void invalidate() {
        epoch++;candidate=null;subject=null;acceptedAt=-1;
        if(pending!=null)message="Focus view/selection changed; retry";
        pending=null;
    }
    public void framingChanged() {invalidate();if(auto){pulling=false;target=state.camera().focus();phase="waiting";}}
    public void suspend(boolean value) {
        if(suspended==value)return;
        time();evaluate();suspended=value;time();
    }
    void pauseChanged() {time();}
    public void reset() {
        invalidate();auto=false;source=FocusTargetSource.CENTER;duration=300;delay=150;tolerance=1;curve=Curve.SMOOTH;
        pulling=false;target=state.camera().focus();phase="settled";message="";wall=clock.getAsLong();
    }
    /** Live screen commands enqueue; headless command callers resolve outside the monitor. */
    public boolean request(FocusTargetSource selection,boolean pull) {
        if(!live)return false;
        time();epoch++;pending=new Pending(selection,pull,epoch);message="Focus query pending";return true;
    }
    public void tick() {
        time();if(!active())return;
        evaluate();
        var currentScene=CameraFocus.Scene.capture(state);
        if(scene!=null&&!scene.equals(currentScene))framingChanged();
        scene=currentScene;
        if(service!=null) {
            var measured=service.poll();
            if(measured!=null) {
                if(pending!=null && measured.epoch()==pending.epoch()) {
                    var intent=pending;pending=null;
                    if(!measured.camera().equals(state.camera()) || !measured.scene().equals(scene))message="Focus view changed; retry";
                    else if(measured.error()!=null)message=measured.error();
                    else {manual(measured.distance(),intent.pull());message="Focus distance="+measured.distance()+" units (first surface)";}
                } else if(auto)accept(measured);
            }
        }
        if(!live || (!auto&&pending==null) || clock.getAsLong()<nextQuery)return;
        if(service==null)service=new FocusQueryService();
        var selection=pending==null?source:pending.source();
        if(service.submit(selection,state.camera(),scene,epoch,clock.getAsLong(),clock))nextQuery=clock.getAsLong()+50_000_000L;
    }
    /** Resolver-independent boundary for replay and future alternate providers. */
    void accept(CameraFocus.Measurement measurement) {
        if(!auto||!active()||measurement.epoch()!=epoch||!measurement.source().equals(source)
                || !measurement.scene().equals(CameraFocus.Scene.capture(state)))return;
        long now=clock.getAsLong();
        if(now-measurement.captured()>100_000_000L) {phase="stale";return;}
        float distance=measurement.distance();
        if(measurement.error()==null && measurement.hit()!=null) {
            if(source instanceof FocusTargetSource.Screen screen && !new CameraProjection(state.camera()).near(
                    measurement.hit().x(),measurement.hit().y(),measurement.hit().z(),screen.u(),screen.v())) {phase="stale";return;}
            distance=measurement.hit().sub(state.eye()).dot(state.camera().forward());
        }
        try {
            if(measurement.error()!=null)throw new IllegalArgumentException(measurement.error());
            if(measurement.subject()==null)throw new IllegalArgumentException("Focus query has no subject");
            state.camera().withFocus(distance);
        } catch(IllegalArgumentException error) {
            evaluate();pulling=false;target=state.camera().focus();candidate=null;subject=null;phase="lost";message=error.getMessage();return;
        }
        if(!measurement.subject().equals(subject)) {
            if(!measurement.subject().equals(candidate)) {candidate=measurement.subject();candidateSince=active;}
            if(active-candidateSince<delay*1e6) {phase="waiting";return;}
            subject=measurement.subject();candidate=null;
        } else {
            candidate=null;
            double q=1.0/distance,accepted=1.0/target;
            if(Math.abs(q-accepted)/Math.max(q,accepted)<=tolerance/100) {
                acceptedAt=measurement.captured();message="";phase=pulling?"pulling":"settled";return;
            }
        }
        acceptedAt=measurement.captured();message="";segment(distance);
    }
    public String status() {
        return String.format(java.util.Locale.ROOT,"focus=%s source=%s actual=%.5g target=%.5g units %s duration=%.0fms curve=%s delay=%.0fms tolerance=%.3g%% age=%s%s",
                auto?"auto":"manual",source,state.camera().focus(),target,active()?phase:"suspended",duration,curve.name().toLowerCase(java.util.Locale.ROOT),delay,tolerance,
                acceptedAt<0?"n/a":Math.max(0,(clock.getAsLong()-acceptedAt)/1_000_000)+"ms",message.isEmpty()?"":"; "+message);
    }
    public String[] overlay(Camera shown) {
        return new String[]{String.format(java.util.Locale.ROOT,"Focus %s: %s; actual %.5g / target %.5g units; %s; age %s",
                auto?"auto":"manual",active()?phase:"suspended",state.camera().focus(),target,source,
                acceptedAt<0?"n/a":Math.max(0,(clock.getAsLong()-acceptedAt)/1_000_000)+"ms"),
                String.format(java.util.Locale.ROOT,"Pull %.0fms %s; acquisition delay %.0fms, tolerance %.3g%%",duration,curve.name().toLowerCase(java.util.Locale.ROOT),delay,tolerance),
                shown==null?"Shown optics: pending":String.format(java.util.Locale.ROOT,"Shown optics: %s, focus %.5g units, aperture %.5g units",shown.projection().name().toLowerCase(java.util.Locale.ROOT),shown.focus(),shown.aperture())};
    }
    @Override public void close() {closed=true;invalidate();if(service!=null)service.close();}
}
