param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'native-process.ps1')
if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'gpu-build.ps1') }
$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:/Users/andri/.jdks/openjdk-23.0.1/bin' }
$classPath = 'out/gpu/classes;out/game/classes;out/gpu/lib/*'
Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/GpuCheck.java','tools/gpu/GpuUiCheck.java','tools/gpu/FogSunCheck.java','tools/gpu/FogResolutionCheck.java','tools/gpu/FogGridCheck.java','tools/gpu/FogJitterCheck.java') 'out/gpu/check-compile.log' 'GPU check compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.GpuCheck') 'out/gpu/check.log' 'GPU verification failed'

Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/TraversalCheck.java') 'out/gpu/traversal-compile.log' 'Traversal comparison compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.TraversalCheck') 'out/gpu/traversal-check.log' 'Traversal comparison checks failed'

Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'game.GpuUiCheck') 'out/gpu/ui-check.log' 'GPU game-panel verification failed'

Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.FogSunCheck') 'out/gpu/fog-sun-check.log' 'Fog cache verification failed'

Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.FogResolutionCheck') 'out/gpu/fog-resolution-check.log' 'Fog resolution verification failed'

Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.FogGridCheck') 'out/gpu/fog-grid-check.log' 'Fog lighting grid verification failed'

Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.FogJitterCheck') 'out/gpu/fog-jitter-check.log' 'Fog jitter verification failed'

Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/DisplayCopyCheck.java') 'out/gpu/display-copy-compile.log' 'Display conversion check compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'game.DisplayCopyCheck','gpu','800') 'out/gpu/display-copy-check.log' 'Parallel GPU image conversion checks failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'game.DisplayCopyCheck','cpu','128') 'out/gpu/display-copy-cpu-check.log' 'CPU image conversion compatibility checks failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'game.DisplayCopyCheck','gpu','1920','1080','check') 'out/gpu/1080p-gpu-check.log' '1080p GPU rendering and lease checks failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'game.DisplayCopyCheck','cpu','1920','1080','check') 'out/gpu/1080p-cpu-check.log' '1080p CPU rendering and lease checks failed'

Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/GpuDisplayCheck.java','tools/gpu/CompactDisplayCheck.java') 'out/gpu/compact-compile.log' 'Compact display check compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.GpuDisplayCheck') 'out/gpu/display-encoding-check.log' 'GPU display encoding checks failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'game.CompactDisplayCheck') 'out/gpu/compact-display-check.log' 'Compact display lifecycle checks failed'

Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/DirectPresentationCheck.java','tools/gpu/GameOverlayCheck.java') 'out/gpu/direct-compile.log' 'Direct presentation check compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=false','-cp',$classPath,'gpu.DirectPresentationCheck','native') 'out/gpu/direct-check.log' 'Resident/compositor/hidden native-window checks failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'game.GameOverlayCheck') 'out/gpu/overlay-check.log' 'UI patch checks failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'game.CompactDisplayCheck','resident-captures') 'out/gpu/resident-capture-check.log' 'Resident snapshot/history checks failed'

Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/TlasReuseCheck.java') 'out/gpu/tlas-reuse-compile.log' 'TLAS reuse check compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.TlasReuseCheck') 'out/gpu/tlas-reuse-check.log' 'TLAS storage reuse checks failed'

Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/GpuAdaptiveCheck.java') 'out/gpu/adaptive-compile.log' 'GPU adaptive check compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.GpuAdaptiveCheck') 'out/gpu/adaptive-check.log' 'GPU adaptive resolution checks failed'

Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/DisplayOwnershipCheck.java') 'out/gpu/ownership-compile.log' 'Display ownership compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.DisplayOwnershipCheck') 'out/gpu/ownership-check.log' 'Display ownership checks failed'

Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/GamePaintCheck.java') 'out/gpu/game-paint-compile.log' 'Game paint compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'game.GamePaintCheck') 'out/gpu/game-paint-check.log' 'Game paint equivalence checks failed'
