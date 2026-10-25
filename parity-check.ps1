param([string]$SkipGpuReason = '')
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
$output = Join-Path $PSScriptRoot 'out/parity'
New-Item -ItemType Directory -Force $output | Out-Null
$summary = [ordered]@{
    revision = (git rev-parse HEAD)
    startedUtc = [DateTime]::UtcNow.ToString('o')
    sourceSha256 = ''
    repository = 'NOT_RUN'
    game = 'NOT_RUN'
    cpu = 'NOT_RUN'
    gpu = 'NOT_RUN'
    result = 'FAIL'
    reason = ''
    evidence = $output
}
try {
    . (Join-Path $PSScriptRoot 'native-process.ps1')
    $jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:/Users/andri/.jdks/openjdk-23.0.1/bin' }
    $sourceHashes = Get-ChildItem src,tst,tools/gpu,tools/parity,assets/gpu -Recurse -File | Sort-Object FullName | ForEach-Object { (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $summary.sourceSha256 = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($sourceHashes -join "`n")))).Replace('-', '') } finally { $sha.Dispose() }
    $summary.repository = 'RUNNING'
    & ./verify.ps1
    $summary.repository = 'PASS'
    $summary.game = 'RUNNING'
    & ./game-check.ps1
    $summary.game = 'PASS'
    $summary.cpu = 'RUNNING'
    $cpuClasses = 'out/game-check/classes'
    Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$cpuClasses,'game.BackendConformance','cpu',$output) (Join-Path $output 'cpu.log') 'CPU conformance failed'
    & ./game-build.ps1
    & ./fatal-check.ps1 -Backend cpu -NoBuild
    $summary.cpu = 'PASS'
    if ($SkipGpuReason) {
        $summary.gpu = 'SKIP'
        $summary.reason = "Explicit device-test skip: $SkipGpuReason"
    } else {
        $required = @('lwjgl-3.3.6.jar','lwjgl-3.3.6-natives-windows.jar','lwjgl-vulkan-3.3.6.jar','lwjgl-shaderc-3.3.6.jar','lwjgl-shaderc-3.3.6-natives-windows.jar')
        $missing = @($required | Where-Object { -not (Test-Path (Join-Path 'out/gpu/lib' $_)) })
        if ($missing.Count) {
            $summary.gpu = 'SKIP'
            $summary.reason = "GPU dependencies absent: $($missing -join ', '); run ./setup-gpu.ps1"
        } else {
            $summary.gpu = 'RUNNING'
            & ./gpu-build.ps1
            $gpuClasses = "$cpuClasses;out/gpu/classes;out/gpu/lib/*"
            Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$cpuClasses,'-d',$cpuClasses,'tools/parity/ParityProbe.java') (Join-Path $output 'probe-compile.log') 'Probe compilation failed'
            $probe = Join-Path $output 'availability.txt'
            Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-cp',$gpuClasses,'game.ParityProbe',$probe) (Join-Path $output 'probe.log') 'GPU probe process failed'
            $availability = Get-Content $probe
            switch ($availability[0]) {
                'UNAVAILABLE' { $summary.gpu = 'SKIP'; $summary.reason = $availability[1] }
                'AVAILABLE' {
                    Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$gpuClasses,'game.BackendConformance','gpu',$output) (Join-Path $output 'gpu.log') 'GPU conformance failed'
                    & ./gpu-check.ps1 -NoBuild
                    & ./fatal-check.ps1 -Backend gpu -NoBuild
                    $summary.gpu = 'PASS'
                }
                default { throw "GPU probe failed (not a hardware skip): $($availability -join ' ')" }
            }
        }
    }
    $summary.result = if ($summary.gpu -eq 'PASS') { 'PASS' } else { 'PARTIAL' }
} catch {
    foreach ($stage in @('repository', 'game', 'cpu', 'gpu')) { if ($summary[$stage] -eq 'RUNNING') { $summary[$stage] = 'FAIL' } }
    $summary.reason = $_.Exception.Message
    throw
} finally {
    $summary.finishedUtc = [DateTime]::UtcNow.ToString('o')
    $summary | ConvertTo-Json | Set-Content (Join-Path $output 'summary.json')
    Write-Output "Parity $($summary.result): repository=$($summary.repository), game=$($summary.game), CPU=$($summary.cpu), GPU=$($summary.gpu). $($summary.reason)"
    Write-Output "Evidence: $(Join-Path $output 'summary.json')"
    Pop-Location
}
# A partial run is deliberately nonzero: callers must not confuse absent GPU evidence with success.
if ($summary.result -eq 'PARTIAL') { exit 2 }
