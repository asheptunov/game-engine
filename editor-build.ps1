param([string]$OutputDirectory = "out/editor")
$ErrorActionPreference = "Stop"
$repository = $PSScriptRoot
. (Join-Path $repository "native-process.ps1")
$jdk = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/javac.exe"))) { Join-Path $env:JAVA_HOME "bin" } else { "C:/Users/andri/.jdks/openjdk-23.0.1/bin" }
$javac = Join-Path $jdk "javac.exe"
$output = Join-Path $repository $OutputDirectory
$classes = Join-Path $output "classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& (Join-Path $repository "engine-build.ps1") -OutputDirectory $OutputDirectory
$platformSources = @(Get-ChildItem (Join-Path $repository "src/platform") -Recurse -Filter *.java).FullName
if ($platformSources.Count) {
    $platformArguments = @("--enable-preview", "--release", "23", "-cp", $classes, "-d", $classes) + $platformSources
    Invoke-NativeLogged $javac $platformArguments (Join-Path $output "platform-compile.log") "Platform adapter compilation failed"
}
$editorSources = @(Get-ChildItem (Join-Path $repository "src/editor") -Recurse -Filter *.java).FullName
$compileArguments = @("--enable-preview", "--release", "23", "-cp", $classes, "-d", $classes) + $editorSources
Invoke-NativeLogged $javac $compileArguments (Join-Path $output "editor-compile.log") "Editor compilation failed"
Write-Output "Editor build passed: $classes"
