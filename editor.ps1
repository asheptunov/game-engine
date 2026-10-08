param([switch]$NoBuild)
$ErrorActionPreference = "Stop"
$repository = $PSScriptRoot
$output = Join-Path $repository "out/editor"
$classes = Join-Path $output "classes"
if (-not $NoBuild -or -not (Test-Path (Join-Path $classes "editor/SceneEditorMain.class"))) { & (Join-Path $repository "editor-build.ps1") -OutputDirectory "out/editor" }
$jdk = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/javaw.exe"))) { Join-Path $env:JAVA_HOME "bin" } else { "C:/Users/andri/.jdks/openjdk-23.0.1/bin" }
$javaw = Join-Path $jdk "javaw.exe"
$stdout = Join-Path $output "editor-stdout.log"
$stderr = Join-Path $output "editor-error.log"
$quotedClasses = '"' + $classes + '"'
$process = Start-Process -FilePath $javaw -ArgumentList @("--enable-preview", "-cp", $quotedClasses, "editor.SceneEditorMain") -WorkingDirectory $repository -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
Start-Sleep -Milliseconds 750
if ($process.HasExited) { throw "Scene editor exited during startup (code $($process.ExitCode)); inspect $stderr" }
Write-Output "Scene editor started (PID $($process.Id)). Startup errors: $stderr"
