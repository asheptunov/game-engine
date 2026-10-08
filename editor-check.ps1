param([string]$OutputDirectory = "out/editor-check")
$ErrorActionPreference = "Stop"
$repository = $PSScriptRoot
. (Join-Path $repository "native-process.ps1")
$jdk = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/java.exe"))) { Join-Path $env:JAVA_HOME "bin" } else { "C:/Users/andri/.jdks/openjdk-23.0.1/bin" }
$java = Join-Path $jdk "java.exe"
$javac = Join-Path $jdk "javac.exe"
$output = Join-Path $repository $OutputDirectory
$classes = Join-Path $output "classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& (Join-Path $repository "engine-build.ps1") -OutputDirectory (Join-Path $OutputDirectory "boundary")
$sources = @(
    (Get-ChildItem (Join-Path $repository "src") -Recurse -Filter *.java).FullName
    (Get-ChildItem (Join-Path $repository "tst") -Recurse -Filter *.java).FullName
)
$compileArguments = @("--enable-preview", "--release", "23", "-d", $classes) + $sources
Invoke-NativeLogged $javac $compileArguments (Join-Path $output "compile-all.log") "Editor/test compilation failed"
$tests = @("editor.EditorControllerTest", "editor.SceneEditorPreviewTest", "editor.EditorBindingPreferencesTest", "editor.GizmoDragTest", "editor.OverlayGeometryTest", "editor.overlay.OverlayGeometryBoundsTest",
    "engine.CameraProjectorTest", "engine.EditableMeshTest", "engine.GeometryRepresentationTest", "engine.SceneDocumentTest", "engine.ScenePersistenceTest", "engine.SpatialQueryTest", "engine.EngineSessionTest",
    "engine.MaterialPlaygroundTest", "engine.DielectricPathTest", "engine.RoughLightingTest", "engine.MeshAccelerationTest", "engine.VolumePathTest")
foreach ($test in $tests) {
    $log = Join-Path $output ($test.Replace(".", "_") + ".log")
    $arguments = @("--enable-preview", "-Djava.awt.headless=true")
    if ($test -eq "editor.SceneEditorPreviewTest") { $arguments += "-Deditor.preview=$(Join-Path $output 'scene-editor-preview.png')" }
    if ($test -eq "editor.EditorBindingPreferencesTest") { $arguments += "-Deditor.bindings.preview=$(Join-Path $output 'scene-editor-bindings-preview.png')" }
    $arguments += @("-cp", $classes, $test)
    Invoke-NativeLogged $java $arguments $log "$test process failed"
    $failure = @(Select-String -Path $log -Pattern '\[ERROR\].*harness\.SuiteRunner|failed with exception|AssertionError')
    if ($failure.Count) { throw "$test reported harness failures; inspect $log" }
}
$preview = Join-Path $output "scene-editor-preview.png"
if (-not (Test-Path $preview) -or (Get-Item $preview).Length -lt 20000) { throw "Window-free editor preview was not produced" }
$meshPreview = Join-Path $output "scene-editor-mesh-extruded-preview.png"
if (-not (Test-Path $meshPreview) -or (Get-Item $meshPreview).Length -lt 20000) { throw "Window-free mesh-editing preview was not produced" }
$elementPreview = Join-Path $output "scene-editor-elements-preview.png"
if (-not (Test-Path $elementPreview) -or (Get-Item $elementPreview).Length -lt 20000) { throw "Window-free element-editing preview was not produced" }
$analyticPreview = Join-Path $output "scene-editor-analytic-preview.png"
if (-not (Test-Path $analyticPreview) -or (Get-Item $analyticPreview).Length -lt 20000) { throw "Window-free analytic-sphere preview was not produced" }
$approximationPreview = Join-Path $output "scene-editor-approximation-preview.png"
if (-not (Test-Path $approximationPreview) -or (Get-Item $approximationPreview).Length -lt 20000) {
    throw "Window-free sphere-approximation preview was not produced"
}
$bindingsPreview = Join-Path $output "scene-editor-bindings-preview.png"
if (-not (Test-Path $bindingsPreview) -or (Get-Item $bindingsPreview).Length -lt 10000) { throw "Window-free bindings preview was not produced" }
Write-Output "Headless editor checks passed: $output"
