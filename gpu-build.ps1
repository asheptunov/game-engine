param([switch]$NoGameBuild)
$ErrorActionPreference = 'Stop'
$repository = $PSScriptRoot
. (Join-Path $repository 'native-process.ps1')
& (Join-Path $repository 'setup-gpu.ps1') -Offline
if (-not $NoGameBuild) { & (Join-Path $repository 'game-build.ps1') }
$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:/Users/andri/.jdks/openjdk-23.0.1/bin' }
$output = Join-Path $repository 'out/gpu'
New-Item -ItemType Directory -Force (Join-Path $output 'classes') | Out-Null
$classPath = (Join-Path $repository 'out/game/classes') + ';' + (Join-Path $output 'lib/*')
$sources = @((Get-ChildItem (Join-Path $repository 'src/gpu') -Filter *.java).FullName) + (Join-Path $repository 'tools/gpu/CompileShader.java')
Invoke-NativeLogged (Join-Path $jdk 'javac.exe') (@('--enable-preview','--release','23','-cp',$classPath,'-d',(Join-Path $output 'classes')) + $sources) (Join-Path $output 'compile.log') 'GPU compilation failed'
$shaderSource = Join-Path $repository 'assets/gpu/diffuse.comp'
$shaderBinary = Join-Path $output 'diffuse.spv'
$shaderStamp = Join-Path $output 'shader-source.sha256'
$shaderHash = (Get-FileHash $shaderSource -Algorithm SHA256).Hash
if (-not (Test-Path $shaderBinary) -or -not (Test-Path $shaderStamp) -or (Get-Content $shaderStamp -Raw).Trim() -ne $shaderHash) {
Invoke-NativeLogged (Join-Path $jdk 'java.exe') @('--enable-preview','-cp',((Join-Path $output 'classes') + ';' + $classPath),'gpu.CompileShader',(Join-Path $repository 'assets/gpu/diffuse.comp'),(Join-Path $output 'diffuse.spv')) (Join-Path $output 'shader.log') 'Shader compilation failed'
    Set-Content $shaderStamp $shaderHash
}
Write-Output 'GPU build passed.'
