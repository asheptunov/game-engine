param([string]$OutputDirectory = "out/game-check")
$ErrorActionPreference = "Stop"
$repository = $PSScriptRoot
. (Join-Path $repository "tools/style/common.ps1")
& (Join-Path $repository "game-build.ps1") -OutputDirectory $OutputDirectory
$classes = Join-Path (Join-Path $repository $OutputDirectory) "classes"
$sources = @(
    (Get-ChildItem (Join-Path $repository "tst/harness") -Filter *.java).FullName
    (Get-ChildItem (Join-Path $repository "tst/game") -Filter *.java).FullName
    (Join-Path $repository "tst/engine/CubeTextureTest.java")
    (Join-Path $repository "tst/engine/SunSkyTest.java")
)
$jdk = Split-Path $styleJava
$compileLog = Join-Path $repository "$OutputDirectory/test-compile.log"
$info = [Diagnostics.ProcessStartInfo]::new()
$info.FileName = Join-Path $jdk "javac.exe"
$info.Arguments = (@("--enable-preview", "--release", "23", "-cp", $classes, "-d", $classes) + $sources | ForEach-Object { '"' + $_ + '"' }) -join ' '
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
$process = [Diagnostics.Process]::new()
$process.StartInfo = $info
try {
    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    Write-StyleUtf8 $compileLog ($stdout.Result + $stderr.Result)
    if ($process.ExitCode -ne 0) { throw "Game test compilation failed: $compileLog" }
} finally { $process.Dispose() }
foreach ($suite in @("game.SampleGameTest", "engine.CubeTextureTest", "engine.SunSkyTest", "game.GameLightingTest")) {
    $name = if ($suite -eq "game.SampleGameTest") { "tests.log" } elseif ($suite -eq "engine.CubeTextureTest") { "texture-tests.log" } elseif ($suite -eq "engine.SunSkyTest") { "sun-sky-tests.log" } else { "lighting-tests.log" }
    $log = Join-Path (Join-Path $repository $OutputDirectory) $name
    $code = Invoke-StyleJava @("--enable-preview", "-Djava.awt.headless=true", "-cp", $classes, $suite) $log
    Get-Content $log
    if ($code -ne 0) { throw "Game checks failed: $log" }
}
Write-Output "Game checks passed; native pointer/window QA remains manual."
