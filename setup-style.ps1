# Explicit network setup; normal formatting and checks run offline.
. (Join-Path $PSScriptRoot 'tools/style/common.ps1')
New-Item -ItemType Directory -Force $styleCache | Out-Null
foreach ($tool in (Get-Content (Join-Path $PSScriptRoot 'tools/style/tools.json') -Raw | ConvertFrom-Json)) {
    $path = Join-Path $styleCache $tool.file
    if (-not (Test-Path $path) -or (Get-FileHash $path -Algorithm SHA256).Hash -ne $tool.sha256) {
        $download = "$path.download"
        Invoke-WebRequest -UseBasicParsing -Uri $tool.url -OutFile $download
        if ((Get-FileHash $download -Algorithm SHA256).Hash -ne $tool.sha256) { throw "Downloaded checksum mismatch: $($tool.file)" }
        Move-Item -LiteralPath $download -Destination $path -Force
    }
}
Expand-Archive -LiteralPath (Join-Path $styleCache 'pmd.zip') -DestinationPath $styleCache -Force
Assert-StyleTools
Write-Output 'Pinned readability tools are ready.'
