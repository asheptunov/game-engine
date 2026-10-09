param([string]$OutputDirectory = "out/game")
$ErrorActionPreference = "Stop"
$repository = $PSScriptRoot
. (Join-Path $repository "native-process.ps1")
$jdk = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/javac.exe"))) { Join-Path $env:JAVA_HOME "bin" } else { "C:/Users/andri/.jdks/openjdk-23.0.1/bin" }
$output = Join-Path $repository $OutputDirectory
$classes = Join-Path $output "classes"
& (Join-Path $repository "engine-build.ps1") -OutputDirectory $OutputDirectory
$sources = @(
    (Get-ChildItem (Join-Path $repository "src/platform/awt/input") -Filter *.java).FullName
    (Get-ChildItem (Join-Path $repository "src/game") -Recurse -Filter *.java).FullName
)
$forbidden = @(Get-ChildItem (Join-Path $repository "src/game") -Recurse -Filter *.java | Select-String -Pattern '^import\s+(scenes|editor)(\.|;)')
if ($forbidden.Count) { throw "Game depends on an existing application: $forbidden" }
$compileArguments = @("--enable-preview", "--release", "23", "-cp", $classes, "-sourcepath", (Join-Path $output "empty-sourcepath"), "-d", $classes) + $sources
Invoke-NativeLogged (Join-Path $jdk "javac.exe") $compileArguments (Join-Path $output "game-compile.log") "Independent game compilation failed"
Write-Output "Game build passed: $classes"
