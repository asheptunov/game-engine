param(
    [string]$OutputDirectory = "out/engine-check"
)

$ErrorActionPreference = "Stop"
$repository = $PSScriptRoot
. (Join-Path $repository "native-process.ps1")
$jdk = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/java.exe"))) {
    Join-Path $env:JAVA_HOME "bin"
} else {
    "C:/Users/andri/.jdks/openjdk-23.0.1/bin"
}
$java = Join-Path $jdk "java.exe"
$javac = Join-Path $jdk "javac.exe"
$output = Join-Path $repository $OutputDirectory
$classes = Join-Path $output "classes"
New-Item -ItemType Directory -Force -Path $classes,(Join-Path $repository "out/cli") | Out-Null

& (Join-Path $repository "engine-build.ps1") -OutputDirectory (Join-Path $OutputDirectory "boundary")

$sources = @(
    (Get-ChildItem (Join-Path $repository "src") -Recurse -Filter *.java).FullName
    (Get-ChildItem (Join-Path $repository "tst") -Recurse -Filter *.java).FullName
)
$compileArguments = @("--enable-preview", "--release", "23", "-d", $classes) + $sources
Invoke-NativeLogged $javac $compileArguments (Join-Path $output "compile-all.log") "Full compilation failed"

$tests = @(
    "engine.EngineSessionTest",
    "engine.MaterialPlaygroundTest",
    "engine.CameraSamplingTest",
    "engine.CameraLifecycleTest",
    "engine.FocusLifecycleTest",
    "engine.ResponsiveTraceTest",
    "engine.TemporalReconstructionTest"
)
foreach ($test in $tests) {
    $log = Join-Path $output ($test.Replace(".","_") + ".log")
    $testArguments = @("--enable-preview", "-Djava.awt.headless=true", "-cp", $classes, $test)
    Invoke-NativeLogged $java $testArguments $log "$test process failed"
    $failure = @(Select-String -Path $log -Pattern '\[ERROR\].*harness\.SuiteRunner|failed with exception|AssertionError')
    if ($failure.Count) { throw "$test reported harness failures; inspect $log" }
}

$image = Join-Path $output "headless-engine.png"
$consumerArguments = @("--enable-preview", "-Djava.awt.headless=true", "-cp", (Join-Path $output "boundary/classes"), "examples.headless.HeadlessEngineDemo", $image)
Invoke-NativeLogged $java $consumerArguments (Join-Path $output "headless-consumer.log") "Headless consumer failed"
if (-not (Test-Path $image)) { throw "Headless consumer failed to produce $image" }
Write-Output "Engine checks passed: $output"
