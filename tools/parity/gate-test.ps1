$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$fixture = Join-Path $repository 'out/parity-gate-test'
New-Item -ItemType Directory -Force $fixture | Out-Null
Copy-Item (Join-Path $repository 'parity-check.ps1') (Join-Path $fixture 'parity-check.ps1') -Force
foreach ($directory in @('src','tst','tools/gpu','tools/parity','assets/gpu','out/gpu/lib')) {
    New-Item -ItemType Directory -Force (Join-Path $fixture $directory) | Out-Null
}
Set-Content (Join-Path $fixture 'src/fixture.txt') 'Fixture only; no renderer is executed.'
foreach ($name in @('lwjgl-3.3.6.jar','lwjgl-3.3.6-natives-windows.jar','lwjgl-vulkan-3.3.6.jar','lwjgl-shaderc-3.3.6.jar','lwjgl-shaderc-3.3.6-natives-windows.jar')) {
    Set-Content (Join-Path $fixture "out/gpu/lib/$name") 'placeholder for gate classification test'
}
foreach ($script in @('verify.ps1','game-check.ps1','gpu-build.ps1','gpu-check.ps1')) {
    Set-Content (Join-Path $fixture $script) 'param([switch]$NoBuild); Write-Output "fixture stage completed"'
}
@'
function Invoke-NativeLogged {
    param($FilePath, $Arguments, $LogPath, $FailureMessage)
    Set-Content $LogPath 'fixture stage'
    if ($Arguments -contains 'game.ParityProbe') {
        $state = switch ($env:PARITY_GATE_TEST) { 'unavailable' {'UNAVAILABLE'} 'probe-error' {'ERROR'} default {'AVAILABLE'} }
        Set-Content $Arguments[-1] "$state`nfixture probe detail"
    }
    if ($Arguments -contains 'game.BackendConformance' -and $Arguments -contains 'gpu' -and $env:PARITY_GATE_TEST -eq 'mismatch') {
        throw 'Injected GPU comparison mismatch'
    }
}
'@ | Set-Content (Join-Path $fixture 'native-process.ps1')
$oldMode = $env:PARITY_GATE_TEST
try {
    foreach ($mode in @('pass','explicit-skip','unavailable','probe-error','mismatch')) {
        $env:PARITY_GATE_TEST = $mode
        $info = [Diagnostics.ProcessStartInfo]::new()
        $info.FileName = (Get-Process -Id $PID).Path
        $info.Arguments = '-NoProfile -File "' + (Join-Path $fixture 'parity-check.ps1') + '"'
        if ($mode -eq 'explicit-skip') { $info.Arguments += ' -SkipGpuReason "fixture skip"' }
        $info.WorkingDirectory = $fixture
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $process = [Diagnostics.Process]::Start($info)
        try {
            $stdout = $process.StandardOutput.ReadToEndAsync()
            $stderr = $process.StandardError.ReadToEndAsync()
            $process.WaitForExit()
            Set-Content (Join-Path $fixture "$mode.log") ($stdout.Result + $stderr.Result)
            $summary = Get-Content (Join-Path $fixture 'out/parity/summary.json') -Raw | ConvertFrom-Json
            $expected = switch ($mode) { 'pass' {'PASS'} 'explicit-skip' {'PARTIAL'} 'unavailable' {'PARTIAL'} default {'FAIL'} }
            if ($summary.result -ne $expected) { throw "$mode expected $expected, got $($summary.result)" }
            if ($expected -eq 'PASS' -and $process.ExitCode -ne 0) { throw 'PASS must exit zero' }
            if ($expected -eq 'PARTIAL' -and ($process.ExitCode -ne 2 -or $summary.gpu -ne 'SKIP')) { throw 'Partial must report GPU SKIP and exit 2' }
            if ($expected -eq 'FAIL' -and ($process.ExitCode -eq 0 -or $summary.gpu -ne 'FAIL')) { throw 'Device failures must fail, never skip' }
            Write-Output "PASS gate classification: $mode"
        } finally { $process.Dispose() }
    }
} finally { $env:PARITY_GATE_TEST = $oldMode }
