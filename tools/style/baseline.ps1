function Get-StyleRelativePath([string]$Path) {
    $absolute = if ([IO.Path]::IsPathRooted($Path)) { $Path } else { Join-Path $styleRepository $Path }
    $absolute = [IO.Path]::GetFullPath($absolute)
    $prefix = $styleRepository.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if (-not $absolute.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Report path outside repository: $Path" }
    $absolute.Substring($prefix.Length).Replace('\','/')
}

function Get-StyleFindings([string]$CheckstyleReport, [string]$PmdReport) {
    [xml]$checks = Get-Content -LiteralPath $CheckstyleReport -Raw
    [xml]$pmd = Get-Content -LiteralPath $PmdReport -Raw
    if ($checks.DocumentElement.Name -ne 'checkstyle' -or $pmd.DocumentElement.LocalName -ne 'pmd') {
        throw 'Unexpected analysis report format.'
    }
    if ($pmd.SelectNodes('//*[local-name()="error" or local-name()="configerror"]').Count) {
        throw "PMD parser/configuration failure; inspect $PmdReport"
    }
    $sourceLines = @{}
    $findings = @()
    foreach ($file in $checks.SelectNodes('/checkstyle/file')) {
        $path = Get-StyleRelativePath $file.GetAttribute('name')
        $sourceLines[$path] = [IO.File]::ReadAllLines((Join-Path $styleRepository $path))
        foreach ($errorNode in $file.SelectNodes('error')) {
            $rule = $errorNode.GetAttribute('source').Split('.')[-1]
            if ($rule -notin @('NeedBracesCheck','OneStatementPerLineCheck','MultipleVariableDeclarationsCheck','AvoidStarImportCheck')) {
                throw "Unexpected Checkstyle error: $($errorNode.OuterXml)"
            }
            $line = [int]$errorNode.GetAttribute('line')
            $anchor = ($sourceLines[$path][$line - 1] -replace '\s+', ' ').Trim()
            $detail = $errorNode.GetAttribute('message')
            $findings += [pscustomobject]@{ tool='checkstyle'; path=$path; rule=$rule; anchor=$anchor; detail=$detail; ceiling=1; line=$line }
        }
    }
    foreach ($file in $pmd.SelectNodes('/*[local-name()="pmd"]/*[local-name()="file"]')) {
        $path = Get-StyleRelativePath $file.GetAttribute('name')
        if (-not $sourceLines.ContainsKey($path)) { $sourceLines[$path] = [IO.File]::ReadAllLines((Join-Path $styleRepository $path)) }
        foreach ($violation in $file.SelectNodes('*[local-name()="violation"]')) {
            $rule = $violation.GetAttribute('rule')
            $line = [int]$violation.GetAttribute('beginline')
            $end = [int]$violation.GetAttribute('endline')
            $detail = ($violation.InnerText -replace '\s+', ' ').Trim()
            $ceiling = 1
            if ($rule -in @('CognitiveComplexity','NcssCount')) {
                if ($detail -notmatch ' of (\d+)') { throw "Missing numeric metric: $detail" }
                $ceiling = [int]$Matches[1]
                # The signature in the message distinguishes overloads. Keep threshold text intact.
                $anchor = $violation.GetAttribute('class') + ': ' + ($detail -replace ' of \d+', ' of <metric>')
            } elseif ($rule -in @('ExcessiveParameterList','AvoidDeeplyNestedIfStmts')) {
                # Exact declaration/block anchor: edits to an already exceptional signature or
                # nested block require explicit review, rather than silently increasing its allowance.
                $anchor = $violation.GetAttribute('class') + '.' + $violation.GetAttribute('method') + ': ' +
                    (($sourceLines[$path][($line - 1)..($end - 1)] -join ' ' -replace '\s+', ' ').Trim())
            } else { throw "Unexpected PMD rule: $rule" }
            $findings += [pscustomobject]@{ tool='pmd'; path=$path; rule=$rule; anchor=$anchor; detail=$detail; ceiling=$ceiling; line=$line }
        }
    }
    # Count identical findings too: a new copy cannot consume an existing allowance twice.
    $groups = @{}
    foreach ($finding in $findings) {
        $identity = @($finding.tool,$finding.path,$finding.rule,$finding.anchor) -join "`n"
        $hash = [Security.Cryptography.SHA256]::Create()
        try { $id = [BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($identity))).Replace('-','').ToLowerInvariant() }
        finally { $hash.Dispose() }
        if ($groups.ContainsKey($id)) {
            $groups[$id].count++
            $groups[$id].ceiling = [Math]::Max($groups[$id].ceiling, $finding.ceiling)
        } else {
            $groups[$id] = [pscustomobject][ordered]@{ id=$id; tool=$finding.tool; path=$finding.path; rule=$finding.rule; anchor=$finding.anchor; detail=$finding.detail; ceiling=$finding.ceiling; count=1; line=$finding.line }
        }
    }
    @($groups.Values | Sort-Object path,rule,anchor)
}

function Compare-StyleBaseline([object[]]$Current, [object[]]$Baseline) {
    $old = @{}
    foreach ($entry in $Baseline) {
        if ($old.ContainsKey($entry.id)) { throw "Duplicate baseline identity: $($entry.id)" }
        $old[$entry.id] = $entry
    }
    $seen = @{}
    $failures = @()
    $stale = @()
    foreach ($entry in $Current) {
        $seen[$entry.id] = $true
        $prior = $old[$entry.id]
        if ($null -eq $prior -or $entry.count -gt $prior.count -or $entry.ceiling -gt $prior.ceiling) {
            $failures += $entry
        } elseif ($entry.count -lt $prior.count -or $entry.ceiling -lt $prior.ceiling) {
            $stale += $entry.id
        }
    }
    foreach ($entry in $Baseline) { if (-not $seen.ContainsKey($entry.id)) { $stale += $entry.id } }
    [pscustomobject]@{ failures=@($failures); stale=@($stale) }
}
