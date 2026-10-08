$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & (Join-Path $PSScriptRoot 'style-check.ps1')
    & (Join-Path $PSScriptRoot 'scene-check.ps1')
    Write-Output 'Verification passed. Coverage: readability gate and selected scene/engine suites; run additional tests relevant to your change.'
} finally { Pop-Location }
