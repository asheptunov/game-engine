package editor;

import engine.Camera;
import harness.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static harness.Assertions.*;

public class EditorBindingPreferencesTest {
    public static void main(String[] args){harness.SuiteRunner.runThis();}

    @Test public void heldSpaceRoutesPanOnlyFromViewportAndClearsAcrossLifecycle()throws Exception{
        var files=defaults();var controller=onEdt(EditorController::new);var panel=onEdt(()->new SceneEditorPanel(controller,files.keys,files.mouse,files.user));
        try{
            onEdt(()->{panel.setSize(1120,720);layout(panel);return null;});var view=onEdt(()->panel.viewComponentForTest(0));
            Camera before=onEdt(()->panel.viewCameraForTest(0));
            onEdt(()->{panel.dispatchKeyForTest(key(view,KeyEvent.KEY_PRESSED,KeyEvent.VK_SPACE,' '));panel.mouseDragForTest(0,MouseEvent.BUTTON3,0,20,0);panel.dispatchKeyForTest(key(view,KeyEvent.KEY_RELEASED,KeyEvent.VK_SPACE,' '));return null;});
            Camera panned=onEdt(()->panel.viewCameraForTest(0));assertVec(before.forward(),panned.forward());assertFalse(before.eye().equals(panned.eye()));
            onEdt(()->{panel.dispatchKeyForTest(key(view,KeyEvent.KEY_PRESSED,KeyEvent.VK_SPACE,' '));panel.mouseDragForTest(0,MouseEvent.BUTTON2,0,20,0);panel.dispatchKeyForTest(key(view,KeyEvent.KEY_RELEASED,KeyEvent.VK_SPACE,' '));return null;});
            Camera middlePanned=onEdt(()->panel.viewCameraForTest(0));assertVec(panned.forward(),middlePanned.forward());

            var input=onEdt(panel::commandInputForTest);onEdt(()->{panel.dispatchKeyForTest(key(input,KeyEvent.KEY_PRESSED,KeyEvent.VK_SPACE,' '));panel.mouseDragForTest(0,MouseEvent.BUTTON3,0,20,0);return null;});
            Camera typedSpace=onEdt(()->panel.viewCameraForTest(0));assertFalse(middlePanned.forward().equals(typedSpace.forward()));
            onEdt(()->{panel.dispatchKeyForTest(key(view,KeyEvent.KEY_PRESSED,KeyEvent.VK_SPACE,' '));panel.setRenderingActive(false);panel.setRenderingActive(true);panel.mouseDragForTest(0,MouseEvent.BUTTON3,0,20,0);return null;});
            Camera resumed=onEdt(()->panel.viewCameraForTest(0));assertFalse(typedSpace.forward().equals(resumed.forward()));
            onEdt(()->{panel.bindingDialogActiveForTest(true);panel.dispatchKeyForTest(key(view,KeyEvent.KEY_PRESSED,KeyEvent.VK_SPACE,' '));panel.bindingDialogActiveForTest(false);panel.mouseDragForTest(0,MouseEvent.BUTTON3,0,20,0);return null;});
            assertFalse(resumed.forward().equals(onEdt(()->panel.viewCameraForTest(0)).forward()));
        }finally{onEdt(()->{panel.close();return null;});}
    }

    @Test public void preferencesValidateApplySaveAndRestartWithoutDirtyingScene()throws Exception{
        var files=defaults();var controller=onEdt(EditorController::new);var panel=onEdt(()->new SceneEditorPanel(controller,files.keys,files.mouse,files.user));
        try{
            assertFalse(onEdt(controller::dirty));var preferences=onEdt(panel::bindingPreferencesForTest);
            assertEquals(List.of("history.undo","history.redo","gesture.cancel","view.orbit","view.pan","view.zoom"),onEdt(preferences::actionIdsForTest));
            onEdt(()->{preferences.setBindingsForTest("history.redo",List.of());preferences.addBindingForTest("view.orbit","key.q+middle+drag+viewport");return null;});
            assertTrue(onEdt(()->preferences.bindingsForTest("history.redo").isEmpty()));assertTrue(onEdt(()->preferences.bindingsForTest("view.orbit").size())==3);
            assertTrue(onEdt(preferences::draftForTest).stream().filter(entry->entry.action().equals("view.orbit")).allMatch(entry->entry.kind()==EditorBindingProfile.Kind.MOUSE));
            writePreferences(preferences,Path.of(System.getProperty("editor.bindings.preview","out/editor/scene-editor-bindings-preview.png")));
            onEdt(()->{preferences.restoreDefaultsForTest();return null;});assertEquals(List.of("ctrl+shift+z"),onEdt(()->preferences.bindingsForTest("history.redo")));
            var valid=List.of(key("ctrl+z","history.undo"),key("ctrl+shift+z","history.redo"),key("escape","gesture.cancel"),
                    mouse("middle+drag+viewport","view.orbit"),mouse("key.q+middle+drag+viewport","view.orbit"),mouse("right+drag+viewport","view.pan"),mouse("wheel+viewport","view.zoom"));
            onEdt(()->{preferences.setRowsForTest(valid);preferences.applyForTest();return null;});assertTrue(onEdt(preferences::statusForTest).contains("Applied"));
            assertTrue(onEdt(panel::navigationHelpForTest).contains("Right-click and drag pan"));assertFalse(onEdt(controller::dirty));
            var before=onEdt(()->panel.viewCameraForTest(0));onEdt(()->{panel.mouseDragForTest(0,MouseEvent.BUTTON3,0,20,0);return null;});var after=onEdt(()->panel.viewCameraForTest(0));assertVec(before.forward(),after.forward());

            var duplicate=List.of(key("ctrl+z","history.undo"),key("ctrl+z","history.redo"),mouse("right+drag+viewport","view.orbit"));
            onEdt(()->{preferences.setRowsForTest(duplicate);preferences.applyForTest();return null;});assertTrue(onEdt(preferences::statusForTest).toLowerCase().contains("duplicate"));
            assertTrue(onEdt(panel::navigationHelpForTest).contains("Right-click and drag pan"));
            var deadMode=List.of(key("ctrl+z","history.undo"),mouse("right+drag+banana","view.orbit"));
            onEdt(()->{preferences.setRowsForTest(deadMode);preferences.applyForTest();return null;});assertTrue(onEdt(preferences::statusForTest).contains("viewport"));
            var left=List.of(key("ctrl+z","history.undo"),mouse("left+drag+viewport","view.orbit"));
            onEdt(()->{preferences.setRowsForTest(left);preferences.applyForTest();return null;});assertTrue(onEdt(preferences::statusForTest).contains("reserved"));

            onEdt(()->{preferences.setRowsForTest(valid);preferences.saveForTest();return null;});assertTrue(onEdt(preferences::statusForTest).contains("Saved"));var saved=Files.readString(files.user);assertTrue(saved.contains("mouse.right+drag+viewport = view.pan"));assertTrue(saved.contains("mouse.key.q+middle+drag+viewport = view.orbit"));
        }finally{onEdt(()->{panel.close();return null;});}
        var restartedController=onEdt(EditorController::new);var restarted=onEdt(()->new SceneEditorPanel(restartedController,files.keys,files.mouse,files.user));
        try{var help=onEdt(restarted::navigationHelpForTest);assertTrue(help.contains("Right-click and drag pan"));assertTrue(help.contains("Q+Middle-click and drag"));assertFalse(onEdt(restartedController::dirty));}
        finally{onEdt(()->{restarted.close();return null;});}
    }

    @Test public void brokenOverrideFallsBackVisiblyAndFailedSavePreservesLiveBindingsAndFile()throws Exception{
        var files=defaults();Files.writeString(files.user,"mouse.right+drag+banana = view.orbit\n");var broken=Files.readAllBytes(files.user);
        var controller=onEdt(EditorController::new);var panel=onEdt(()->new SceneEditorPanel(controller,files.keys,files.mouse,files.user));
        try{assertTrue(onEdt(panel::bindingStatusForTest).contains("ignored"));assertTrue(onEdt(panel::navigationHelpForTest).contains("Right-click and drag orbit"));assertTrue(java.util.Arrays.equals(broken,Files.readAllBytes(files.user)));}
        finally{onEdt(()->{panel.close();return null;});}

        var failureFiles=defaults();Files.createDirectory(failureFiles.user);var failureController=onEdt(EditorController::new);var failurePanel=onEdt(()->new SceneEditorPanel(failureController,failureFiles.keys,failureFiles.mouse,failureFiles.user));
        try{
            var preferences=onEdt(failurePanel::bindingPreferencesForTest);var changed=List.of(key("ctrl+z","history.undo"),mouse("right+drag+viewport","view.pan"));
            onEdt(()->{preferences.setRowsForTest(changed);preferences.saveForTest();return null;});assertTrue(onEdt(preferences::statusForTest).contains("Could not save"));
            assertTrue(onEdt(failurePanel::navigationHelpForTest).contains("Right-click and drag orbit"));assertTrue(Files.isDirectory(failureFiles.user));
        }finally{onEdt(()->{failurePanel.close();return null;});}
    }

    private record Fileset(Path keys,Path mouse,Path user){}
    private static Fileset defaults()throws Exception{var directory=Files.createTempDirectory("editor-binding-preferences");var keys=directory.resolve("keys.properties");var mouse=directory.resolve("mouse.properties");
        Files.writeString(keys,"ctrl+shift+z = history.redo\nctrl+z = history.undo\nescape = gesture.cancel\n");
        Files.writeString(mouse,"middle+drag+viewport = view.orbit\nright+drag+viewport = view.orbit\nkey.space+middle+drag+viewport = view.pan\nkey.space+right+drag+viewport = view.pan\nwheel+viewport = view.zoom\n");return new Fileset(keys,mouse,directory.resolve("override.properties"));}
    private static EditorBindingProfile.Entry key(String chord,String action){return new EditorBindingProfile.Entry(EditorBindingProfile.Kind.KEY,chord,action);}
    private static EditorBindingProfile.Entry mouse(String chord,String action){return new EditorBindingProfile.Entry(EditorBindingProfile.Kind.MOUSE,chord,action);}
    private static KeyEvent key(Component component,int id,int code,char value){return new KeyEvent(component,id,System.currentTimeMillis(),0,code,value);}
    private static void assertVec(math.Vec3 expected,math.Vec3 actual){assertTrue(Math.abs(expected.x()-actual.x())<1e-5f);assertTrue(Math.abs(expected.y()-actual.y())<1e-5f);assertTrue(Math.abs(expected.z()-actual.z())<1e-5f);}
    private static void writePreferences(BindingPreferencesPanel panel,Path output)throws Exception{var image=new BufferedImage(800,760,BufferedImage.TYPE_INT_RGB);onEdt(()->{panel.setSize(image.getWidth(),image.getHeight());layout(panel);var graphics=image.createGraphics();panel.printAll(graphics);graphics.dispose();return null;});Files.createDirectories(output.toAbsolutePath().getParent());ImageIO.write(image,"png",output.toFile());assertTrue(Files.size(output)>10_000);}
    private static void layout(Container container){container.doLayout();for(var child:container.getComponents())if(child instanceof Container nested)layout(nested);}
    private static <T>T onEdt(Callable<T> action)throws Exception{if(SwingUtilities.isEventDispatchThread())return action.call();var value=new AtomicReference<T>();var error=new AtomicReference<Throwable>();SwingUtilities.invokeAndWait(()->{try{value.set(action.call());}catch(Throwable failure){error.set(failure);}});if(error.get()!=null)throw new RuntimeException(error.get());return value.get();}
}
