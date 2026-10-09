param([string]$OutputDirectory = "out/game-check")
$ErrorActionPreference = "Stop"
$repository = $PSScriptRoot
. (Join-Path $repository "tools/style/common.ps1")
& (Join-Path $repository "game-build.ps1") -OutputDirectory $OutputDirectory
$classes = Join-Path (Join-Path $repository $OutputDirectory) "classes"
$sources = @(
    (Get-ChildItem (Join-Path $repository "tst/harness") -Filter *.java).FullName
    (Get-ChildItem (Join-Path $repository "tst/game") -Filter *.java).FullName
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
$log = Join-Path $repository "$OutputDirectory/tests.log"
$code = Invoke-StyleJava @("--enable-preview", "-Djava.awt.headless=true", "-cp", $classes, "game.SampleGameTest") $log
Get-Content $log
if ($code -ne 0) { throw "Game tests failed: $log" }
Write-Output "Game checks passed; native pointer/window QA remains manual."
