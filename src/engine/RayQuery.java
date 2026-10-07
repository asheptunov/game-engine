package engine;

import math.Vec3;
import java.util.Objects;
import java.util.Set;

/** Normalized-world-distance ray query with optional node exclusions. */
public record RayQuery(Vec3 origin, Vec3 direction, float minimumDistance, float maximumDistance,
                       Set<NodeId> excludedNodes) {
    public RayQuery(Vec3 origin,Vec3 direction){this(origin,direction,0,Float.POSITIVE_INFINITY,Set.of());}
    public RayQuery {
        Objects.requireNonNull(origin,"origin");Objects.requireNonNull(direction,"direction");
        excludedNodes=Set.copyOf(Objects.requireNonNull(excludedNodes,"excludedNodes"));
        if(!finite(origin)||!finite(direction)||maxAbs(direction)==0)throw new IllegalArgumentException("Query ray must be finite and nonzero");
        if(!Float.isFinite(minimumDistance)||minimumDistance<0||Float.isNaN(maximumDistance)||maximumDistance<=minimumDistance)
            throw new IllegalArgumentException("Query distance range is invalid");
    }
    private static boolean finite(Vec3 v){return Float.isFinite(v.x())&&Float.isFinite(v.y())&&Float.isFinite(v.z());}
    Vec3 normalizedDirection(){double scale=maxAbs(direction);double x=direction.x()/scale,y=direction.y()/scale,z=direction.z()/scale;double length=Math.sqrt(x*x+y*y+z*z);return new Vec3((float)(x/length),(float)(y/length),(float)(z/length));}
    private static double maxAbs(Vec3 v){return Math.max(Math.abs((double)v.x()),Math.max(Math.abs((double)v.y()),Math.abs((double)v.z())));}
}
