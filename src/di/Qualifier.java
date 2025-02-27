package di;

public sealed interface Qualifier permits Qualifier.Name, Qualifier.Annotation {
    record Name(String value) implements Qualifier {}

    record Annotation(java.lang.annotation.Annotation value) implements Qualifier {}
}
