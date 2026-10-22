package gpu;

import static org.lwjgl.util.shaderc.Shaderc.*;
import java.nio.file.*;

/** Build-only shader compiler. Runtime loads the resulting SPIR-V without shaderc. */
public final class CompileShader {
    private CompileShader() {}
    public static void main(String[] args) throws Exception {
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        if (compiler == 0 || options == 0) throw new IllegalStateException("Shader compiler unavailable");
        try {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
            long result = shaderc_compile_into_spv(compiler, Files.readString(Path.of(args[0])), shaderc_compute_shader, args[0], "main", options);
            try {
                if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                    throw new IllegalArgumentException(shaderc_result_get_error_message(result));
                var bytes = shaderc_result_get_bytes(result);
                var output = new byte[bytes.remaining()];
                bytes.get(output);
                Files.write(Path.of(args[1]), output);
            } finally { shaderc_result_release(result); }
        } finally { shaderc_compile_options_release(options); shaderc_compiler_release(compiler); }
    }
}
