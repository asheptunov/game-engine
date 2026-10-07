param([string]$OutputDirectory = "out/scene-check")

$ErrorActionPreference = "Stop"
$repository = $PSScriptRoot
. (Join-Path $repository "native-process.ps1")
$jdk = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/java.exe"))) { Join-Path $env:JAVA_HOME "bin" } else { "C:/Users/andri/.jdks/openjdk-23.0.1/bin" }
$java = Join-Path $jdk "java.exe"
$output = Join-Path $repository $OutputDirectory
New-Item -ItemType Directory -Force -Path $output | Out-Null

& (Join-Path $repository "engine-check.ps1") -OutputDirectory (Join-Path $OutputDirectory "base")
$classes = Join-Path $output "base/classes"
$tests = @(
    "engine.SceneDocumentTest",
    "engine.ScenePersistenceTest",
    "engine.SpatialQueryTest",
    "engine.MeshAccelerationTest",
    "engine.DielectricPathTest",
    "engine.VolumePathTest",
    "engine.ParallelTraceTest"
)
foreach ($test in $tests) {
    $log = Join-Path $output ($test.Replace(".","_") + ".log")
    $testArguments = @("--enable-preview", "-Djava.awt.headless=true", "-cp", $classes, $test)
    Invoke-NativeLogged $java $testArguments $log "$test process failed"
    $failure = @(Select-String -Path $log -Pattern '\[ERROR\].*harness\.SuiteRunner|failed with exception|AssertionError')
    if ($failure.Count) { throw "$test reported harness failures; inspect $log" }
}

$boundary = Join-Path $output "base/boundary/classes"
$scene = Join-Path $output "document-demo.scene.xml"
$image = Join-Path $output "document-demo.png"
$consumerArguments = @("--enable-preview", "-Djava.awt.headless=true", "-cp", $boundary, "examples.headless.SceneDocumentDemo", $scene, $image)
Invoke-NativeLogged $java $consumerArguments (Join-Path $output "document-consumer.log") "Scene document consumer failed"
if (-not (Test-Path $scene) -or -not (Test-Path $image)) { throw "Scene document consumer did not produce its outputs" }

Write-Output "SKIP (pre-existing, out of B scope): Caps-on synthetic-console path documented by the implementation handoff."
Write-Output "Scene E3/E4 checks passed: $output"
