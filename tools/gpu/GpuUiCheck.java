package game;

import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Exercise the real game panel without native windows, pointer capture or focus changes. */
public final class GpuUiCheck {
    private GpuUiCheck() {}
    public static void main(String[] args) throws Exception {
        var navigation=new GameNavigation();navigation.fixedResolution(960,600);
        try(var renderer=new GameRenderer(navigation,BlockWorld.landscape(),"gpu")) {
            long deadline=System.nanoTime()+30_000_000_000L;
            while(renderer.frame()==null && System.nanoTime()<deadline) {
                if(renderer.error()!=null) throw new AssertionError(renderer.error());
                Thread.sleep(5);
            }
            if(renderer.frame()==null) throw new AssertionError("No GPU game image");
            final GamePanel[] holder=new GamePanel[1];
            try {
            SwingUtilities.invokeAndWait(()->{
                var panel=new GamePanel(navigation,renderer);holder[0]=panel;panel.setSize(960,600);layout(panel);
                panel.dispatchEvent(new MouseEvent(panel,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,30,175,1,false,MouseEvent.BUTTON1));
                if(!renderer.adaptive()) throw new AssertionError("GPU adaptive button did not enable");
                renderer.adaptive(false);
                var before=renderer.lighting();
                panel.dispatchEvent(new MouseEvent(panel,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,30,130,1,false,MouseEvent.BUTTON1));
                if(renderer.lighting().equals(before)) throw new AssertionError("Sun control did not change lighting");
                panel.renderingActive(false);panel.renderingActive(true);
            });
            long initialGeneration=renderer.restart();
            awaitSamples(renderer, frame -> frame.generation()==initialGeneration && frame.samples() >= 4);
            SwingUtilities.invokeAndWait(()->clickRefinement(holder[0]));
            if(renderer.refinement() || navigation.captured()) throw new AssertionError("Refinement toggle did not disable without capture");
            awaitSamples(renderer, frame -> frame.samples() == 1);
            long publication=renderer.frame().publicationNanos();
            Thread.sleep(150);
            if(renderer.frame().publicationNanos()!=publication) throw new AssertionError("Refinement continued while off");
            navigation.capture();navigation.look(40,0);navigation.release();
            awaitSamples(renderer, frame -> frame.publicationNanos()!=publication && frame.samples()==1);
            if(renderer.frame().image().getWidth()!=960) throw new AssertionError("Refinement changed resolution");
            SwingUtilities.invokeAndWait(()->clickRefinement(holder[0]));
            if(!renderer.refinement()) throw new AssertionError("Refinement toggle did not enable");
            awaitSamples(renderer, frame -> frame.samples() >= 4);
            SwingUtilities.invokeAndWait(()->{
                var lightingGrid=(javax.swing.JCheckBox)find(holder[0],"Shared 3D fog lighting (experimental)");
                if(lightingGrid==null) throw new AssertionError("Missing lighting grid control");
                lightingGrid.doClick();
                if(!renderer.settings().fogLightingGrid()) throw new AssertionError("Grid control did not enable");
                lightingGrid.doClick();
                if(renderer.settings().fogLightingGrid()) throw new AssertionError("Grid control did not disable");
                var jitter=(javax.swing.JCheckBox)find(holder[0],"Jitter fog samples (experimental)");
                if(jitter==null) throw new AssertionError("Missing jitter control");
                jitter.doClick();if(!renderer.settings().fogJitter()) throw new AssertionError("Jitter did not enable");
                jitter.doClick();if(renderer.settings().fogJitter()) throw new AssertionError("Jitter did not disable");
                var air=(javax.swing.JComboBox<?>)find(holder[0],"atmosphereResolution");
                if(air==null) throw new AssertionError("Missing atmosphere quality control");
                air.setSelectedIndex(1);
                if(renderer.settings().fogDownsample()!=2) throw new AssertionError("Half atmosphere control failed");
                air.setSelectedIndex(0);
                var fog=(javax.swing.JComboBox<?>)find(holder[0],"fogResolution");
                if(fog==null) throw new AssertionError("Missing fog resolution control");
                var fogPage=(java.awt.Container)fog.getParent();
                var oldSize=fogPage.getSize();
                for(int height:new int[]{600,1080,2160}) {
                    fogPage.setSize(900,height);layout(fogPage);
                    if(fog.getHeight()>fog.getPreferredSize().height) throw new AssertionError("Fog dropdown stretched vertically");
                }
                fogPage.setSize(oldSize);layout(fogPage);
                fog.setSelectedIndex(2);
                if(renderer.settings().fogDownsample()!=4) throw new AssertionError("Quarter fog control failed");
                var settingsPanel=(GameSettingsPanel)javax.swing.SwingUtilities.getAncestorOfClass(GameSettingsPanel.class,air);
                var budget=(javax.swing.JComboBox<?>)find(holder[0],"frameBudget");
                if(budget==null) throw new AssertionError("Missing frame budget selector");
                budget.setSelectedIndex(2);
                if(!renderer.adaptive() || Math.abs(renderer.settings().interactiveMillis()-1000./120)>.001)
                    throw new AssertionError("120 FPS selection failed");
                var budgetSettings=renderer.settings();
                settingsPanel.refresh();
                if(!renderer.settings().equals(budgetSettings)) throw new AssertionError("Refresh mutated budget");
                budget.setSelectedIndex(1);
                if(Math.abs(renderer.settings().interactiveMillis()-1000./60)>.001) throw new AssertionError("60 FPS selection failed");
                budget.setSelectedIndex(0);
                if(renderer.adaptive()) throw new AssertionError("Fixed selection failed");
                settingsPanel.refresh();
                if(air.getSelectedIndex()!=2) throw new AssertionError("Air/fog selectors did not synchronize");
                fog.setSelectedIndex(1);
                if(renderer.settings().fogDownsample()!=2) throw new AssertionError("Half fog control failed");
                fog.setSelectedIndex(0);
                if(renderer.settings().fogDownsample()!=1) throw new AssertionError("Full fog control failed");
                var aperture=(javax.swing.JSpinner)find(holder[0],"Aperture radius (world units)");
                if(aperture==null || !aperture.isEnabled()) throw new AssertionError("GPU depth of field must be enabled");
                aperture.setValue(.12);
                var focus=(javax.swing.JSpinner)find(holder[0],"Focus distance (world units)");focus.setValue(8.0);
                if(navigation.aperture()!=.12f || navigation.focus()!=8f) throw new AssertionError("GPU optics controls failed");
                var exposure=(javax.swing.JSpinner)find(holder[0],"Exposure multiplier");
                exposure.setValue(1.5);
                if(renderer.exposure()!=1.5f) throw new AssertionError("Exposure control failed");
                var depth=(javax.swing.JSpinner)find(holder[0],"Path depth");
                depth.setValue(1.0);
                if(renderer.settings().pathDepth()!=1) throw new AssertionError("Depth control failed");
            });
            long opticsGeneration=renderer.restart();
            awaitSamples(renderer, frame -> frame.generation()==opticsGeneration && frame.samples() >= 4);
            navigation.pose(new math.Vec3(0, 3, 0), 0, -Math.PI / 4);
            var focusWorld=BlockWorld.landscape().snapshot();
            var measurement=new engine.CameraFocus().resolve(engine.FocusTargetSource.CENTER,
                    navigation.view().camera(),new engine.CameraFocus.Scene(focusWorld.instances(),focusWorld.legacyObjects(),0),
                    0,System.nanoTime(),System::nanoTime);
            if(measurement.error()!=null) throw new AssertionError(measurement.error());
            SwingUtilities.invokeAndWait(()->{
                var auto=(javax.swing.JCheckBox)find(holder[0],"Autofocus (screen center)");
                if(auto==null || !auto.isEnabled()) throw new AssertionError("GPU autofocus unavailable");
                auto.doClick();
            });
            awaitSamples(renderer, frame -> frame.camera().focus()==measurement.distance() && frame.samples()>=4);
            SwingUtilities.invokeAndWait(()->{
                var focus=(javax.swing.JSpinner)find(holder[0],"Focus distance (world units)");focus.setValue(9.0);
                if(navigation.autofocus()) throw new AssertionError("Manual distance did not disable autofocus");
            });
            var image=new BufferedImage(960,600,BufferedImage.TYPE_INT_RGB);
            SwingUtilities.invokeAndWait(()->{
                navigation.optics(.04f,9);
                renderer.configure(s->s.withFoveated(true));
                var setup=(javax.swing.JButton)find(holder[0],"Set up GPU reconstruction");
                if(setup==null) throw new AssertionError("Missing GPU reconstruction setup");
                setup.doClick();
                if(navigation.aperture()!=0 || renderer.settings().foveated()
                        || renderer.settings().fogDownsample()!=1 || !renderer.settings().temporal())
                    throw new AssertionError("GPU reconstruction setup did not apply compatible settings");
            });
            awaitSamples(renderer,frame->frame.camera().aperture()==0 && frame.context()!=null
                    && frame.context().settings().temporal() && frame.samples()>=4);
            SwingUtilities.invokeAndWait(()->{
                var lighting=(javax.swing.JComboBox<?>)find(holder[0],"lightingResolution");
                if(lighting==null) throw new AssertionError("Missing lighting resolution control");
                lighting.setSelectedIndex(2);
                if(renderer.settings().lightingScale()!=.5 || renderer.settings().interactive())
                    throw new AssertionError("Half lighting did not select fixed reconstruction");
            });
            awaitSamples(renderer,frame->frame.context()!=null && frame.context().settings().lightingScale()==.5
                    && frame.samples()>=4);
            if(renderer.frame().image().getWidth()!=960 || renderer.frame().image().getHeight()!=600)
                throw new AssertionError("Game conversion lost native output dimensions");
            if(java.nio.file.Files.isRegularFile(java.nio.file.Path.of("out/nrd/runtime/Release/rte_nrd.dll"))) {
                SwingUtilities.invokeAndWait(()->{
                    var denoiser=(javax.swing.JComboBox<?>)find(holder[0],"denoiser");
                    if(denoiser==null) throw new AssertionError("Missing denoiser selector");
                    denoiser.setSelectedIndex(1);
                    if(renderer.settings().denoiser()!=engine.Denoiser.NRD_RELAX) throw new AssertionError("NRD selection failed");
                });
                awaitSamples(renderer,frame->frame.context()!=null && frame.context().settings().denoiser()==engine.Denoiser.NRD_RELAX && frame.samples()>=4);
                if(!renderer.backendStatus().contains("NRD RELAX")) throw new AssertionError("NRD did not run in the game");
                SwingUtilities.invokeAndWait(()->((javax.swing.JCheckBox)find(holder[0],"Path roulette (experimental)")).doClick());
                awaitSamples(renderer,frame->frame.context()!=null && frame.context().settings().pathRoulette() && frame.samples()>=4);
                if(!renderer.backendStatus().contains("path roulette true")) throw new AssertionError("Roulette did not run in the game");
                SwingUtilities.invokeAndWait(()->((javax.swing.JCheckBox)find(holder[0],"Path roulette (experimental)")).doClick());
                awaitSamples(renderer,frame->frame.context()!=null && !frame.context().settings().pathRoulette());
                SwingUtilities.invokeAndWait(()->((javax.swing.JComboBox<?>)find(holder[0],"denoiser")).setSelectedIndex(0));
                awaitSamples(renderer,frame->frame.context()!=null && frame.context().settings().denoiser()==engine.Denoiser.BUILT_IN);
            }
            if(java.nio.file.Files.isRegularFile(java.nio.file.Path.of("out/fsr/runtime/Release/rte_fsr.dll"))) {
                SwingUtilities.invokeAndWait(()->{
                    var upscale=(javax.swing.JComboBox<?>)find(holder[0],"upscaler");
                    if(upscale==null || !upscale.isEnabled()) throw new AssertionError("FSR selector unavailable");
                    upscale.setSelectedIndex(2);
                    var selected=renderer.settings();
                    if(selected.upscaler()!=engine.Upscaler.FSR_PERFORMANCE || selected.lightingScale()!=1
                            || selected.denoiser()!=engine.Denoiser.NRD_RELAX || selected.interactive()
                            || !selected.temporal() || selected.foveated() || selected.fogDownsample()!=1 || navigation.aperture()!=0)
                        throw new AssertionError("FSR prerequisites not configured");
                });
                awaitSamples(renderer,frame->frame.context()!=null && frame.context().settings().upscaler()==engine.Upscaler.FSR_PERFORMANCE && frame.samples()>=4);
                if(renderer.frame().image().getWidth()!=960 || !renderer.backendStatus().contains("FSR_PERFORMANCE"))
                    throw new AssertionError("Game FSR output or diagnostics missing");
                SwingUtilities.invokeAndWait(()->((javax.swing.JComboBox<?>)find(holder[0],"upscaler")).setSelectedIndex(0));
                awaitSamples(renderer,frame->frame.context()!=null && frame.context().settings().upscaler()==engine.Upscaler.OFF);
            }
            SwingUtilities.invokeAndWait(()->((javax.swing.JCheckBox)find(holder[0],"Temporal reconstruction")).doClick());
            awaitSamples(renderer,frame->frame.context()!=null && !frame.context().settings().temporal());
            SwingUtilities.invokeAndWait(()->{
                var graphics=image.createGraphics();
                try {layout(holder[0]);holder[0].paint(graphics);} finally {graphics.dispose();}
            });
            ImageIO.write(image,"png",Path.of("out/gpu/game-panel.png").toFile());
            for(int tabIndex=0;tabIndex<4;tabIndex++) {
                final int selected=tabIndex;
                SwingUtilities.invokeAndWait(()->{
                    var tabs=findTabs(holder[0]);tabs.setSelectedIndex(selected);layout(holder[0]);
                    var draw=image.createGraphics();try {holder[0].paint(draw);} finally {draw.dispose();}
                });
                ImageIO.write(image,"png",Path.of("out/gpu/settings-"+tabIndex+".png").toFile());
            }
            SwingUtilities.invokeAndWait(holder[0]::close);
            System.out.println("PASS offscreen game panel, refinement off/motion/on, fixed-grid control, lighting and focus lifecycle");
            System.out.println(renderer.backendStatus());
            } finally {
                if(holder[0]!=null) SwingUtilities.invokeAndWait(holder[0]::close);
            }
        }
        missingDenoiserRuntime();
    }

    private static void missingDenoiserRuntime() throws Exception {
        var navigation=new GameNavigation();navigation.fixedResolution(96,80);navigation.active(false);
        // The CPU session deliberately reports no NRD runtime while the UI exposes GPU controls.
        var hooks=new GameRenderer.RuntimeHooks(new GameDiagnostics(), failure->{},
                (world,view,settings,backend)->engine.RenderEngine.openSession(world,view,settings,"cpu"));
        try(var renderer=new GameRenderer(navigation,BlockWorld.landscape().snapshot(),"gpu",GameRenderer::copyImage,hooks)) {
            SwingUtilities.invokeAndWait(()->{
                var panel=new GameSettingsPanel(navigation,renderer);panel.refresh();
                var selector=(javax.swing.JComboBox<?>)find(panel,"denoiser");
                if(selector==null || selector.isEnabled()) throw new AssertionError("Unavailable NRD remained selectable");
            });
            var before=renderer.settings();boolean rejected=false;
            try {renderer.configure(s->s.withDenoiser(engine.Denoiser.NRD_RELAX).withTemporal(true,1));}
            catch(IllegalArgumentException expected) {rejected=true;}
            if(!rejected || !renderer.settings().equals(before) || renderer.error()!=null)
                throw new AssertionError("Missing NRD was not rejected atomically before rendering");
            System.out.println("PASS unavailable NRD is disabled and cannot break the running renderer");
        }
    }

    private static javax.swing.JTabbedPane findTabs(java.awt.Container parent) {
        for(var child:parent.getComponents()) {
            if(child instanceof javax.swing.JTabbedPane tabs) return tabs;
            if(child instanceof java.awt.Container container) {
                var found=findTabs(container);if(found!=null) return found;
            }
        }
        return null;
    }

    private static void layout(java.awt.Container parent) {
        parent.doLayout();
        for(var child:parent.getComponents()) if(child instanceof java.awt.Container container) layout(container);
    }

    private static java.awt.Component find(java.awt.Container parent,String name) {
        for(var child:parent.getComponents()) {
            if(name.equals(child.getName())) return child;
            if(child instanceof java.awt.Container container) {
                var found=find(container,name);if(found!=null) return found;
            }
        }
        return null;
    }

    private static void clickRefinement(GamePanel panel) {
        panel.dispatchEvent(new MouseEvent(panel,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,250,175,1,false,MouseEvent.BUTTON1));
    }

    private static void awaitSamples(GameRenderer renderer,java.util.function.Predicate<GameRenderer.Frame> ready) throws Exception {
        long deadline=System.nanoTime()+30_000_000_000L;
        while((renderer.frame()==null || !ready.test(renderer.frame())) && System.nanoTime()<deadline) {
            if(renderer.error()!=null) throw new AssertionError(renderer.error());
            Thread.sleep(5);
        }
        if(renderer.frame()==null || !ready.test(renderer.frame())) throw new AssertionError("Timed out waiting for refinement state");
    }
}
