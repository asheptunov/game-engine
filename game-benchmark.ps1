param([switch]$NoBuild, [switch]$NoAdaptive, [switch]$Profile, [string]$OutputDirectory = 'out/game-route')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tools/style/common.ps1')
if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'game-check.ps1') }
$classes = Join-Path $PSScriptRoot 'out/game-check/classes'
if (-not (Test-Path (Join-Path $classes 'game/GameRouteBenchmark.class'))) { throw 'Run ./game-check.ps1 first.' }
$output = Join-Path $PSScriptRoot $OutputDirectory
[void](New-Item -ItemType Directory -Force $output)
$cpu = (Get-CimInstance Win32_Processor | Select-Object -ExpandProperty Name) -join ', '
$sourceFiles = @(Get-ChildItem (Join-Path $PSScriptRoot 'src'), (Join-Path $PSScriptRoot 'tst') -Recurse -Filter '*.java' | Sort-Object FullName)
$fingerprints = @($sourceFiles | ForEach-Object { "$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash)  $($_.FullName.Substring($PSScriptRoot.Length + 1))" })
Write-StyleUtf8 (Join-Path $output 'source-sha256.txt') ($fingerprints -join "`n")
Write-StyleUtf8 (Join-Path $output 'run.txt') "JFR=$($Profile.IsPresent)`nrevision=$(git rev-parse HEAD)`n"
$javaArguments = @('--enable-preview', '-Djava.awt.headless=true', "-Dgame.cpu=$cpu")
if ($Profile) {
    # Skip the 30-second route warmup. JFR samples are diagnostic, not unprofiled timing evidence.
    $recording = Join-Path $output 'game.jfr'
    $javaArguments += "-XX:StartFlightRecording=filename=$recording,settings=profile,delay=30s,dumponexit=true"
}
$javaArguments += @('-cp', $classes, 'game.GameRouteBenchmark', $output, (-not $NoAdaptive).ToString().ToLowerInvariant())
$code = Invoke-StyleJava $javaArguments (Join-Path $output 'benchmark.log')
Get-Content (Join-Path $output 'benchmark.log')
$benchmarkCode = $code
if ($Profile -and (Test-Path -LiteralPath $recording)) {
    $summary = Join-Path $output 'jfr-summary.csv'
    $code = Invoke-StyleJava @('--enable-preview', '--source', '23', (Join-Path $PSScriptRoot 'tools/game/SummarizeJfr.java'), $recording) $summary
    if ($code -ne 0) { throw "JFR summary failed; inspect $summary" }
}
if ($benchmarkCode -ne 0) { throw "Benchmark failed; inspect $output" }
Write-Output "G4 benchmark passed. Results: $output"
