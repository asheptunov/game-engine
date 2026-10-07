param([string]$OutputDirectory = "out/scene-check")

$ErrorActionPreference = "Stop"
$repository = $PSScriptRoot
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
    & $java --enable-preview '-Djava.awt.headless=true' -cp $classes $test 2>&1 | Tee-Object -FilePath $log
    if ($LASTEXITCODE -ne 0) { throw "$test process failed with exit code $LASTEXITCODE" }
    $failure = @(Select-String -Path $log -Pattern '\[ERROR\].*harness\.SuiteRunner|failed with exception|AssertionError')
    if ($failure.Count) { throw "$test reported harness failures; inspect $log" }
}

$boundary = Join-Path $output "base/boundary/classes"
$scene = Join-Path $output "document-demo.scene.xml"
$image = Join-Path $output "document-demo.png"
& $java --enable-preview '-Djava.awt.headless=true' -cp $boundary examples.headless.SceneDocumentDemo $scene $image 2>&1 |
    Tee-Object -FilePath (Join-Path $output "document-consumer.log")
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $scene) -or -not (Test-Path $image)) { throw "Scene document consumer failed" }

Write-Output "SKIP (pre-existing, out of B scope): Caps-on synthetic-console path documented by the implementation handoff."
Write-Output "Scene E3/E4 checks passed: $output"
