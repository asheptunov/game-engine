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

Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/ReconstructionCheck.java','tools/gpu/ReconstructionBenchmark.java') 'out/gpu/reconstruction-compile.log' 'Reconstruction check compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.ReconstructionCheck') 'out/gpu/reconstruction-check.log' 'GPU reconstruction checks failed'

Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/LightingReconstructionCheck.java') 'out/gpu/lighting-compile.log' 'Lighting reconstruction compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.LightingReconstructionCheck') 'out/gpu/lighting-check.log' 'Lighting reconstruction checks failed'

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

Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/FoveatedCheck.java') 'out/gpu/foveated-compile.log' 'Foveated compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.FoveatedCheck') 'out/gpu/foveated-check.log' 'Foveated sampling checks failed'

Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/SmoothFoveationCheck.java') 'out/gpu/smooth-foveation-compile.log' 'Smooth foveation compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.SmoothFoveationCheck') 'out/gpu/smooth-foveation-check.log' 'Smooth foveation checks failed'

Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/HighResolutionCheck.java') 'out/gpu/high-resolution-compile.log' 'High-resolution compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.HighResolutionCheck') 'out/gpu/high-resolution-check.log' 'High-resolution checks failed'

Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/ThinLeafCheck.java') 'out/gpu/thin-leaf-compile.log' 'Thin-leaf compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.ThinLeafCheck') 'out/gpu/thin-leaf-check.log' 'Thin-leaf transport checks failed'

Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/BloomCheck.java','tools/gpu/BloomLifecycleCheck.java') 'out/gpu/bloom-compile.log' 'Bloom compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'gpu.BloomCheck') 'out/gpu/bloom-check.log' 'Bloom encoding and compositor checks failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-Djava.awt.headless=true','-cp',$classPath,'game.BloomLifecycleCheck') 'out/gpu/bloom-lifecycle.log' 'Bloom lifecycle checks failed'

Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/PathRouletteCheck.java') 'out/gpu/roulette-compile.log' 'Path roulette compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','--enable-native-access=ALL-UNNAMED','-Djava.awt.headless=true','-cp',$classPath,'gpu.PathRouletteCheck') 'out/gpu/roulette-check.log' 'Path roulette checks failed'

Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/SunHistoryCheck.java') 'out/gpu/sun-compile.log' 'Sun history compilation failed'
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','--enable-native-access=ALL-UNNAMED','-Djava.awt.headless=true','-cp',$classPath,'gpu.SunHistoryCheck') 'out/gpu/sun-check.log' 'Sun history checks failed'

if (Test-Path 'out/nrd/runtime/Release/rte_nrd.dll') {
    & (Join-Path $PSScriptRoot 'setup-nrd.ps1') -Offline
    Invoke-NativeLogged (Join-Path $jdk 'javac.exe') @('--enable-preview','--release','23','-cp',$classPath,'-d','out/gpu/classes','tools/gpu/NrdCheck.java') 'out/gpu/nrd-compile.log' 'NRD check compilation failed'
    Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','--enable-native-access=ALL-UNNAMED','-Djava.awt.headless=true','-cp',$classPath,'gpu.NrdCheck') 'out/gpu/nrd-check.log' 'NRD checks failed'
} else {
    Write-Output 'Optional NRD checks skipped: run ./setup-nrd.ps1 to install the pinned runtime.'
}
