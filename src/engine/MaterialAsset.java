package engine;

import java.util.Objects;

/** Shared immutable material asset. Revisions are runtime publication epochs. */
public record MaterialAsset(MaterialId id, String label, long revision, Material material) {
    public MaterialAsset {
        Objects.requireNonNull(id,"id");Objects.requireNonNull(material,"material");
        label=Labels.checked(label);
        if(revision<0)throw new IllegalArgumentException("Asset revision must be nonnegative");
        material=new Material(id.toString(),material.color(),material.kind(),material.ior(),material.absorption(),
                material.roughness(),material.emission(),material.scattering(),material.anisotropy());
    }
    MaterialAsset withRevision(long value){return new MaterialAsset(id,label,value,material);}
}
