# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

No Maven/Gradle. From the repo root:

- `./build` — compile `src/` + `tst/` to `out/cli/`. Exits non-zero on failure; full log at `out/cli/build.log`.
- `./test <ClassName>` — run a test suite's `main` (e.g. `./test di.InjectorTest`).
- `./run` — run `Main`.

All three use JDK 23 with `--enable-preview`. Override the JDK with `JAVA_HOME=/path/to/jdk ./build`. The scripts auto-handle WSL→Windows path translation when the JDK is a Windows install. IntelliJ's `out/production/` and `out/test/` are untouched.

- **Source root**: `src/` (default package for `Main.java`; all other packages are under `src/`)
- **Test root**: `tst/`
- **Entry point**: `Main.java` (no package declaration) — opens an 800×800 AWT window at 144hz

**Running tests**: Each test suite has its own `main` method. The custom harness (`tst/harness/`) discovers and runs all `@harness.Test`-annotated methods in the calling class. To run a single test: disable all others via `@Test(enabled = false)`, or add a standalone `@Test`-annotated method and run from that class's `main`.

`Workbench.java` contains one-off data migration tests; its tests are disabled by default.

## Architecture

### Rendering Pipeline

Every frame, a `PeriodicExecutor` fires `CompositeRenderer.render()`, which calls each `Renderer` in sequence:

```
Eraser → Checkerboard → (scene renderer) → AwtViewer
```

- `Raster` is the core pixel buffer interface. `PixelRaster` is the in-memory impl.
- `Painter` draws primitives onto a `Raster`. `Printer` renders text using loaded `Font` bitmaps.
- `AwtViewer` is the final stage — flushes the `Raster`'s RGB data to a Swing `JFrame` using a `BufferStrategy`.
- `BlendMode` controls compositing (OVER_PRE, SUBTRACT, etc.) for all draw operations.

Raster files are serialized as raw pixel bytes. `ArgbSerializer` / `RgbSerializer` read/write `.tx` files. `ChainRasterSerializer` tries each serializer in order on load.

### Scenes

`Scene` is a marker interface. The currently active `Scene` is tracked in `Main.SCENE` via `AtomicReference<Scene>`.

`SceneAwareProxyBuilder` creates a JDK dynamic proxy that delegates `KeyListener`, `MouseListener`, `MouseMotionListener`, `MouseWheelListener`, or `Renderer` calls to whichever scene is currently active — allowing scene switching without re-wiring the AWT event chain.

**TextureEditor** is the only scene currently implemented. It handles:
- Modes: `BRUSH`, `BOX_SELECT`, `PIXEL_SELECT`, `LASSO_SELECT`, `FILL`, `COLOR_PICKER`, `COMMAND_ENTRY`
- Tool card overlay, color picker overlay, console overlay
- Undo/redo via `CircularBufferHistoryImpl` snapshots
- File I/O via `FileSystemRasterRepository` (save/load `.tx` rasters)
- An in-app console (`/` key) with filesystem-style commands: `ls`, `cd`, `mkdir`, `save`, `load`, `rm`, `touch`, `status`, `canvas`, `exit`

### DI Framework (`src/di/`)

A custom Guice-inspired injector. Usage pattern:

```java
Injector injector = Injector.create(new Module() {
    @Override
    public void configure(GraphBuilder b) {
        b.bind(IFoo.class).to(FooImpl.class).singleton();
        b.bind(int.class).named("width").toInstance(800);
    }

    @Provides
    @Singleton
    Bar provideBar(IFoo foo) { return new Bar(foo); }
});
Foo foo = injector.get(IFoo.class);
```

- **Constructor injection**: annotate with `@di.annotations.Inject`, or use the sole default constructor.
- **Qualifiers**: `@Named("name")` or a custom annotation meta-annotated with `@di.annotations.Qualifier`.
- **Scopes**: `.singleton()` / `.prototype()` on the binding builder; `@Singleton` on `@Provides` methods.
- **List/map bindings**: `b.bindList().of(T.class)` and `b.bindMap().from(K.class).to(V.class)` — injected as `List<T>` / `Map<K,V>`.
- **Generic types**: use `new GenericType<List<String>>() {}` as the key where raw `Class` is insufficient.

### Logging

```java
private static final Logger LOG = LogManager.instance().getThis();
```

`getThis()` uses the call stack to infer the enclosing class. `Logger` supports chained calls: `LOG.info("…").info("…")`.

### Custom Test Harness (`tst/harness/`)

- `@harness.Test` — marks a test method (non-private, non-static, no parameters). Set `enabled = false` to skip.
- `SuiteRunner.runThis()` — called from `main`; discovers all enabled `@Test` methods in the calling class via reflection.
- `harness.Assertions` — provides `assertEquals`, `assertSame`, `assertNotSame`, `assertInstanceOf`, `assertNotNull`.
