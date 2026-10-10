param([switch]$NoBuild, [ValidateSet('landscape', 'gallery')][string]$Scene = 'landscape')
$ErrorActionPreference = "Stop"
$repository = $PSScriptRoot
$output = Join-Path $repository "out/game"
$classes = Join-Path $output "classes"
if (-not $NoBuild -or -not (Test-Path (Join-Path $classes "game/SampleGameMain.class"))) { & (Join-Path $repository "game-build.ps1") }
$jdk = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/javaw.exe"))) { Join-Path $env:JAVA_HOME "bin" } else { "C:/Users/andri/.jdks/openjdk-23.0.1/bin" }
$stderr = Join-Path $output "game-error.log"
$process = Start-Process -FilePath (Join-Path $jdk "javaw.exe") -WindowStyle Hidden -ArgumentList @("--enable-preview", "-cp", ('"' + $classes + '"'), "game.SampleGameMain", $Scene.ToLowerInvariant()) -WorkingDirectory $repository -RedirectStandardOutput (Join-Path $output "game-stdout.log") -RedirectStandardError $stderr -PassThru
Start-Sleep -Milliseconds 750
if ($process.HasExited) { throw "Sample game exited during startup; inspect $stderr" }
Write-Output "Sample game started (PID $($process.Id)). Errors: $stderr"
