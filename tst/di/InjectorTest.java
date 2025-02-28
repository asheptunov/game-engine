package di;

import di.annotations.Inject;
import di.annotations.Named;
import di.annotations.Provides;
import di.annotations.Qualifier;
import harness.SuiteRunner;
import harness.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertInstanceOf;
import static harness.Assertions.assertNotNull;
import static harness.Assertions.assertNotSame;
import static harness.Assertions.assertSame;

class InjectorTest {
    static class A {
        private final IB b;
        private final C  c;

        @Inject
        A(IB b, C c) {
            this.b = b;
            this.c = c;
        }
    }

    interface IB {}

    static class B implements IB {
        private final D d;

        @Inject
        B(D d) {
            this.d = d;
        }
    }

    static class C {
        private final D d;

        @Inject
        C(D d) {
            this.d = d;
        }
    }

    static class D {}

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.PARAMETER})
    @interface MyQualifier {
        @SuppressWarnings("ClassExplicitlyAnnotation")
        class MyQualifierImpl implements MyQualifier {
            static MyQualifier.MyQualifierImpl instance() {
                return new MyQualifierImpl();
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return MyQualifier.class;
            }

            @Override
            public boolean equals(Object obj) {
                return obj instanceof MyQualifier;
            }

            @Override
            public int hashCode() {
                // other qualifier annotations should implement this using the documentation in java.lang.Annotation's
                // hashCode method so that manually created instances are comparable to natively created ones.
                return 0;
            }
        }
    }

    @Test
    void injectCtor() {
        var injector = Injector.create();
        var c1 = assertInstanceOf(C.class, injector.get(C.class));
        // proto scope
        var c2 = assertInstanceOf(C.class, injector.get(C.class));
        assertNotSame(c1, c2);
    }

    @Test
    void defaultCtor() {
        var injector = Injector.create();
        var d1 = assertInstanceOf(D.class, injector.get(D.class));
        // proto scope
        var d2 = assertInstanceOf(D.class, injector.get(D.class));
        assertNotSame(d1, d2);
    }

    @Test
    void provider() {
        var lastD = new AtomicReference<D>();
        var injector = Injector.create(b -> b.bind(D.class).toProvider(() -> {
            lastD.set(new D());
            return lastD.get();
        }));
        var d1 = assertInstanceOf(D.class, injector.get(D.class));
        assertNotNull(lastD.get());
        assertSame(lastD.get(), d1);
        // proto scope
        var d2 = assertInstanceOf(D.class, injector.get(D.class));
        assertSame(lastD.get(), d2);
        assertNotSame(d1, d2);
    }

    @Test
    void instance() {
        var d = new D();
        var injector = Injector.create(b -> b.bind(D.class).toInstance(d));
        assertSame(d, assertInstanceOf(D.class, injector.get(D.class)));
        // proto scope
        assertSame(d, assertInstanceOf(D.class, injector.get(D.class)));
    }

    @Test
    void providerMethod() {
        var injector = Injector.create(new Module() {
            @Override
            public void configure(GraphBuilder graphBuilder) {}

            @Provides
            String nonGeneric() {
                return "str";
            }

            @Provides
            List<String> genericParameterized() {
                return List.of("1", "2", "3");
            }

            @Provides
            @SuppressWarnings("rawtypes")
            List genericParameterless() {
                return List.of("a", "b", "c");
            }

            @Provides
            @Named("myName")
            String named() {
                return "namedStr";
            }

            @Provides
            @MyQualifier
            String qualified() {
                return "qualifiedStr";
            }

            @Provides
            @SuppressWarnings("rawtypes")
            byte[] dependent(String nonGeneric,
                             List<String> genericParameterized,
                             List genericParameterless,
                             @Named("myName") String named,
                             @MyQualifier String qualified) {
                assertEquals("str", nonGeneric);
                assertEquals(List.of("1", "2", "3"), genericParameterized);
                assertEquals(List.of("a", "b", "c"), genericParameterless);
                assertEquals("namedStr", named);
                assertEquals("qualifiedStr", qualified);
                return "dependentStr".getBytes(StandardCharsets.UTF_8);
            }
        });
        assertEquals("str",
                injector.get(String.class));
        assertEquals(List.of("1", "2", "3"),
                injector.get(new GenericType<List<String>>() {}));
        assertEquals(List.of("a", "b", "c"),
                injector.get(List.class));
        assertEquals("namedStr",
                injector.get(Key.get(String.class).named("myName")));
        assertEquals("qualifiedStr",
                injector.get(Key.get(String.class).annotated(MyQualifier.MyQualifierImpl.instance())));
        assertEquals("dependentStr".getBytes(StandardCharsets.UTF_8), injector.get(byte[].class));
    }

    @Test
    void providerClass() {
        var injector = Injector.create(new Module() {
            @Override
            public void configure(GraphBuilder graphBuilder) {
                graphBuilder.bind(String.class).toProvider(MyStringProvider.class);
                graphBuilder.bind(String.class).named("dependent").toProvider(DependentProvider.class);
            }

            static class MyStringProvider implements Provider<String> {
                @Override
                public String get() {
                    return "myString";
                }
            }

            static class DependentProvider implements Provider<String> {
                @Inject
                DependentProvider(String myString) {
                    assertEquals("myString", myString);
                }

                @Override
                public String get() {
                    return "dependentString";
                }
            }
        });
        assertEquals("myString", injector.get(String.class));
        assertEquals("dependentString", injector.get(Key.get(String.class).named("dependent")));
    }

    @Test
    void interfaceBinding() {
        var injector = Injector.create(b -> b.bind(IB.class).to(B.class));
        var b1 = assertInstanceOf(B.class, injector.get(IB.class));
        // proto scope
        var b2 = assertInstanceOf(B.class, injector.get(IB.class));
        assertNotSame(b1, b2);
    }

    @Test
    void moduleInstall() {
        var injector = Injector.create(b -> b.install(bb -> bb.bind(IB.class).to(B.class)));
        var b1 = assertInstanceOf(B.class, injector.get(IB.class));
        // proto scope
        var b2 = assertInstanceOf(B.class, injector.get(IB.class));
        assertNotSame(b1, b2);
    }

    @Test
    void singleton() {
        var injector = Injector.create(b -> b.bind(IB.class).to(B.class).singleton());
        // IB is singleton
        var ib1 = assertInstanceOf(B.class, injector.get(IB.class));
        var ib2 = assertInstanceOf(B.class, injector.get(IB.class));
        assertSame(ib1, ib2);
        // B is proto
        var b1 = assertInstanceOf(B.class, injector.get(B.class));
        var b2 = assertInstanceOf(B.class, injector.get(B.class));
        assertNotSame(b1, b2);
    }

    @Test
    void prototype() {
        var injector = Injector.create(b -> b.bind(IB.class).to(B.class).prototype());
        var ib1 = assertInstanceOf(B.class, injector.get(IB.class));
        var ib2 = assertInstanceOf(B.class, injector.get(IB.class));
        assertNotSame(ib1, ib2);
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
