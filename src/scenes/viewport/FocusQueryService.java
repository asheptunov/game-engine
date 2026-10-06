package scenes.viewport;

import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** One in-flight query, one completion mailbox, no FIFO of views. Query thread owns the cache. */
final class FocusQueryService implements AutoCloseable {
    interface Resolver {
        CameraFocus.Measurement resolve(FocusTargetSource source,Camera camera,CameraFocus.Scene scene,
                                        long epoch,long captured,LongSupplier clock);
    }
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{
        var t=new Thread(r,"viewport-focus-query");t.setDaemon(true);return t;
    });
    private final Resolver resolver;
    private volatile CameraFocus.Measurement completed;
    private volatile boolean busy,closed;
    FocusQueryService() {this(new CameraFocus()::resolve);}
    FocusQueryService(Resolver resolver) {this.resolver=resolver;}
    boolean submit(FocusTargetSource source,Camera camera,CameraFocus.Scene scene,long epoch,long captured,LongSupplier clock) {
        if(busy||closed)return false;
        busy=true;
        executor.execute(()->{
            try {var result=resolver.resolve(source,camera,scene,epoch,captured,clock);if(!closed)completed=result;}
            catch(RuntimeException error) {
                if(!closed)completed=new CameraFocus.Measurement(source,null,null,0,captured,clock.getAsLong(),epoch,scene,camera,"Focus query failed: "+error.getMessage());
            } finally {busy=false;}
        });
        return true;
    }
    CameraFocus.Measurement poll() {var value=completed;completed=null;return value;}
    boolean busy() {return busy;}
    @Override public void close() {closed=true;completed=null;executor.shutdownNow();}
}
