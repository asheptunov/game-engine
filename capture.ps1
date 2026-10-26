param(
    [Parameter(Mandatory=$true)][string]$Capture,
    [ValidateSet('inspect','replay','render')][string]$Action = 'inspect',
    [ValidateSet('cpu','gpu')][string]$Backend = 'cpu',
    [ValidateSet('current','displayed')][string]$View = 'current',
    [ValidateRange(1,4096)][int]$Samples = 1,
    [string]$Output = 'out/game/capture-render.png',
    [switch]$NoBuild
)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    . ./native-process.ps1
    if (-not $NoBuild) { ./game-build.ps1 }
    $classes = Join-Path $PSScriptRoot 'out/game/classes'
    if ($Action -eq 'render' -and $Backend -eq 'gpu') {
        if (-not $NoBuild) { ./gpu-build.ps1 -NoGameBuild }
        ./setup-gpu.ps1 -Offline -RuntimeOnly
        $classes += ';out/gpu/classes;out/gpu/lib/*'
    }
    $jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:/Users/andri/.jdks/openjdk-23.0.1/bin' }
    $capturePath = (Resolve-Path -LiteralPath $Capture).Path
    Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classes,'game.CaptureReplay',$Action,$capturePath,$Output,$Backend,$View,$Samples) 'out/game/capture-command.log' 'Capture command failed'
} finally { Pop-Location }
