param([switch]$Measurements, [string]$OutputDirectory = 'out/fog')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'native-process.ps1')
Push-Location $PSScriptRoot
try {
    & ./game-check.ps1
    & ./gpu-build.ps1
    $jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:/Users/andri/.jdks/openjdk-23.0.1/bin' }
    New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
    $cpuClasses = 'out/game-check/classes'
    $gpuClasses = "$cpuClasses;out/gpu/classes;out/gpu/lib/*"
    Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$cpuClasses,'game.FogNumerics',$OutputDirectory) (Join-Path $OutputDirectory 'numerics.log') 'Fog numerical checks failed'
    foreach ($backend in @('cpu','gpu')) {
        $classPath = if ($backend -eq 'cpu') { $cpuClasses } else { $gpuClasses }
        $arguments = @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'game.FogScenes',$backend,$OutputDirectory)
        if ($Measurements) { $arguments += 'full' }
        Invoke-NativeLogged (Join-Path $jdk 'java.exe') $arguments (Join-Path $OutputDirectory "$backend-scenes.log") 'Fog scene checks failed'
    }
    if ($Measurements) {
        Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$cpuClasses,'-d',$cpuClasses,'tools/parity/FogBenchmark.java') (Join-Path $OutputDirectory 'benchmark-compile.log') 'Fog benchmark compilation failed'
        foreach ($backend in @('cpu','gpu')) {
            $classPath = if ($backend -eq 'cpu') { $cpuClasses } else { $gpuClasses }
            Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'game.FogBenchmark',$backend,(Join-Path $OutputDirectory 'route')) (Join-Path $OutputDirectory "$backend-route.log") 'Fog route failed'
        }
    }
    $hashes = Get-ChildItem src,tst,tools/parity,assets/gpu -Recurse -File | Sort-Object FullName | ForEach-Object { (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $fingerprint = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($hashes -join "`n")))).Replace('-', '') } finally { $sha.Dispose() }
    @{ result='PASS'; baseRevision=(git rev-parse HEAD); sourceSha256=$fingerprint; measurements=[bool]$Measurements; completedUtc=[DateTime]::UtcNow.ToString('o') } | ConvertTo-Json | Set-Content (Join-Path $OutputDirectory 'summary.json')
    Write-Output 'Fog checks passed on CPU and GPU. Native playthrough acceptance remains manual.'
} finally { Pop-Location }
