param([switch]$PruneBaseline)
. (Join-Path $PSScriptRoot 'tools/style/common.ps1')
. (Join-Path $PSScriptRoot 'tools/style/baseline.ps1')
Assert-StyleTools
$output = Join-Path $PSScriptRoot 'out/style'
New-Item -ItemType Directory -Force $output | Out-Null
Invoke-StyleFormatter $false $output
$checks = Join-Path $output 'checkstyle.xml'
$pmd = Join-Path $output 'pmd.xml'
# Remove previous reports so a failed tool cannot leave a stale success behind.
foreach ($report in @($checks,$pmd)) { if (Test-Path $report) { Remove-Item -LiteralPath $report } }
$checkCode = Invoke-StyleJava @('-jar',(Join-Path $styleCache 'checkstyle.jar'),'-c','tools/style/checkstyle.xml','-f','xml','-o',$checks,'src','tst') (Join-Path $output 'checkstyle.log')
if ($checkCode -lt 0 -or -not (Test-Path $checks) -or (Select-String (Join-Path $output 'checkstyle.log') -Pattern 'Exception')) {
    throw "Checkstyle execution failed ($checkCode). Inspect out/style/checkstyle.log."
}
$pmdCode = Invoke-StyleJava @('-cp',(Join-Path $styleCache 'pmd-bin-7.7.0/lib/*'),'net.sourceforge.pmd.cli.PmdCli','check','-d','src,tst','-R','tools/style/pmd.xml','-f','xml','-r',$pmd,'--use-version','java-23-preview','--no-progress','--no-cache') (Join-Path $output 'pmd.log')
if ($pmdCode -notin @(0,4) -or -not (Test-Path $pmd)) { throw "PMD execution failed ($pmdCode). Inspect out/style/pmd.log." }
$current = @(Get-StyleFindings $checks $pmd)
[xml]$checkReport = Get-Content $checks -Raw
if ($checkReport.SelectNodes('/checkstyle/file').Count -ne @(Get-StyleSources).Count -or
    $checkReport.SelectNodes('/checkstyle/file/error').Count -ne $checkCode) {
    throw 'Incomplete Checkstyle report or inconsistent exit status; inspect out/style/checkstyle.log.'
}
Write-StyleUtf8 (Join-Path $output 'findings.json') (ConvertTo-Json -InputObject $current -Depth 5)
$baselinePath = Join-Path $PSScriptRoot 'tools/style/baseline.json'
if (-not (Test-Path $baselinePath)) { throw 'Missing reviewed baseline: tools/style/baseline.json. Current findings are in out/style/findings.json.' }
$baseline = @(Get-Content $baselinePath -Raw | ConvertFrom-Json)
$comparison = Compare-StyleBaseline $current $baseline
if ($comparison.failures.Count) {
    $comparison.failures | ForEach-Object { Write-Output "$($_.path):$($_.line): $($_.rule): $($_.detail)" }
    throw "$($comparison.failures.Count) new or increased findings. Fix the code; do not regenerate the baseline to hide them."
}
if ($PruneBaseline) {
    # Only shrink allowances. There is deliberately no automatic accept-new switch.
    Write-StyleUtf8 $baselinePath (ConvertTo-Json -InputObject $current -Depth 5)
} elseif ($comparison.stale.Count) {
    throw "$($comparison.stale.Count) resolved/reduced baseline entries. Run ./style-check.ps1 -PruneBaseline and review the baseline diff."
}
Write-Output "Style passed: $($current.Count) tracked existing finding groups; reports in out/style."
