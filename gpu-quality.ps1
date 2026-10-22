param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'native-process.ps1')
if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'gpu-build.ps1') }
$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:/Users/andri/.jdks/openjdk-23.0.1/bin' }
$classPath = 'out/game/classes;out/gpu/classes;out/gpu/lib/*'
Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/GpuCheck.java','tools/gpu/GpuQuality.java') 'out/gpu/quality-compile.log' 'GPU quality compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.GpuQuality') 'out/gpu/quality.log' 'GPU quality comparison failed'
