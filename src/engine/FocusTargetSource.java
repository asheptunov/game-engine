package engine;

/** Immutable selection intent; resolvers can support future world/subject/timeline sources. */
public interface FocusTargetSource {
    record Screen(float u,float v) implements FocusTargetSource {
        public Screen {
            if(!Float.isFinite(u)||!Float.isFinite(v)||u<0||u>1||v<0||v>1)
                throw new IllegalArgumentException("Screen focus coordinates must be finite 0..1 (v=0 bottom)");
        }
        @Override public String toString() {return "screen("+u+","+v+")";}
    }
    Screen CENTER=new Screen(.5f,.5f);
}
