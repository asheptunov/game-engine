param([switch]$NoBuild, [ValidateSet('world', 'landscape', 'gallery')][string]$Scene = 'world',
    [ValidateSet('cpu','gpu')][string]$Backend = 'cpu',
    [ValidateRange(0,1600)][int]$Width = 0, [ValidateRange(0,1600)][int]$Height = 0, [string]$Capture = "")
$ErrorActionPreference = "Stop"
$repository = $PSScriptRoot
$output = Join-Path $repository "out/game"
$classes = Join-Path $output "classes"
if (-not $NoBuild -or -not (Test-Path (Join-Path $classes "game/SampleGameMain.class"))) { & (Join-Path $repository "game-build.ps1") }
$jdk = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/javaw.exe"))) { Join-Path $env:JAVA_HOME "bin" } else { "C:/Users/andri/.jdks/openjdk-23.0.1/bin" }
if (($Width -eq 0) -ne ($Height -eq 0) -or ($Width -ne 0 -and ($Width -lt 64 -or $Height -lt 64))) { throw 'Supply both Width and Height, each 64..1600, or omit both' }
$classPath = $classes
if ($Backend -eq 'gpu') {
    if (-not $NoBuild) { & (Join-Path $repository 'gpu-build.ps1') -NoGameBuild }
    & (Join-Path $repository 'setup-gpu.ps1') -Offline -RuntimeOnly
    $classPath += ';' + (Join-Path $repository 'out/gpu/classes') + ';' + (Join-Path $repository 'out/gpu/lib/*')
}
$crashes = Join-Path $output 'crashes'
New-Item -ItemType Directory -Force $crashes | Out-Null
$jvmError = Join-Path $crashes 'hs_err_pid%p.log'
$stderr = Join-Path $output "game-error.log"
$captureArgument = if ($Capture) { @('"' + (Resolve-Path -LiteralPath $Capture).Path + '"') } else { @() }
$process = Start-Process -FilePath (Join-Path $jdk "javaw.exe") -WindowStyle Hidden -ArgumentList (@("--enable-preview", ('"-XX:ErrorFile=' + $jvmError + '"'), ('"-Dgame.jvmErrorFile=' + $jvmError + '"'), "-cp", ('"' + $classPath + '"'), "game.SampleGameMain", $Scene.ToLowerInvariant(), $Backend.ToLowerInvariant(), $Width, $Height) + $captureArgument) -WorkingDirectory $repository -RedirectStandardOutput (Join-Path $output "game-stdout.log") -RedirectStandardError $stderr -PassThru
Start-Sleep -Milliseconds 750
if ($process.HasExited) { throw "Sample game exited during startup; inspect $stderr" }
Write-Output "Sample game started (PID $($process.Id)). Errors: $stderr"
