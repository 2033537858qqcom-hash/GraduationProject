$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '..\lib\AgentEvalReport.ps1')
$evalTestRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\backend\target\agent-grader-tests'))
$evalFixtureDirectory=Join-Path $evalTestRoot ([guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $evalFixtureDirectory -Force
$evalDefinition=[pscustomobject]@{schemaVersion=1;id='agent-grader-unit-v1';evaluationType='deterministic-regression';requiredCount=2;cases=@(
    [pscustomobject]@{id='CHECK-01';group='A';title='Case A';criterion='Invariant A';testClass='AgentIntegrationTest';testMethod='firstCase'},
    [pscustomobject]@{id='CHECK-02';group='B';title='Case B';criterion='Invariant B';testClass='AgentIntegrationTest';testMethod='secondCase'}
)}
function Assert-Eval([bool]$Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
function Grade-Eval([string]$Name,[string]$Xml,[int]$ExitCode=0) {
    $evalFixture=Join-Path $evalFixtureDirectory "$Name.xml"
    $Xml | Set-Content -LiteralPath $evalFixture -Encoding utf8
    Get-AgentEvalReport -Definition $evalDefinition -JUnitFiles @($evalFixture) -MavenExitCode $ExitCode
}
$evalFirst='<testcase classname="cn.zzuit.campus.AgentIntegrationTest" name="firstCase" time="0.25" />'
$evalSecond='<testcase classname="cn.zzuit.campus.AgentIntegrationTest" name="secondCase" time="0.5" />'
$evalPassing="<testsuite>$evalFirst$evalSecond</testsuite>"
$evalResult=Grade-Eval 'pass' $evalPassing
Assert-Eval ($evalResult.passedAll -and $evalResult.passed -eq 2 -and $evalResult.cases[0].durationSeconds -eq 0.25) 'Valid cases must pass with invariant decimal parsing.'

$evalResult=Grade-Eval 'fail' ('<testsuite>'+$evalFirst+'<testcase classname="cn.zzuit.campus.AgentIntegrationTest" name="secondCase" time="0.5"><failure message="synthetic" /></testcase></testsuite>')
Assert-Eval (-not $evalResult.passedAll -and $evalResult.passed -eq 1 -and $evalResult.cases[1].status -eq 'FAILED') 'An assertion failure must fail the whole report.'

$evalResult=Grade-Eval 'skip' ('<testsuite>'+$evalFirst+'<testcase classname="cn.zzuit.campus.AgentIntegrationTest" name="secondCase"><skipped /></testcase></testsuite>')
Assert-Eval (-not $evalResult.passedAll -and $evalResult.cases[1].status -eq 'SKIPPED') 'Skipped cases must never count as passed.'

$evalResult=Grade-Eval 'error' ('<testsuite>'+$evalFirst+'<testcase classname="cn.zzuit.campus.AgentIntegrationTest" name="secondCase"><error type="SyntheticException" /></testcase></testsuite>')
Assert-Eval (-not $evalResult.passedAll -and $evalResult.cases[1].status -eq 'ERROR') 'JUnit errors must fail the report.'

$evalResult=Grade-Eval 'invalid-time' ('<testsuite>'+$evalFirst+'<testcase classname="cn.zzuit.campus.AgentIntegrationTest" name="secondCase" time="NaN" /></testsuite>')
Assert-Eval (-not $evalResult.passedAll -and $evalResult.cases[1].status -eq 'ERROR') 'Invalid duration metadata must not pass.'

$evalResult=Grade-Eval 'missing' "<testsuite>$evalFirst</testsuite>"
Assert-Eval (-not $evalResult.passedAll -and $evalResult.cases[1].status -eq 'MISSING') 'Missing cases must fail without borrowing old results.'

$evalResult=Grade-Eval 'duplicate' "<testsuite>$evalFirst$evalFirst$evalSecond</testsuite>"
Assert-Eval (-not $evalResult.passedAll -and $evalResult.cases[0].status -eq 'DUPLICATE') 'Duplicate test results must fail.'

$evalResult=Grade-Eval 'build-failure' $evalPassing 1
Assert-Eval (-not $evalResult.passedAll -and $evalResult.passed -eq 2) 'Maven failure must override passing test rows.'

$evalResult=Grade-Eval 'unexpected' ('<testsuite>'+$evalFirst+$evalSecond+'<testcase classname="cn.zzuit.campus.OtherTest" name="unexpected" /></testsuite>')
Assert-Eval (-not $evalResult.passedAll -and $evalResult.unexpectedTests -eq 1) 'Unexpected reports must be visible and fail the run.'

$evalResult=Grade-Eval 'xml-error' '<testsuite><testcase'
Assert-Eval (-not $evalResult.passedAll -and $evalResult.reportErrors.Count -eq 1) 'Malformed XML cannot produce a successful report.'

$evalResult=Grade-Eval 'dtd' '<!DOCTYPE testsuite [<!ENTITY test SYSTEM "http://example.invalid/">]><testsuite>&test;</testsuite>'
Assert-Eval (-not $evalResult.passedAll -and $evalResult.reportErrors.Count -eq 1) 'DTD and external entities must be rejected.'

$evalInvalid=$evalDefinition | ConvertTo-Json -Depth 8 | ConvertFrom-Json
$evalInvalid.cases[1].id=$evalInvalid.cases[0].id
$evalRejected=$false
try { Assert-AgentEvalDefinition $evalInvalid } catch { $evalRejected=$true }
Assert-Eval $evalRejected 'Duplicate case IDs must be rejected before evaluation.'

$evalInvalid=$evalDefinition | ConvertTo-Json -Depth 8 | ConvertFrom-Json
$evalInvalid.requiredCount=3
$evalRejected=$false
try { Assert-AgentEvalDefinition $evalInvalid } catch { $evalRejected=$true }
Assert-Eval $evalRejected 'A reduced catalog cannot silently meet the original required count.'

$evalResult=Get-AgentEvalReport -Definition $evalDefinition -JUnitFiles @()
Assert-Eval (-not $evalResult.passedAll -and $evalResult.passed -eq 0) 'No reports must fail.'
Write-Output 'Agent report grader: 14 checks passed.'
