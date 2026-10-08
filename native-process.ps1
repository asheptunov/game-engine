function Invoke-NativeLogged {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$LogPath,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    Get-Command -Name $FilePath -CommandType Application -ErrorAction Stop | Out-Null
    $nativeExitCode = $null
    $previousPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5.1 wraps native stderr as non-terminating ErrorRecords.
        # Keep those diagnostics in the merged log without letting "Stop" abort a
        # successful compiler, while making log-file failures terminate explicitly.
        $ErrorActionPreference = "Continue"
        & $FilePath @Arguments 2>&1 |
            ForEach-Object { $_.ToString() } |
            Tee-Object -FilePath $LogPath -ErrorAction Stop
        $nativeExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }

    if ($null -eq $nativeExitCode) { throw "$FailureMessage because the process did not report an exit code" }
    if ($nativeExitCode -ne 0) { throw "$FailureMessage with exit code $nativeExitCode" }
}
