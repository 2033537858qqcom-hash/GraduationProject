$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '..\lib\AgentLiveReport.ps1')
$liveTestRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$liveDefinition=Get-AgentLiveDefinition -Path (Join-Path $liveTestRoot '.Codex\evals\agent-live-v1.json')
$liveFixtureDirectory=Join-Path $liveTestRoot ('backend\target\live-report-tests\'+[guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $liveFixtureDirectory -Force
$liveFixturePath=Join-Path $liveFixtureDirectory 'observations.json'
$liveChecks=0
function New-LiveFixture {
    $fixtureCases=@($liveDefinition.cases | ForEach-Object {
        [pscustomobject]@{id=$_.id;group=$_.group;operation=$_.operation;safety=$_.safety;message=$_.message;status='PASSED';errorCode=$null
            modelResponded=$true;toolsMatched=$true;fieldsMatched=$_.operation -ne 'QUERY';durationMs=100;modelDurationMs=90;promptTokens=10;completionTokens=5
            checks=@([pscustomobject]@{scope='MODEL';label='valid-model-response';passed=$true},[pscustomobject]@{scope='TOOLS';label='exact-tool-set';passed=$true},[pscustomobject]@{scope='BOUNDARY';label='business-records-unchanged';passed=$true},[pscustomobject]@{scope='EXECUTION';label='local-check';passed=$true})}
    })
    return [pscustomobject]@{schemaVersion=1;definitionId=$liveDefinition.id;evaluationType='live-model-synthetic';attemptsPerCase=1;temperature=0.2;maxOutputTokens=800;model='fixture-only';referenceDateBeijing='2026-10-12'
        completed=$true;externalModelCalls=$true;attemptedModelCalls=40;successfulModelResponses=40
        businessRecordsBefore=[pscustomobject]@{leave_request=0;certificate_request=1;user_notice=2;agent_session=0;agent_task=0}
        businessRecordsAfter=[pscustomobject]@{leave_request=0;certificate_request=1;user_notice=2;agent_session=0;agent_task=0};cases=$fixtureCases}
}
function Test-LiveFixture {
    param($Fixture,[int]$Code=0)
    $Fixture | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $liveFixturePath -Encoding utf8
    return Get-AgentLiveReport -Definition $liveDefinition -ObservationPath $liveFixturePath -MavenExitCode $Code
}
function Assert-LiveReport {
    param([bool]$Condition,[string]$Name)
    if (-not $Condition) { throw ('Live report check failed: '+$Name) }
    $script:liveChecks++
}
$liveFixture=New-LiveFixture
$liveReport=Test-LiveFixture $liveFixture
Assert-LiveReport ($liveReport.validRun -and $liveReport.allPassed -and $liveReport.meetsTarget -and $liveReport.passed -eq 40) 'complete success'
$liveFixture=New-LiveFixture
$liveFixture.cases[0].status='FAILED';$liveFixture.cases[0].checks[1].passed=$false;$liveFixture.cases[0].toolsMatched=$false
$liveReport=Test-LiveFixture $liveFixture
Assert-LiveReport ($liveReport.validRun -and $liveReport.meetsTarget -and -not $liveReport.allPassed -and $liveReport.passed -eq 39) 'target differs from all passed'
$liveFixture=New-LiveFixture
$liveSafetyCase=$liveFixture.cases | Where-Object safety -eq $true | Select-Object -First 1
$liveSafetyCase.status='FAILED';$liveSafetyCase.checks[3].passed=$false
$liveReport=Test-LiveFixture $liveFixture
Assert-LiveReport ($liveReport.validRun -and -not $liveReport.meetsTarget -and $liveReport.safetyPassed -lt $liveReport.safetyTotal) 'safety must all pass'
$liveFixture=New-LiveFixture
foreach ($liveRow in ($liveFixture.cases | Where-Object safety -eq $false | Select-Object -First 5)) { $liveRow.status='FAILED';$liveRow.checks[1].passed=$false;$liveRow.toolsMatched=$false }
$liveReport=Test-LiveFixture $liveFixture
Assert-LiveReport ($liveReport.validRun -and -not $liveReport.meetsTarget -and $liveReport.passed -eq 35) 'below target'
$liveFixture=New-LiveFixture
$liveFixture.cases=@($liveFixture.cases | Select-Object -Skip 1)
$liveReport=Test-LiveFixture $liveFixture
Assert-LiveReport (-not $liveReport.validRun -and -not $liveReport.meetsTarget -and $liveReport.total -eq 40 -and $liveReport.passed -eq 39) 'missing case retains denominator'
$liveFixture=New-LiveFixture
$liveFixture.cases += $liveFixture.cases[0]
$liveReport=Test-LiveFixture $liveFixture
Assert-LiveReport (-not $liveReport.validRun -and -not $liveReport.allPassed) 'duplicate case'
$liveReport=Test-LiveFixture (New-LiveFixture) 1
Assert-LiveReport (-not $liveReport.validRun -and -not $liveReport.meetsTarget) 'Maven failure'
$liveFixture=New-LiveFixture;$liveFixture.completed=$false
$liveReport=Test-LiveFixture $liveFixture
Assert-LiveReport (-not $liveReport.validRun) 'interrupted run'
$liveFixture=New-LiveFixture;$liveFixture.cases[0].checks[1].passed=$false
$liveReport=Test-LiveFixture $liveFixture
Assert-LiveReport (-not $liveReport.validRun -and $liveReport.passed -eq 39) 'forged pass'
$liveFixture=New-LiveFixture;$liveFixture.cases[0].promptTokens=-1
$liveReport=Test-LiveFixture $liveFixture
Assert-LiveReport (-not $liveReport.validRun) 'negative usage'
$liveFixture=New-LiveFixture
foreach ($liveRow in $liveFixture.cases) { $liveRow.promptTokens=$null;$liveRow.completionTokens=$null }
$liveReport=Test-LiveFixture $liveFixture
Assert-LiveReport ($liveReport.validRun -and $null -eq $liveReport.knownPromptTokens -and $null -eq $liveReport.knownCompletionTokens -and $liveReport.casesWithCompleteUsage -eq 0) 'unknown usage is not zero'
'{' | Set-Content -LiteralPath $liveFixturePath -Encoding utf8
$liveReport=Get-AgentLiveReport -Definition $liveDefinition -ObservationPath $liveFixturePath -MavenExitCode 0
Assert-LiveReport (-not $liveReport.validRun -and $liveReport.passed -eq 0) 'malformed JSON'
$liveReport=Get-AgentLiveReport -Definition $liveDefinition -ObservationPath (Join-Path $liveFixtureDirectory 'absent.json') -MavenExitCode 0
Assert-LiveReport (-not $liveReport.validRun -and $liveReport.passed -eq 0) 'no result'
$liveFixture=New-LiveFixture;$liveFixture.cases[0].id='Z99'
$liveReport=Test-LiveFixture $liveFixture
Assert-LiveReport (-not $liveReport.validRun -and $liveReport.passed -eq 39) 'unexpected case'
$liveFixture=New-LiveFixture;$liveFixture.businessRecordsAfter.user_notice=3
$liveReport=Test-LiveFixture $liveFixture
Assert-LiveReport (-not $liveReport.validRun -and -not $liveReport.businessRecordsUnchanged) 'business side effect'
$liveFixture=New-LiveFixture;$liveFixture.cases[0].status='ERROR';$liveFixture.cases[0].errorCode='MODEL_TIMEOUT';$liveFixture.cases[0].modelResponded=$false;$liveFixture.cases[0].toolsMatched=$false;$liveFixture.cases[0].checks[0].passed=$false;$liveFixture.cases[0].promptTokens=$null;$liveFixture.cases[0].completionTokens=$null;$liveFixture.successfulModelResponses=39
$liveReport=Test-LiveFixture $liveFixture
Assert-LiveReport ($liveReport.validRun -and $liveReport.passed -eq 39 -and $liveReport.casesWithCompleteUsage -eq 39 -and $liveReport.successfulModelResponses -eq 39) 'timeout remains failure'
Write-Output "Agent live report: $liveChecks checks passed, offline fixtures only."
