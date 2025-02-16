package rendering;

import harness.Test;

import static harness.Assertions.assertEquals;
import static harness.SuiteRunner.runThis;

class ArgbRasterSerializerTest {
    ArgbRasterSerializer cut = ArgbRasterSerializer.INSTANCE;

    @Test
    void testSerializeDeserialize() {
        Raster raster = new PixelRaster(40, 50, Color.NamedColor.WHITE);
        byte[] bytes = cut.serialize(raster).fold(b -> b, e -> {throw e;});
        Raster deserializedRaster = cut.deserialize(bytes).fold(r -> r, e -> {throw e;});
        assertEquals(raster, deserializedRaster);
    }

    public static void main(String[] args) {
        runThis();
    }
}
