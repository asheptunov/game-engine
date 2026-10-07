param([string]$OutputDirectory = "out/input-check")
$ErrorActionPreference = "Stop"
$repository = $PSScriptRoot
. (Join-Path $repository "native-process.ps1")
$jdk = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/java.exe"))) { Join-Path $env:JAVA_HOME "bin" } else { "C:/Users/andri/.jdks/openjdk-23.0.1/bin" }
$java = Join-Path $jdk "java.exe"
$output = Join-Path $repository $OutputDirectory
$classes = Join-Path $output "classes"

& (Join-Path $repository "editor-check.ps1") -OutputDirectory $OutputDirectory

$tests = @(
    @{ Name = "engine.input.EngineInputTest"; Headless = $true },
    @{ Name = "platform.awt.input.AwtInputAdapterTest"; Headless = $true },
    @{ Name = "ui.ActionRegistryTest"; Headless = $true },
    @{ Name = "ui.BindingsLoaderTest"; Headless = $true },
    @{ Name = "ui.InputBindingsTest"; Headless = $true },
    @{ Name = "ui.KeyChordParseTest"; Headless = $true },
    @{ Name = "ui.KeyChordTest"; Headless = $true },
    @{ Name = "ui.MouseBindingsTest"; Headless = $true },
    @{ Name = "ui.MouseChordParseTest"; Headless = $true },
    @{ Name = "scenes.textureeditor.TextureEditorKeyBindingsTest"; Headless = $false },
    @{ Name = "scenes.textureeditor.TextureEditorMouseBindingsTest"; Headless = $true },
    @{ Name = "ViewportKeyBindingsTest"; Headless = $false }
)
foreach ($test in $tests) {
    $name = $test.Name
    $log = Join-Path $output ($name.Replace(".", "_") + ".log")
    $headless = if ($test.Headless) { "true" } else { "false" }
    $arguments = @("--enable-preview", "-Djava.awt.headless=$headless", "-cp", $classes, $name)
    Invoke-NativeLogged $java $arguments $log "$name process failed"
    $failure = @(Select-String -Path $log -Pattern '\[ERROR\].*harness\.SuiteRunner|failed with exception|AssertionError')
    if ($failure.Count) { throw "$name reported harness failures; inspect $log" }
}
Write-Output "Input binding checks passed: $output"
