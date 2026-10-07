param([string]$OutputDirectory = "out/editor-check")
$ErrorActionPreference = "Stop"
$repository = $PSScriptRoot
$jdk = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/java.exe"))) { Join-Path $env:JAVA_HOME "bin" } else { "C:/Users/andri/.jdks/openjdk-23.0.1/bin" }
$java = Join-Path $jdk "java.exe"
$javac = Join-Path $jdk "javac.exe"
$output = Join-Path $repository $OutputDirectory
$classes = Join-Path $output "classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& (Join-Path $repository "engine-build.ps1") -OutputDirectory (Join-Path $OutputDirectory "boundary")
$sources = @(
    (Get-ChildItem (Join-Path $repository "src") -Recurse -Filter *.java).FullName
    (Get-ChildItem (Join-Path $repository "tst") -Recurse -Filter *.java).FullName
)
& $javac --enable-preview --release 23 -d $classes $sources 2>&1 | Tee-Object -FilePath (Join-Path $output "compile-all.log")
if ($LASTEXITCODE -ne 0) { throw "Editor/test compilation failed with exit code $LASTEXITCODE" }
$tests = @("editor.EditorControllerTest", "editor.SceneEditorPreviewTest", "engine.SceneDocumentTest", "engine.ScenePersistenceTest", "engine.SpatialQueryTest", "engine.EngineSessionTest")
foreach ($test in $tests) {
    $log = Join-Path $output ($test.Replace(".", "_") + ".log")
    $arguments = @("--enable-preview", "-Djava.awt.headless=true")
    if ($test -eq "editor.SceneEditorPreviewTest") { $arguments += "-Deditor.preview=$(Join-Path $output 'scene-editor-preview.png')" }
    $arguments += @("-cp", $classes, $test)
    & $java @arguments 2>&1 | Tee-Object -FilePath $log
    if ($LASTEXITCODE -ne 0) { throw "$test process failed with exit code $LASTEXITCODE" }
    $failure = @(Select-String -Path $log -Pattern '\[ERROR\].*harness\.SuiteRunner|failed with exception|AssertionError')
    if ($failure.Count) { throw "$test reported harness failures; inspect $log" }
}
$preview = Join-Path $output "scene-editor-preview.png"
if (-not (Test-Path $preview) -or (Get-Item $preview).Length -lt 20000) { throw "Window-free editor preview was not produced" }
Write-Output "Headless editor checks passed: $output"
