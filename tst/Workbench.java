import harness.Test;
import rendering.ArgbSerializer;
import rendering.Color;
import rendering.Filter;
import rendering.RgbSerializer;

import static harness.SuiteRunner.runThis;

@Test(enabled = false)
void rgbToArgb() throws IOException {
    File dir = Path.of("assets/fonts/test/standard").toFile();
    File[] files = Objects.requireNonNull(dir.listFiles(f -> f.getName().endsWith(".tx")));
    for (File file : files) {
        var rgbBytes = Files.readAllBytes(file.toPath());
        var raster = RgbSerializer.INSTANCE.deserialize(rgbBytes).fold(r -> r, e -> {throw e;});
        var argbBytes = ArgbSerializer.INSTANCE.serialize(raster).fold(r -> r, e -> {throw e;});
        Files.write(file.toPath(), argbBytes);
    }
}

@Test(enabled = false)
void whiteOnBlackToBlackOnAlpha() throws IOException {
    File dir = Path.of("assets/fonts/test/standard").toFile();
    File[] files = Objects.requireNonNull(dir.listFiles(f -> f.getName().endsWith(".tx")));
    for (File file : files) {
        var bytes = Files.readAllBytes(file.toPath());
        var raster = ArgbSerializer.INSTANCE.deserialize(bytes).fold(r -> r, e -> {throw e;});
        raster = Filter.chromaKey(Color.NamedColor.BLACK).apply(raster);
        raster = Filter.chromaMap(Color.NamedColor.WHITE, Color.NamedColor.BLACK).apply(raster);
        bytes = ArgbSerializer.INSTANCE.serialize(raster).fold(r -> r, e -> {throw e;});
        Files.write(file.toPath(), bytes);
    }
}

public static void main(String[] args) {
    runThis();
}
