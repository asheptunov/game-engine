$ErrorActionPreference = 'Stop'
$styleRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$styleCache = Join-Path $styleRepository 'out/style-tools'
$styleJavaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { Join-Path $env:USERPROFILE '.jdks/openjdk-23.0.1' }
$styleJava = Join-Path $styleJavaHome 'bin/java.exe'

function Write-StyleUtf8([string]$Path, [string]$Text) {
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}

function Assert-StyleTools {
    if (-not (Test-Path -LiteralPath $styleJava)) { throw "JDK not found: $styleJava. Set JAVA_HOME to JDK 23." }
    foreach ($tool in (Get-Content (Join-Path $PSScriptRoot 'tools.json') -Raw | ConvertFrom-Json)) {
        $path = Join-Path $styleCache $tool.file
        if (-not (Test-Path -LiteralPath $path)) { throw "Missing $path. Run ./setup-style.ps1 first." }
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $tool.sha256) {
            throw "Checksum mismatch: $path. Restore the pinned tool with ./setup-style.ps1."
        }
    }
    if (-not (Test-Path (Join-Path $styleCache 'pmd-bin-7.7.0/lib/pmd-cli-7.7.0.jar'))) {
        throw 'PMD is not extracted. Run ./setup-style.ps1.'
    }
}

function Get-StyleSources {
    @(Get-ChildItem (Join-Path $styleRepository 'src'),(Join-Path $styleRepository 'tst') -Recurse -Filter '*.java' |
        Sort-Object FullName | ForEach-Object { $_.FullName })
}

function Invoke-StyleJava([string[]]$JavaArgs, [string]$Log) {
    # Use a console-free child and drain both streams asynchronously, including on Windows PowerShell 5.1.
    $quoted = foreach ($argument in $JavaArgs) {
        '"' + (($argument -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
    }
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $styleJava
    $info.Arguments = $quoted -join ' '
    $info.WorkingDirectory = $styleRepository
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
        Write-StyleUtf8 $Log ($stdout.Result + $stderr.Result)
        return $process.ExitCode
    } finally { $process.Dispose() }
}

function Invoke-StyleFormatter([bool]$Apply, [string]$OutputDirectory) {
    $sources = @(Get-StyleSources)
    if ($sources.Count -eq 0) { throw 'No Java sources found.' }
    foreach ($source in $sources) {
        $text = [IO.File]::ReadAllText($source)
        if ($text.Contains("`r")) {
            if (-not $Apply) { throw "Non-LF Java line endings: $source. Run ./format.ps1." }
            Write-StyleUtf8 $source ($text.Replace("`r`n", "`n").Replace("`r", "`n"))
        }
    }
    $list = Join-Path $OutputDirectory 'formatter.args'
    $mode = if ($Apply) { @('--replace') } else { @('--dry-run', '--set-exit-if-changed') }
    # Let the Java launcher expand its quoted argfile; the formatter's own @file
    # parser splits paths at spaces and does not remove quotes.
    $arguments = @('-jar', (Join-Path $styleCache 'formatter.jar'), '--aosp') + $mode + $sources
    $lines = @($arguments | ForEach-Object { '"' + $_.Replace('\','/') + '"' })
    Write-StyleUtf8 $list ($lines -join "`n")
    $log = Join-Path $OutputDirectory 'formatter.log'
    $code = Invoke-StyleJava @("@$list") $log
    if ($code -ne 0 -or (Select-String -Path $log -Pattern 'Skipping non-Java file')) { throw "Formatting failed or differs. Run ./format.ps1; inspect $log" }
}
