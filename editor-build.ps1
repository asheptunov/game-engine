param([string]$OutputDirectory = "out/editor")
$ErrorActionPreference = "Stop"
$repository = $PSScriptRoot
$jdk = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/javac.exe"))) { Join-Path $env:JAVA_HOME "bin" } else { "C:/Users/andri/.jdks/openjdk-23.0.1/bin" }
$javac = Join-Path $jdk "javac.exe"
$output = Join-Path $repository $OutputDirectory
$classes = Join-Path $output "classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& (Join-Path $repository "engine-build.ps1") -OutputDirectory $OutputDirectory
$editorSources = @(Get-ChildItem (Join-Path $repository "src/editor") -Recurse -Filter *.java).FullName
& $javac --enable-preview --release 23 -cp $classes -d $classes $editorSources 2>&1 | Tee-Object -FilePath (Join-Path $output "editor-compile.log")
if ($LASTEXITCODE -ne 0) { throw "Editor compilation failed with exit code $LASTEXITCODE" }
Write-Output "Editor build passed: $classes"
