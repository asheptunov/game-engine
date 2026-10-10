param([switch]$NoBuild, [switch]$NoAdaptive, [string]$OutputDirectory = 'out/game-route')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tools/style/common.ps1')
if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'game-check.ps1') }
$classes = Join-Path $PSScriptRoot 'out/game-check/classes'
if (-not (Test-Path (Join-Path $classes 'game/GameRouteBenchmark.class'))) { throw 'Run ./game-check.ps1 first.' }
$output = Join-Path $PSScriptRoot $OutputDirectory
[void](New-Item -ItemType Directory -Force $output)
$cpu = (Get-CimInstance Win32_Processor | Select-Object -ExpandProperty Name) -join ', '
$code = Invoke-StyleJava @('--enable-preview', '-Djava.awt.headless=true', "-Dgame.cpu=$cpu", '-cp', $classes, 'game.GameRouteBenchmark', $output, (-not $NoAdaptive).ToString().ToLowerInvariant()) (Join-Path $output 'benchmark.log')
Get-Content (Join-Path $output 'benchmark.log')
if ($code -ne 0) { throw "Benchmark failed; inspect $output" }
Write-Output "G4 benchmark passed. Results: $output"
