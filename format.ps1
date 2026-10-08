. (Join-Path $PSScriptRoot 'tools/style/common.ps1')
Assert-StyleTools
$output = Join-Path $PSScriptRoot 'out/style'
New-Item -ItemType Directory -Force $output | Out-Null
Invoke-StyleFormatter $true $output
Write-Output 'Java formatting applied (google-java-format 1.24.0, AOSP).'
