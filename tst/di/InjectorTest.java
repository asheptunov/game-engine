package di;

import di.annotations.Inject;
import harness.SuiteRunner;
import harness.Test;

import java.util.concurrent.atomic.AtomicReference;

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
    void supplier() {
        var lastD = new AtomicReference<D>();
        var injector = Injector.create(b -> b.link(D.class).to(() -> {
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
        var injector = Injector.create(b -> b.link(D.class).to(d));
        assertSame(d, assertInstanceOf(D.class, injector.get(D.class)));
        // proto scope
        assertSame(d, assertInstanceOf(D.class, injector.get(D.class)));
    }

    @Test
    void supplierMethod() {
        // TODO
    }

    @Test
    void supplierClass() {
        // TODO
    }

    @Test
    void interfaceLink() {
        var injector = Injector.create(b -> b.link(IB.class).to(B.class));
        var b1 = assertInstanceOf(B.class, injector.get(IB.class));
        // proto scope
        var b2 = assertInstanceOf(B.class, injector.get(IB.class));
        assertNotSame(b1, b2);
    }

    @Test
    void moduleInstall() {
        var injector = Injector.create(b -> b.install(bb -> bb.link(IB.class).to(B.class)));
        var b1 = assertInstanceOf(B.class, injector.get(IB.class));
        // proto scope
        var b2 = assertInstanceOf(B.class, injector.get(IB.class));
        assertNotSame(b1, b2);
    }

    @Test
    void singleton() {
        var injector = Injector.create(b -> b.link(IB.class).to(B.class).singleton());
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
        var injector = Injector.create(b -> b.link(IB.class).to(B.class).prototype());
        var ib1 = assertInstanceOf(B.class, injector.get(IB.class));
        var ib2 = assertInstanceOf(B.class, injector.get(IB.class));
        assertNotSame(ib1, ib2);
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
