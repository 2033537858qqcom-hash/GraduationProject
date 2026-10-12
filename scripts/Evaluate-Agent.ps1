param([string]$CatalogPath='')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib\AgentEvalReport.ps1')
$evalRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($CatalogPath)) { $CatalogPath=Join-Path $evalRoot '.Codex\evals\agent-v1.json' }
$evalDefinition=Get-AgentEvalDefinition -Path $CatalogPath
$evalBackend=Join-Path $evalRoot 'backend'
foreach ($evalCase in $evalDefinition.cases) {
    $evalSource=Join-Path $evalBackend "src\test\java\cn\zzuit\campus\$($evalCase.testClass).java"
    if (-not (Test-Path -LiteralPath $evalSource) -or (Get-Content -LiteralPath $evalSource -Raw -Encoding utf8) -notmatch ('@Test\s+void\s+'+[regex]::Escape($evalCase.testMethod)+'\s*\(')) { throw "Missing evaluation test: $($evalCase.id)." }
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Maven is required.' }
$evalRunId=[DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)
$evalRunDirectory=Join-Path $evalRoot "output\agent-evals\$evalRunId"
$evalJUnitDirectory=Join-Path $evalBackend "target\agent-evals\$evalRunId"
$null=New-Item -ItemType Directory -Path $evalRunDirectory,$evalJUnitDirectory -Force
$evalSelectors=($evalDefinition.cases | Group-Object testClass | ForEach-Object { $_.Name+'#'+(($_.Group.testMethod) -join '+') }) -join ','
$evalFingerprintFiles=@(Get-ChildItem -LiteralPath (Join-Path $evalBackend 'src') -Recurse -File | Where-Object Extension -In '.java','.sql','.yml')+@(
    Get-Item -LiteralPath (Join-Path $evalBackend 'pom.xml'),$CatalogPath,(Join-Path $PSScriptRoot 'Evaluate-Agent.ps1'),(Join-Path $PSScriptRoot 'lib\AgentEvalReport.ps1')
)
$evalFingerprints=@($evalFingerprintFiles | Sort-Object FullName | ForEach-Object { [ordered]@{file=[IO.Path]::GetRelativePath($evalRoot,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash} })
$evalDigest=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($evalFingerprints | ConvertTo-Json -Depth 4 -Compress))))
$evalStartedAt=[DateTimeOffset]::UtcNow
$evalSavedKey=[Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY','Process')
$evalSavedEnabled=[Environment]::GetEnvironmentVariable('DEEPSEEK_ENABLED','Process')
$evalExitCode=1
Write-Output "Running $($evalDefinition.requiredCount) fixed Agent cases with isolated databases and model fixtures."
Push-Location -LiteralPath $evalBackend
try {
    [Environment]::SetEnvironmentVariable('DEEPSEEK_API_KEY',$null,'Process')
    [Environment]::SetEnvironmentVariable('DEEPSEEK_ENABLED','false','Process')
    & mvn -B -Pagent-eval "-Dtest=$evalSelectors" "-Dagent.eval.reportDirectory=$evalJUnitDirectory" test *> (Join-Path $evalRunDirectory 'maven.log')
    $evalExitCode=$LASTEXITCODE
} catch { Write-Output 'Maven could not finish; the report will remain unsuccessful.' }
finally {
    [Environment]::SetEnvironmentVariable('DEEPSEEK_API_KEY',$evalSavedKey,'Process')
    [Environment]::SetEnvironmentVariable('DEEPSEEK_ENABLED',$evalSavedEnabled,'Process')
    Pop-Location
}
$evalFiles=@(Get-ChildItem -LiteralPath $evalJUnitDirectory -Filter 'TEST-*.xml' -File | Select-Object -ExpandProperty FullName)
$evalReport=Get-AgentEvalReport -Definition $evalDefinition -JUnitFiles $evalFiles -MavenExitCode $evalExitCode
$evalReport | Add-Member -NotePropertyName runId -NotePropertyValue $evalRunId
$evalReport | Add-Member -NotePropertyName startedAtUtc -NotePropertyValue $evalStartedAt.ToString('o')
$evalReport | Add-Member -NotePropertyName completedAtUtc -NotePropertyValue ([DateTimeOffset]::UtcNow.ToString('o'))
$evalReport | Add-Member -NotePropertyName catalogSha256 -NotePropertyValue (Get-FileHash -LiteralPath $CatalogPath -Algorithm SHA256).Hash
$evalReport | Add-Member -NotePropertyName sourceFingerprint -NotePropertyValue $evalDigest
$evalReport | Add-Member -NotePropertyName sourceFiles -NotePropertyValue $evalFingerprints
$evalReport | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $evalRunDirectory 'report.json') -Encoding utf8
$evalSummary=ConvertTo-AgentEvalMarkdown $evalReport
$evalSummary += [Environment]::NewLine+[Environment]::NewLine+"Run: $evalRunId; source fingerprint: $evalDigest."
$evalSummary | Set-Content -LiteralPath (Join-Path $evalRunDirectory 'report.md') -Encoding utf8
foreach ($evalFile in $evalFiles) { Copy-Item -LiteralPath $evalFile -Destination $evalRunDirectory }
Write-Output "Result: $($evalReport.passed)/$($evalReport.total); passedAll=$($evalReport.passedAll)."
Write-Output "Report: $(Join-Path $evalRunDirectory 'report.md')"
if (-not $evalReport.passedAll) { exit 1 }
