param(
    [string]$OutputDirectory = "out/engine-check"
)

$ErrorActionPreference = "Stop"
$repository = $PSScriptRoot
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
& $javac --enable-preview --release 23 -d $classes $sources 2>&1 |
    Tee-Object -FilePath (Join-Path $output "compile-all.log")
if ($LASTEXITCODE -ne 0) { throw "Full compilation failed with exit code $LASTEXITCODE" }

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
    & $java --enable-preview '-Djava.awt.headless=true' -cp $classes $test 2>&1 | Tee-Object -FilePath $log
    if ($LASTEXITCODE -ne 0) { throw "$test process failed with exit code $LASTEXITCODE" }
    $failure = @(Select-String -Path $log -Pattern '\[ERROR\].*harness\.SuiteRunner|failed with exception|AssertionError')
    if ($failure.Count) { throw "$test reported harness failures; inspect $log" }
}

$image = Join-Path $output "headless-engine.png"
& $java --enable-preview '-Djava.awt.headless=true' -cp (Join-Path $output "boundary/classes") examples.headless.HeadlessEngineDemo $image 2>&1 |
    Tee-Object -FilePath (Join-Path $output "headless-consumer.log")
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $image)) { throw "Headless consumer failed" }
Write-Output "Engine checks passed: $output"
