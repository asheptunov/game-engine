package gpu;

import static org.lwjgl.vulkan.VK10.*;
import engine.*;
import java.util.Arrays;

/** Actual-device display equivalence, bounded storage, and warmed filter timings. */
public final class BloomCheck {
    public static void main(String[] args) throws Exception {
        try (var gpu = new VulkanDevice(); var display = new GpuDisplay(gpu);
                var compositor = new GpuCompositor(gpu)) {
            for (int[] size : new int[][] {{67,65},{480,300},{1920,1080},{3840,2160}}) {
                int w=size[0], h=size[1], count=w*h;
                var raw=new float[count*3];
                for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
                    float value=Math.abs(x-w/2)<w/60 && Math.abs(y-h/2)<h/60 ? 100 : .08f;
                    for(int c=0;c<3;c++) raw[(y*w+x)*3+c]=value*(1-c*.15f);
                }
                try(var source=gpu.buffer((long)count*16,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT,false);
                    var upload=gpu.buffer((long)count*16,VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT,true)) {
                    for(int i=0;i<count;i++) for(int c=0;c<3;c++) upload.mapped.putFloat(i*16+c*4,raw[i*3+c]);
                    copy(gpu,upload,source);
                    display.prepare(count);
                    var actual=new int[count];
                    var expected=new int[count];
                    var filter=new Bloom.Filter();
                    for(var mode:Bloom.values()) {
                        filter.copy(raw,w,h,expected,1,mode);
                        gpu.begin(); display.encode(source,w,h,1,mode);
                        gpu.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT);
                        display.transfer();
                        gpu.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_HOST_BIT,VK_ACCESS_HOST_READ_BIT);
                        gpu.submit(); display.read(actual);
                        compare(expected,actual,"packed "+mode+" "+w+"x"+h);
                        gpu.begin(); compositor.encode(source,w,h,1,w,h,new int[]{0,w,h,0},mode);
                        gpu.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT);
                        compositor.transfer();
                        gpu.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_HOST_BIT,VK_ACCESS_HOST_READ_BIT);
                        gpu.submit(); compositor.read(actual);
                        compare(expected,actual,"native compositor "+mode+" "+w+"x"+h);
                        long[] times=new long[100];
                        for(int iteration=-30;iteration<times.length;iteration++) {
                            gpu.begin();
                            vkCmdResetQueryPool(gpu.command,gpu.queries,0,6);
                            vkCmdWriteTimestamp(gpu.command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,gpu.queries,0);
                            display.encode(source,w,h,1,mode);
                            vkCmdWriteTimestamp(gpu.command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,gpu.queries,1);
                            gpu.submit();
                            if(iteration>=0) times[iteration]=elapsed(gpu);
                        }
                        Arrays.sort(times);
                        System.out.printf("COST %dx%d %s median %.3fms p95 %.3fms%n",w,h,mode,times[50]/1e6,times[95]/1e6);
                        if(w==480) {
                            var image=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_RGB);
                            image.setRGB(0,0,w,h,expected,0,w);
                            javax.imageio.ImageIO.write(image,"png",new java.io.File("out/gpu/bloom-"+mode+".png"));
                        }
                    }
                    copy(gpu,source,upload);
                    for(int i=0;i<count;i++) for(int c=0;c<3;c++) {
                        if(upload.mapped.getFloat(i*16+c*4)!=raw[i*3+c]) throw new AssertionError("Raw input modified");
                    }
                }
            }
        }
    }
    private static void copy(VulkanDevice gpu,VulkanDevice.Buffer from,VulkanDevice.Buffer to) {
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
            gpu.begin();
            gpu.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT);
            vkCmdCopyBuffer(gpu.command,from.handle,to.handle,org.lwjgl.vulkan.VkBufferCopy.calloc(1,stack).size(from.size));
            gpu.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_HOST_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_HOST_READ_BIT);
            gpu.submit();
        }
    }
    private static long elapsed(VulkanDevice gpu) {
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
            var values=stack.mallocLong(2);
            VulkanDevice.check(vkGetQueryPoolResults(gpu.device,gpu.queries,0,2,values,8,VK_QUERY_RESULT_64_BIT|VK_QUERY_RESULT_WAIT_BIT),"bloom timestamps");
            return (long)((values.get(1)-values.get(0))*gpu.timestampPeriod);
        }
    }
    private static void compare(int[] expected,int[] actual,String label) {
        int max=0;
        for(int i=0;i<expected.length;i++) for(int shift:new int[]{0,8,16}) {
            max=Math.max(max,Math.abs(((expected[i]>>shift)&255)-((actual[i]>>shift)&255)));
        }
        if(max>1) throw new AssertionError(label+" byte error "+max);
        System.out.println("PASS "+label+" maximum byte error "+max);
    }
}
