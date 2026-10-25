param([ValidateSet('cpu','gpu')][string]$Backend = 'cpu', [switch]$NoBuild)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    . ./native-process.ps1
    $jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:/Users/andri/.jdks/openjdk-23.0.1/bin' }
    if (-not $NoBuild) {
        if ($Backend -eq 'gpu') { & ./gpu-build.ps1 } else { & ./game-build.ps1 }
    }
    $classes = Join-Path $PSScriptRoot 'out/game/classes'
    $fixtures = Join-Path $PSScriptRoot 'out/fatal-check/classes'
    New-Item -ItemType Directory -Force $fixtures | Out-Null
    $classpath = "$fixtures;$classes"
    $sources = @('tools/parity/CpuFaultSession.java','tools/parity/FatalChild.java','tools/parity/FatalPreview.java')
    if ($Backend -eq 'gpu') {
        $classpath += ';out/gpu/classes;out/gpu/lib/*'
        $sources += 'tools/parity/GpuFaultSession.java'
    }
    Invoke-NativeLogged (Join-Path $jdk 'javac.exe') (@('--enable-preview','--release','23','-cp',$classpath,'-d',$fixtures) + $sources) 'out/fatal-check/compile.log' 'Fault fixture compilation failed'
    $modes = if ($Backend -eq 'cpu') { @('cpu-before','cpu-after','missing-module','report-write','repeated','worker-hang','cleanup-hang','cleanup-error','validation','cpu-library') } else { @('gpu-init','gpu-execution','gpu-library') }
    foreach ($mode in $modes) {
        $case = Join-Path $PSScriptRoot ('out/fatal-check/' + $mode + '-' + [guid]::NewGuid())
        New-Item -ItemType Directory -Force $case | Out-Null
        $reports = Join-Path $case 'crashes'
        if ($mode -eq 'report-write') { Set-Content $reports 'Deliberately a file, not a directory.' }
        $info = [Diagnostics.ProcessStartInfo]::new()
        $info.FileName = Join-Path $jdk 'java.exe'
        $errorFile = Join-Path $case 'hs_err_pid%p.log'
        $arguments = @('--enable-preview','-Djava.awt.headless=true',"-XX:ErrorFile=$errorFile","-Dgame.jvmErrorFile=$errorFile",'-cp',$classpath,'game.FatalChild',$mode,$reports)
        $info.Arguments = ($arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $info.WorkingDirectory = $PSScriptRoot
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $process = [Diagnostics.Process]::Start($info)
        try {
            $stdout = $process.StandardOutput.ReadToEndAsync()
            $stderr = $process.StandardError.ReadToEndAsync()
            if (-not $process.WaitForExit(15000)) { $process.Kill(); throw "$mode exceeded 15 second child deadline (including startup)" }
            $watch.Stop()
            $output = $stdout.Result + $stderr.Result
            Set-Content (Join-Path $case 'process.log') $output
            if ($mode.EndsWith('library')) {
                if ($process.ExitCode -ne 0 -or -not $output.Contains('library retains first failure')) { throw 'Library failure contract failed' }
            } elseif ($mode -eq 'validation') {
                if ($process.ExitCode -ne 0 -or $output -notmatch 'recoverable validation') { throw 'CPU recovery/validation failed' }
                if (@(Get-ChildItem $reports -Filter '*-crash.txt').Count) { throw 'Validation must not crash' }
            } else {
                if ($process.ExitCode -ne 1) { throw "$mode must exit 1, got $($process.ExitCode)" }
                if ([regex]::Matches($output, 'renderer startup:').Count -ne 1) { throw "$mode switched or repeated renderer startup" }
                if ($mode -eq 'report-write') {
                    if ($output -notmatch 'Cannot write diagnostic path') { throw 'Missing report-write error' }
                    $report = $output
                } else {
                    $files = @(Get-ChildItem $reports -Filter '*-crash.txt')
                    if ($files.Count -ne 1) { throw "$mode expected exactly one fatal report" }
                    $report = Get-Content $files[0].FullName -Raw
                    if (-not $output.Contains($files[0].FullName)) { throw 'stderr did not identify absolute report path' }
                    if (@(Get-ChildItem $reports -Filter '*-run.txt').Count -ne 1) { throw 'Startup context absent' }
                }
                foreach ($required in @('originalFailure:','backend=','build=','sourceSha256','jvmFatalLog=','lastRequested=','sceneFingerprint=','clockHour=','lastCompleted=','recentEvents:','hardExitDeadlineMillis=5000')) {
                    if (-not $report.Contains($required)) { throw "$mode report missing $required" }
                }
                $cause = switch ($mode) { 'gpu-init' {'original GPU init cause'} 'gpu-execution' {'Vulkan result -4'} 'missing-module' {'ClassNotFoundException'} default {'original CPU cause'} }
                if (-not $report.Contains($cause)) { throw "$mode lost original cause" }
                if ($mode -eq 'cpu-after' -and $report.Contains('lastCompleted=unavailable')) { throw 'Completed frame absent' }
                if ($mode -eq 'cpu-before' -and -not $report.Contains('before first frame')) { throw 'First-frame absence not marked' }
                if ($mode -eq 'gpu-init' -and -not $report.Contains('device=unavailable')) { throw 'Missing device not marked' }
                if ($mode -eq 'gpu-execution' -and -not $report.Contains('driverVersion=')) { throw 'Device/driver context absent' }
                if ($mode -eq 'cleanup-hang' -and -not $report.Contains('cleanupCompleted=false')) { throw 'Hung cleanup did not time out' }
                if ($mode -eq 'cleanup-error' -and -not $report.Contains('secondary cleanup cause')) { throw 'Cleanup error lost' }
                if ($mode -eq 'repeated' -and $report.Contains('must not replace first cause')) { throw 'Repeated error replaced original' }
                if ($mode -ne 'missing-module' -and -not $output.Contains('pointer released')) { throw 'Pointer cleanup was not invoked' }
            }
            Write-Output "PASS fatal child $mode ($($watch.ElapsedMilliseconds) ms, exit $($process.ExitCode)): $case"
        } finally { $process.Dispose() }
    }
} finally { Pop-Location }
