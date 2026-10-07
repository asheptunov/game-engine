package engine;

import java.util.Objects;

/** Shared immutable geometry asset. Revisions are runtime publication epochs. */
public record GeometryAsset(GeometryId id, String label, long revision, GeometryData geometry) {
    public GeometryAsset {
        Objects.requireNonNull(id,"id");Objects.requireNonNull(geometry,"geometry");
        label=Labels.checked(label);
        if(revision<0)throw new IllegalArgumentException("Asset revision must be nonnegative");
    }
    GeometryAsset withRevision(long value){return new GeometryAsset(id,label,value,geometry);}
}
