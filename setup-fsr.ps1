param([switch]$Offline, [switch]$Rebuild)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$directory = Join-Path $root 'out/fsr'
$source = Join-Path $directory 'source'
$headers = Join-Path $directory 'vulkan-headers'
$revision = 'c6efa6bf7f2027b3ec94f28578bb5965eabb9e55'
$headerRevision = 'bae9700cd9425541a0f6029957f005e5ad3ef660'
$dllHash = 'A1624CC4238FEF046F30C4D80CE3F47BE63FC5F5373F49E3EE9EDB9960F54C78'
New-Item -ItemType Directory -Force $directory | Out-Null

function Invoke-FsrTool([string]$Executable, [string[]]$Arguments, [string]$Log) {
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
        if ($process.ExitCode -ne 0) { throw "FSR command failed; see out/fsr/$Log" }
    } finally { $process.Dispose() }
}


$runtime = Join-Path $directory 'runtime/Release'
$manifest = Join-Path $runtime 'build.json'
$bridgeHash = (Get-FileHash (Join-Path $root 'tools/fsr/bridge.cpp') -Algorithm SHA256).Hash
$cmakeHash = (Get-FileHash (Join-Path $root 'tools/fsr/CMakeLists.txt') -Algorithm SHA256).Hash
if ((Test-Path $manifest) -and -not $Rebuild) {
    $saved = Get-Content $manifest -Raw | ConvertFrom-Json
    if ($saved.revision -eq $revision -and $saved.headers -eq $headerRevision -and
            $saved.bridge -eq $bridgeHash -and $saved.cmake -eq $cmakeHash) {
        $valid = $true
        foreach ($name in @('amd_fidelityfx_vk.dll', 'rte_fsr.dll')) {
            $file = Join-Path $runtime $name
            $valid = $valid -and (Test-Path $file) -and ((Get-FileHash $file -Algorithm SHA256).Hash -eq $saved.files.$name)
        }
        if ($valid -and $saved.files.'amd_fidelityfx_vk.dll' -eq $dllHash) {
            Write-Output 'Pinned FSR 3.1.4 Vulkan runtime verified offline.'; return
        }
    }
}
if (-not (Test-Path (Join-Path $source '.git'))) {
    if ($Offline) { throw 'Missing FSR source. Run ./setup-fsr.ps1 once with network access, CMake and Visual Studio C++ build tools.' }
    Invoke-FsrTool 'git' @('clone', '--filter=blob:none', '--no-checkout', '--depth', '1', '--branch', 'v1.1.4',
        'https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK.git', $source) 'source-clone.log'
    Invoke-FsrTool 'git' @('-C', $source, 'sparse-checkout', 'set', '--no-cone',
        '/PrebuiltSignedDLL/amd_fidelityfx_vk.dll', '/ffx-api/include/', '/LICENSE.txt') 'source-sparse.log'
    Invoke-FsrTool 'git' @('-C', $source, 'checkout', '--detach', $revision) 'source-checkout.log'
}
if (-not (Test-Path (Join-Path $headers '.git'))) {
    if ($Offline) { throw 'Missing Vulkan headers. Run ./setup-fsr.ps1 once with network access.' }
    Invoke-FsrTool 'git' @('clone', '--depth', '1', '--branch', 'v1.3.250',
        'https://github.com/KhronosGroup/Vulkan-Headers.git', $headers) 'headers-clone.log'
}
foreach ($entry in @(@($source, $revision, 'source'), @($headers, $headerRevision, 'headers'))) {
    Invoke-FsrTool 'git' @('-C', $entry[0], 'rev-parse', 'HEAD') "$($entry[2])-revision.log"
    if ((Get-Content (Join-Path $directory "$($entry[2])-revision.log") -Raw).Trim() -ne $entry[1]) {
        throw "out/fsr/$($entry[2]) must contain the pinned revision; preserve and move any other checkout before setup."
    }
    Invoke-FsrTool 'git' @('-C', $entry[0], 'diff', '--exit-code', 'HEAD', '--') "$($entry[2])-changes.log"
}
$dll = Join-Path $source 'PrebuiltSignedDLL/amd_fidelityfx_vk.dll'
if ((Get-FileHash $dll -Algorithm SHA256).Hash -ne $dllHash) { throw 'Pinned AMD Vulkan DLL checksum mismatch' }
Invoke-FsrTool 'cmake' @('-S', (Join-Path $root 'tools/fsr'), '-B', (Join-Path $directory 'bridge'),
    "-DFSR_SOURCE_DIR=$source", "-DVULKAN_HEADERS_DIR=$headers") 'setup-configure.log'
Invoke-FsrTool 'cmake' @('--build', (Join-Path $directory 'bridge'), '--config', 'Release', '-j', '1', '--', '/nr:false') 'setup-build.log'
$files = @{}
foreach ($name in @('amd_fidelityfx_vk.dll', 'rte_fsr.dll')) {
    $files[$name] = (Get-FileHash (Join-Path $runtime $name) -Algorithm SHA256).Hash
}
Copy-Item -LiteralPath (Join-Path $source 'LICENSE.txt') -Destination (Join-Path $runtime 'FidelityFX-LICENSE.txt')
@{ revision=$revision; headers=$headerRevision; bridge=$bridgeHash; cmake=$cmakeHash; files=$files } |
    ConvertTo-Json | Set-Content -LiteralPath $manifest -Encoding utf8
Write-Output 'Pinned FSR 3.1.4 Vulkan runtime built. Install NRD with ./setup-nrd.ps1 before selecting FSR in the game.'
