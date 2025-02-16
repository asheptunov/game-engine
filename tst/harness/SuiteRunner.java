package harness;

import logging.LogManager;
import logging.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class SuiteRunner {
    private static final SuiteRunner INSTANCE  = new SuiteRunner();
    private static final Logger      LOG       = LogManager.instance().getThis();
    private static final Clock       CLOCK     = Clock.systemDefaultZone();
    public static final  String      CHECKMARK = "✅";
    public static final  String      X_MARK    = "❌";

    private sealed interface TestResult permits Success, Failure {}

    private record Success(Duration time) implements TestResult {}

    private record Failure(Duration time, Throwable ex) implements TestResult {}

    private SuiteRunner() {}

    public static void runThis() {
        SuiteRunner.INSTANCE.runAllInCallingClass();
    }

    private void runAllInCallingClass() {
        var suite = inferCallingClass();
        LOG.debug("Inferred test suite: %s", suite);
        runAllIn(suite);
    }

    private Class<?> inferCallingClass() {
        var foundSelf = false;
        for (var frame : Thread.currentThread().getStackTrace()) {
            var klassOpt = getClassFromFrame(frame);
            if (klassOpt.isEmpty()) {
                continue;
            }
            var klass = klassOpt.get();
            if (this.getClass() == klass) {
                foundSelf = true;
                continue;
            }
            if (foundSelf) {
                return klass;
            }
        }
        throw new RuntimeException("Could not determine test suite from stack");
    }

    private void runAllIn(Class<?> suite) {
        var constructor = findConstructor(suite);
        LOG.debug("Test suite %s has default constructor", suite);
        var instance = instantiateSuite(suite, constructor);
        LOG.debug("Instantiated test suite: %s", suite);
        runAllIn(suite, instance);
    }

    private Constructor<?> findConstructor(Class<?> suite) {
        try {
            return suite.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Test suite must have a default constructor: " + suite, e);
        }
    }

    private Object instantiateSuite(Class<?> suite, Constructor<?> constructor) {
        try {
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new RuntimeException("Failed to instantiate suite instance: " + suite, e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("Failed to instantiate suite instance; suite constructor threw: " + suite,
                    e.getCause());
        }
    }

    private void runAllIn(Class<?> suite, Object instance) {
        var tests = findTests(suite);
        LOG.debug("Found %d tests in suite: %s", tests.size(), suite);
        var results = runTests(suite, instance, tests);
        displayResults(suite, results);
    }

    private Collection<Method> findTests(Class<?> suite) {
        var tests = new ArrayList<Method>();
        for (Method method : suite.getDeclaredMethods()) {
            if (null == method.getAnnotation(Test.class)) {
                continue;
            }
            if (0 != method.getParameterCount()) {
                throw new IllegalArgumentException("Test method must have no arguments: " + formatTest(suite, method));
            }
            if (Modifier.isStatic(method.getModifiers())) {
                throw new IllegalArgumentException("Test method must not be static: " + formatTest(suite, method));
            }
            if (Modifier.isPrivate(method.getModifiers())) {
                throw new IllegalArgumentException("Test method must not be private: " + formatTest(suite, method));
            }
            tests.add(method);
        }
        return tests;
    }

    private Map<Method, TestResult> runTests(Class<?> suite, Object instance, Collection<Method> tests) {
        var results = new HashMap<Method, TestResult>();
        for (Method test : tests) {
            var result = runTest(suite, instance, test);
            results.put(test, result);
        }
        return results;
    }

    private void displayResults(Class<?> suite, Map<Method, TestResult> results) {
        LOG.info("======Test Results======");
        LOG.info("%s %s [%s]",
                results.values().stream().anyMatch(r -> r instanceof Failure) ? X_MARK : CHECKMARK,
                formatSuite(suite),
                results.values().stream().map(r -> switch (r) {
                    case Success s -> s.time();
                    case Failure f -> f.time();
                }).reduce(Duration.ZERO, Duration::plus));
        results.forEach((test, result) -> {
            LOG.info("%s %s [%s]",
                    switch (result) {
                        case Success _ -> CHECKMARK;
                        case Failure _ -> X_MARK;
                    },
                    formatTest(suite, test),
                    switch (result) {
                        case Success s -> s.time();
                        case Failure f -> f.time();
                    });
        });
        results.entrySet().stream()
                .filter(r -> r.getValue() instanceof Failure)
                .forEach(r -> {
                    LOG.error(((Failure) r.getValue()).ex(), "%s failed with exception: %s",
                            formatTest(suite, r.getKey()),
                            ((Failure) r.getValue()).ex().getClass());
                });
        LOG.info("========================");
    }

    private TestResult runTest(Class<?> suite, Object instance, Method test) {
        var start = CLOCK.instant();
        try {
            test.setAccessible(true);
            test.invoke(instance);
            return new Success(Duration.between(start, CLOCK.instant()));
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to invoke test method: " + formatTest(suite, test), e);
        } catch (InvocationTargetException e) {
            return new Failure(Duration.between(start, CLOCK.instant()), e.getCause());
        }
    }

    private Optional<Class<?>> getClassFromFrame(StackTraceElement frame) {
        try {
            return Optional.of(Class.forName(frame.getClassName()));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    private String formatSuite(Class<?> suite) {
        return "Suite " + suite.getCanonicalName();
    }

    private String formatTest(Class<?> suite, Method test) {
        return "Test %s#%s".formatted(suite.getCanonicalName(), test.getName());
    }
}
