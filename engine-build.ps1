param(
    [string]$OutputDirectory = "out/engine-build"
)

$ErrorActionPreference = "Stop"
$repository = $PSScriptRoot
$jdk = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/javac.exe"))) {
    Join-Path $env:JAVA_HOME "bin"
} else {
    "C:/Users/andri/.jdks/openjdk-23.0.1/bin"
}
$javac = Join-Path $jdk "javac.exe"
$output = Join-Path $repository $OutputDirectory
$classes = Join-Path $output "classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$engineFiles = @(Get-ChildItem (Join-Path $repository "src/engine") -Recurse -Filter *.java)
$forbidden = @($engineFiles | Select-String -Pattern '^import\s+(scenes|ui|di|rendering|java\.awt)(\.|;)')
if ($forbidden.Count) {
    $forbidden | ForEach-Object { Write-Error "$($_.Path):$($_.LineNumber): forbidden engine dependency $($_.Line.Trim())" }
    throw "Engine dependency boundary failed"
}

$sources = @(
    $engineFiles.FullName
    (Get-ChildItem (Join-Path $repository "src/math") -Recurse -Filter *.java).FullName
    (Get-ChildItem (Join-Path $repository "src/logging") -Recurse -Filter *.java).FullName
    (Join-Path $repository "src/profiling/TraceProfile.java")
    (Join-Path $repository "src/profiling/RuntimeMetrics.java")
)
& $javac --enable-preview --release 23 -d $classes $sources 2>&1 |
    Tee-Object -FilePath (Join-Path $output "engine-compile.log")
if ($LASTEXITCODE -ne 0) { throw "Engine-only compilation failed with exit code $LASTEXITCODE" }

$consumer = Join-Path $repository "src/examples/headless/HeadlessEngineDemo.java"
& $javac --enable-preview --release 23 -cp $classes -d $classes $consumer 2>&1 |
    Tee-Object -FilePath (Join-Path $output "consumer-compile.log")
if ($LASTEXITCODE -ne 0) { throw "Independent consumer compilation failed with exit code $LASTEXITCODE" }
Write-Output "Engine-only build passed: $classes"
