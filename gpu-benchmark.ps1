param([string]$OutputDirectory = 'out/gpu/route', [switch]$NoBuild)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'native-process.ps1')
if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'gpu-build.ps1') }
$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:/Users/andri/.jdks/openjdk-23.0.1/bin' }
$classPath = 'out/game/classes;out/gpu/classes;out/gpu/lib/*'
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
Get-ChildItem src,tools/gpu,assets/gpu -Recurse -File | Sort-Object FullName | ForEach-Object { "$((Get-FileHash $_.FullName -Algorithm SHA256).Hash)  $($_.FullName.Substring($PSScriptRoot.Length+1))" } | Set-Content (Join-Path $OutputDirectory 'source-sha256.txt')
& nvidia-smi --query-gpu=name,driver_version,memory.total,power.limit --format=csv | Set-Content (Join-Path $OutputDirectory 'device.txt')
Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/GpuRouteComparison.java') 'out/gpu/route-compile.log' 'Route benchmark compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'game.GpuRouteComparison',$OutputDirectory) (Join-Path $OutputDirectory 'benchmark.log') 'GPU route comparison failed'
