# Regression checks for the gate itself. All mutations occur in an isolated out/ fixture.
. (Join-Path $PSScriptRoot 'tools/style/common.ps1')
. (Join-Path $PSScriptRoot 'tools/style/baseline.ps1')
Assert-StyleTools
$testRoot = Join-Path $PSScriptRoot ('out/style-tests/' + [Guid]::NewGuid().ToString('N'))
$fixture = Join-Path $testRoot 'repository with spaces'
New-Item -ItemType Directory -Force "$fixture/src","$fixture/tst","$fixture/tools/style","$fixture/out/style-tools" | Out-Null
Copy-Item "$PSScriptRoot/tools/style/*.ps1","$PSScriptRoot/tools/style/*.xml","$PSScriptRoot/tools/style/tools.json" "$fixture/tools/style"
Copy-Item "$PSScriptRoot/format.ps1","$PSScriptRoot/style-check.ps1" $fixture
Copy-Item "$styleCache/*" "$fixture/out/style-tools" -Recurse
$shell = (Get-Process -Id $PID).Path
$passed = 0

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "Gate regression: $Message" }
}

function Invoke-Fixture([string]$Script, [bool]$Success, [string[]]$Extra = @()) {
    $log = Join-Path $testRoot ("{0}-{1}.log" -f $script:passed, $Script)
    $savedJava = $script:styleJava
    $script:styleJava = $shell
    try {
        $exitCode = Invoke-StyleJava (@('-NoProfile','-ExecutionPolicy','Bypass','-File',"$fixture/$Script") + $Extra) $log
    } finally { $script:styleJava = $savedJava }
    Assert-Condition (($exitCode -eq 0) -eq $Success) "Unexpected exit $exitCode for $Script; inspect $log"
    $script:passed++
}

$source = "$fixture/src/Example.java"
Write-StyleUtf8 "$fixture/src/settings.txt" 'Non-Java resources must not affect coverage checks.'
Write-StyleUtf8 $source 'class Example { int value(boolean enabled) { if (enabled) return 1; return 0; } }'
Write-StyleUtf8 "$fixture/tools/style/baseline.json" '[]'
Invoke-Fixture 'style-check.ps1' $false # formatting drift
Invoke-Fixture 'format.ps1' $true
$firstHash = (Get-FileHash $source).Hash
Invoke-Fixture 'format.ps1' $true
Assert-Condition ((Get-FileHash $source).Hash -eq $firstHash) 'Formatter is not idempotent.'
Write-StyleUtf8 $source ([IO.File]::ReadAllText($source).Replace("`n", "`r`n"))
Invoke-Fixture 'style-check.ps1' $false # Windows line endings must normalize too
Invoke-Fixture 'format.ps1' $true
Assert-Condition ((Get-FileHash $source).Hash -eq $firstHash) 'LF normalization changed Java content.'
Invoke-Fixture 'style-check.ps1' $false # formatted code still violates NeedBraces
$fixtureFindings = @(Get-Content "$fixture/out/style/findings.json" -Raw | ConvertFrom-Json)
Assert-Condition ($fixtureFindings.Count -eq 1 -and $fixtureFindings[0].rule -eq 'NeedBracesCheck') 'Expected the actual Checkstyle finding.'
Copy-Item "$fixture/out/style/findings.json" "$fixture/tools/style/baseline.json"
Invoke-Fixture 'style-check.ps1' $true # accepted initial debt
Write-StyleUtf8 $source ("// A comment shifts line numbers without changing the finding.`n" + [IO.File]::ReadAllText($source))
Invoke-Fixture 'format.ps1' $true
Invoke-Fixture 'style-check.ps1' $true # location stability, even in a path with spaces
Write-StyleUtf8 $source 'class Example { int value(boolean enabled) { if (enabled) { return 1; } return 0; } }'
Invoke-Fixture 'format.ps1' $true
Invoke-Fixture 'style-check.ps1' $false # stale baseline requires explicit pruning
Invoke-Fixture 'style-check.ps1' $true @('-PruneBaseline')
Assert-Condition (@(Get-Content "$fixture/tools/style/baseline.json" -Raw | ConvertFrom-Json).Count -eq 0) 'Resolved baseline was not pruned.'
# Exercise real PMD findings and metric growth, not just the comparator.
function Write-ComplexFixture([int]$Branches) {
    $statements = @(1..$Branches | ForEach-Object { "if (value == $_) { result++; }" }) -join "`n"
    Write-StyleUtf8 $source "class Example { int score(int value) { int result = 0; $statements return result; } }"
}
Write-ComplexFixture 16
Invoke-Fixture 'format.ps1' $true
Invoke-Fixture 'style-check.ps1' $false
$metrics = @(Get-Content "$fixture/out/style/findings.json" -Raw | ConvertFrom-Json)
Assert-Condition ($metrics.Count -eq 1 -and $metrics[0].rule -eq 'CognitiveComplexity' -and $metrics[0].ceiling -eq 16) 'Expected actual PMD cognitive complexity of 16.'
Copy-Item "$fixture/out/style/findings.json" "$fixture/tools/style/baseline.json"
Write-ComplexFixture 17
Invoke-Fixture 'format.ps1' $true
Invoke-Fixture 'style-check.ps1' $false @('-PruneBaseline')
Assert-Condition ((Get-Content "$fixture/tools/style/baseline.json" -Raw | ConvertFrom-Json).ceiling -eq 16) 'Pruning enlarged a baseline allowance.'
Write-ComplexFixture 15
Invoke-Fixture 'format.ps1' $true
Invoke-Fixture 'style-check.ps1' $true @('-PruneBaseline')
Assert-Condition ((Get-Content "$fixture/tools/style/baseline.json" -Raw | ConvertFrom-Json).ceiling -eq 15) 'Pruning did not lower the actual PMD ceiling.'
Write-StyleUtf8 $source 'class Example { not valid Java! }'
Invoke-Fixture 'style-check.ps1' $false # parser failure, never baseline it
Move-Item "$fixture/out/style-tools/formatter.jar" "$fixture/out/style-tools/formatter.missing"
Invoke-Fixture 'style-check.ps1' $false # missing tool
Move-Item "$fixture/out/style-tools/formatter.missing" "$fixture/out/style-tools/formatter.jar"
Write-StyleUtf8 "$fixture/out/style-tools/formatter.jar" 'corrupted tool'
Invoke-Fixture 'style-check.ps1' $false # checksum failure

# Metrics may shrink, but may not grow or consume an allowance multiple times.
$old = [pscustomobject]@{ id='method'; count=1; ceiling=20; line=10 }
$higher = [pscustomobject]@{ id='method'; count=1; ceiling=21; line=90 }
$lower = [pscustomobject]@{ id='method'; count=1; ceiling=19; line=90 }
$duplicate = [pscustomobject]@{ id='method'; count=2; ceiling=20; line=90 }
Assert-Condition ((Compare-StyleBaseline @($higher) @($old)).failures.Count -eq 1) 'Increased complexity escaped.'
Assert-Condition ((Compare-StyleBaseline @($lower) @($old)).stale.Count -eq 1) 'Reduced complexity did not tighten the baseline.'
Assert-Condition ((Compare-StyleBaseline @($duplicate) @($old)).failures.Count -eq 1) 'A copied violation reused an allowance.'

# Run actual harness mains as child processes, rather than testing only log text.
$classes = Join-Path $testRoot 'classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$passing = Join-Path $testRoot 'PassingSuite.java'
$failing = Join-Path $testRoot 'FailingSuite.java'
Write-StyleUtf8 $passing 'public class PassingSuite { @harness.Test void passes() {} public static void main(String[] args) { harness.SuiteRunner.runThis(); } }'
Write-StyleUtf8 $failing 'public class FailingSuite { @harness.Test void fails() { throw new AssertionError("intentional gate test"); } public static void main(String[] args) { harness.SuiteRunner.runThis(); } }'
$sources = @((Get-ChildItem "$PSScriptRoot/src/logging","$PSScriptRoot/tst/harness" -Filter '*.java' -Recurse).FullName) + @($passing,$failing)
$savedJava = $styleJava
$styleJava = Join-Path $styleJavaHome 'bin/javac.exe'
try { $code = Invoke-StyleJava (@('--enable-preview','--release','23','-d',$classes) + $sources) "$testRoot/compile.log" }
finally { $styleJava = $savedJava }
Assert-Condition ($code -eq 0) "Harness fixtures failed to compile; inspect $testRoot/compile.log"
foreach ($suite in @('PassingSuite','FailingSuite')) {
    $code = Invoke-StyleJava @('--enable-preview','-cp',$classes,$suite) "$testRoot/$suite.log"
    Assert-Condition (($code -eq 0) -eq ($suite -eq 'PassingSuite')) "Wrong process status for $suite"
}
Write-Output "Gate regression checks passed ($passed command cases, baseline comparisons, passing/failing harness processes). Logs: $testRoot"
