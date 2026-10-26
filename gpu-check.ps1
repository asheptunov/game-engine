param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'native-process.ps1')
if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'gpu-build.ps1') }
$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:/Users/andri/.jdks/openjdk-23.0.1/bin' }
$classPath = 'out/game/classes;out/gpu/classes;out/gpu/lib/*'
Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/GpuCheck.java','tools/gpu/GpuUiCheck.java','tools/gpu/FogSunCheck.java','tools/gpu/FogResolutionCheck.java','tools/gpu/FogGridCheck.java') 'out/gpu/check-compile.log' 'GPU check compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.GpuCheck') 'out/gpu/check.log' 'GPU verification failed'

Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'game.GpuUiCheck') 'out/gpu/ui-check.log' 'GPU game-panel verification failed'

Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.FogSunCheck') 'out/gpu/fog-sun-check.log' 'Fog cache verification failed'

Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.FogResolutionCheck') 'out/gpu/fog-resolution-check.log' 'Fog resolution verification failed'

Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.FogGridCheck') 'out/gpu/fog-grid-check.log' 'Fog lighting grid verification failed'
