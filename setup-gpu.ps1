param([switch]$Offline, [switch]$RuntimeOnly)
$ErrorActionPreference = 'Stop'
$directory = Join-Path $PSScriptRoot 'out/gpu/lib'
New-Item -ItemType Directory -Force $directory | Out-Null
$artifacts = @{
    'lwjgl-jawt-3.3.6.jar' = '2093EE9A645689D43EFD8C4F58DDD424F92A242813D93714642A3A2B45FDBF3E'
    'lwjgl-3.3.6.jar' = 'B00E2781B74CC829DB9D39FB68746B25BB7B94CE61D46293457DBCCDDABD999C'
    'lwjgl-3.3.6-natives-windows.jar' = 'A8D8EDDA34718BF70F68D14DE1295B5BFC0F477A9607A3A9705D9E2D88538A8C'
    'lwjgl-vulkan-3.3.6.jar' = '07501098ACDFE784F55F4F5EA2E99E33E89A1D18128DDBCD41571434B5E848E4'
    'lwjgl-shaderc-3.3.6.jar' = '7BEC76DBEEF91CD0C899FA37F9F120217B422F8050235E5B36BBA41B7BE35F71'
    'lwjgl-shaderc-3.3.6-natives-windows.jar' = 'C8C5629EAC0BB01EA0A145565D54757A9AA8D7FD5FF0436D9069E61C0BFD2662'
}
foreach ($name in $artifacts.Keys) {
    if ($RuntimeOnly -and $name.StartsWith('lwjgl-shaderc-')) { continue }
    $target = Join-Path $directory $name
    if (-not (Test-Path -LiteralPath $target)) {
        if ($Offline) { throw "Missing GPU dependency $name; run ./setup-gpu.ps1 once with network access" }
        $artifact = $name.Substring(0, $name.IndexOf('-3.3.6'))
        Invoke-WebRequest "https://repo.maven.apache.org/maven2/org/lwjgl/$artifact/3.3.6/$name" -OutFile $target
    }
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $artifacts[$name]) { throw "GPU dependency checksum mismatch: $target" }
}
Write-Output 'Pinned GPU dependencies verified (LWJGL 3.3.6, Windows x64).'
