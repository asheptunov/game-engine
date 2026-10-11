param([switch]$NoBuild, [string]$OutputDirectory = 'out/bvh-construction-pinned')
$ErrorActionPreference = 'Stop'
$taskRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $taskRepository 'tools/style/common.ps1')
if (-not $NoBuild) { & (Join-Path $taskRepository 'verify.ps1') }
$taskOutput = [IO.Path]::GetFullPath((Join-Path $taskRepository $OutputDirectory))
if (-not $taskOutput.StartsWith($taskRepository + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Benchmark output must be inside the repository.' }
[void](New-Item -ItemType Directory -Force -Path $taskOutput)
$taskClasses = Join-Path $taskRepository 'out/scene-check/base/classes'
$taskAgentClass = Join-Path $taskClasses 'engine/BvhMemoryAgent.class'
if (-not (Test-Path -LiteralPath $taskAgentClass)) { throw 'Run ./verify.ps1 first.' }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskAgent = Join-Path $taskOutput 'bvh-memory-agent.jar'
$taskStream = [IO.File]::Open($taskAgent, [IO.FileMode]::Create)
$taskArchive = [IO.Compression.ZipArchive]::new($taskStream, [IO.Compression.ZipArchiveMode]::Create)
try {
    $taskManifest = $taskArchive.CreateEntry('META-INF/MANIFEST.MF').Open()
    $taskBytes = [Text.Encoding]::UTF8.GetBytes("Manifest-Version: 1.0`nPremain-Class: engine.BvhMemoryAgent`n`n")
    try { $taskManifest.Write($taskBytes, 0, $taskBytes.Length) } finally { $taskManifest.Dispose() }
    [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskArchive, $taskAgentClass, 'engine/BvhMemoryAgent.class')
} finally { $taskArchive.Dispose(); $taskStream.Dispose() }
Write-StyleUtf8 (Join-Path $taskOutput 'metadata.txt') "logicalProcessorAffinityMask=1 (logical CPU 0)`nchildConsole=False; streams redirected`nrevision=$(git rev-parse HEAD)`n"
foreach ($taskScene in @('landscape', 'blocks-1024', 'mesh-4', 'mesh-12', 'mesh-32')) {
    $taskArguments = @('--enable-preview', '-Djava.awt.headless=true', "-javaagent:$taskAgent", '-cp', $taskClasses, 'engine.BvhConstructionBenchmark', 'confirm', $taskScene)
    $taskQuoted = foreach ($taskArgument in $taskArguments) { '"' + (($taskArgument -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"' }
    $taskInfo = [Diagnostics.ProcessStartInfo]::new()
    $taskInfo.FileName = $styleJava
    $taskInfo.Arguments = $taskQuoted -join ' '
    $taskInfo.WorkingDirectory = $taskRepository
    $taskInfo.UseShellExecute = $false
    $taskInfo.CreateNoWindow = $true
    $taskInfo.RedirectStandardOutput = $true
    $taskInfo.RedirectStandardError = $true
    $taskProcess = [Diagnostics.Process]::new()
    $taskProcess.StartInfo = $taskInfo
    try {
        [void]$taskProcess.Start()
        $taskProcess.ProcessorAffinity = [IntPtr]1
        if ($taskProcess.ProcessorAffinity.ToInt64() -ne 1) { throw 'CPU affinity was not applied.' }
        $taskStdout = $taskProcess.StandardOutput.ReadToEndAsync()
        $taskStderr = $taskProcess.StandardError.ReadToEndAsync()
        $taskProcess.WaitForExit()
        Write-StyleUtf8 (Join-Path $taskOutput "$taskScene.csv") ($taskStdout.Result + $taskStderr.Result)
        if ($taskProcess.ExitCode -ne 0) { throw "Benchmark failed: $taskScene" }
    } finally { $taskProcess.Dispose() }
}
Write-Output "Pinned BVH comparison complete: $taskOutput"
