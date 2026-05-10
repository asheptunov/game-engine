import di.Injector;
import timing.PeriodicExecutor;

public static void main(String[] ignoredArgs) throws InterruptedException {
    var module = new MainModule();
    var injector = Injector.create(module);
    module.registerScenes(injector);
    injector.get(PeriodicExecutor.class).execute();
}
