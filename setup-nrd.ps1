param([switch]$Offline, [switch]$Rebuild)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$directory = Join-Path $root 'out/nrd'
$source = Join-Path $directory 'source'
$revision = 'e06852bd545973cb224eb28820b891205cfc1624'
New-Item -ItemType Directory -Force $directory | Out-Null

function Invoke-NrdTool([string]$Executable, [string[]]$Arguments, [string]$Log) {
    $command = @(Get-Command $Executable -CommandType Application -ErrorAction Stop)[0]
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $command.Source
    $quoted = foreach ($argument in $Arguments) {
        '"' + (($argument -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
    }
    $info.Arguments = $quoted -join ' '
    $info.WorkingDirectory = $root
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.EnvironmentVariables['MSBUILDDISABLENODEREUSE'] = '1'
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        [IO.File]::WriteAllText((Join-Path $directory $Log), $stdout.Result + $stderr.Result)
        if ($process.ExitCode -ne 0) { throw "NRD command failed; see out/nrd/$Log" }
    } finally { $process.Dispose() }
}

$manifest = Join-Path $directory 'runtime/Release/build.json'
$bridgeHash = (Get-FileHash (Join-Path $root 'tools/nrd/bridge.cpp') -Algorithm SHA256).Hash
$cmakeHash = (Get-FileHash (Join-Path $root 'tools/nrd/CMakeLists.txt') -Algorithm SHA256).Hash
if ((Test-Path $manifest) -and -not $Rebuild) {
    $saved = Get-Content $manifest -Raw | ConvertFrom-Json
    if ($saved.revision -eq $revision -and $saved.bridge -eq $bridgeHash -and $saved.cmake -eq $cmakeHash) {
        $valid = $true
        foreach ($name in @('NRD.dll', 'rte_nrd.dll')) {
            $file = Join-Path (Split-Path $manifest) $name
            $valid = $valid -and (Test-Path $file) -and ((Get-FileHash $file -Algorithm SHA256).Hash -eq $saved.files.$name)
        }
        if ($valid) { Write-Output 'Pinned NRD 4.18.0 runtime verified offline.'; return }
    }
}
if (-not (Test-Path (Join-Path $source '.git'))) {
    if ($Offline) { throw 'Missing NRD source. Run ./setup-nrd.ps1 once with network access, CMake 3.30+ and Visual Studio C++ build tools.' }
    Invoke-NrdTool 'git' @('clone', '--no-checkout', 'https://github.com/NVIDIA-RTX/NRD.git', $source) 'source-clone.log'
    Invoke-NrdTool 'git' @('-C', $source, 'checkout', '--detach', $revision) 'source-checkout.log'
}
Invoke-NrdTool 'git' @('-C', $source, 'rev-parse', 'HEAD') 'source-revision.log'
if ((Get-Content (Join-Path $directory 'source-revision.log') -Raw).Trim() -ne $revision) {
    throw 'out/nrd/source must contain the pinned NRD revision; preserve and move any other checkout before setup.'
}
Invoke-NrdTool 'git' @('-C', $source, 'diff', '--exit-code', 'HEAD', '--') 'source-changes.log'
$disconnected = if ($Offline) { 'ON' } else { 'OFF' }
Invoke-NrdTool 'cmake' @('-S', $source, '-B', (Join-Path $directory 'build'),
    '-DNRD_NRI=OFF', '-DNRD_EMBEDS_DXBC_SHADERS=OFF', '-DNRD_EMBEDS_DXIL_SHADERS=OFF',
    '-DNRD_EMBEDS_SPIRV_SHADERS=ON', '-DNRD_SUPPORTS_QUAD_INTRINSICS=OFF',
    '-DNRD_NORMAL_ENCODING=2', '-DNRD_ROUGHNESS_ENCODING=1', "-DFETCHCONTENT_FULLY_DISCONNECTED=$disconnected") 'setup-configure.log'
Invoke-NrdTool 'cmake' @('--build', (Join-Path $directory 'build'), '--config', 'Release', '-j', '1', '--', '/nr:false') 'setup-build.log'
Invoke-NrdTool 'cmake' @('-S', (Join-Path $root 'tools/nrd'), '-B', (Join-Path $directory 'bridge-live'),
    "-DNRD_SOURCE_DIR=$source") 'setup-bridge-configure.log'
Invoke-NrdTool 'cmake' @('--build', (Join-Path $directory 'bridge-live'), '--config', 'Release', '-j', '1', '--', '/nr:false') 'setup-bridge-build.log'
$files = @{}
foreach ($name in @('NRD.dll', 'rte_nrd.dll')) {
    $files[$name] = (Get-FileHash (Join-Path (Split-Path $manifest) $name) -Algorithm SHA256).Hash
}
Copy-Item -LiteralPath (Join-Path $source 'LICENSE.txt') -Destination (Join-Path (Split-Path $manifest) 'NRD-LICENSE.txt')
@{ revision=$revision; bridge=$bridgeHash; cmake=$cmakeHash; files=$files } | ConvertTo-Json |
    Set-Content -LiteralPath $manifest -Encoding utf8
Write-Output 'Pinned NRD 4.18.0 runtime built. GPU rendering can now select NRD RELAX.'
