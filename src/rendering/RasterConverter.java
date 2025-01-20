package rendering;

public interface RasterConverter {
    Raster forward(Raster raster);

    Raster backward(Raster raster);

    default RasterConverter reversed() {
        return new ReversedConverter(this);
    }

    class ReversedConverter implements RasterConverter {
        private final RasterConverter delegate;

        public ReversedConverter(RasterConverter delegate) {
            this.delegate = delegate;
        }

        @Override
        public Raster forward(Raster raster) {
            return delegate.backward(raster);
        }

        @Override
        public Raster backward(Raster raster) {
            return delegate.forward(raster);
        }
    }
}
