$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib\AgentLiveReport.ps1')
$evalRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$evalCatalog=Join-Path $evalRoot '.Codex\evals\agent-live-v1.json'
$evalDefinition=Get-AgentLiveDefinition -Path $evalCatalog
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Maven is required.' }
$evalRunId=[DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)
$evalDirectory=Join-Path $evalRoot "output\agent-live-evals\$evalRunId"
$evalJUnit=Join-Path $evalRoot "backend\target\agent-live-evals\$evalRunId"
$null=New-Item -ItemType Directory -Path $evalDirectory,$evalJUnit -Force
$evalFiles=@(Get-ChildItem -LiteralPath (Join-Path $evalRoot 'backend\src') -Recurse -File | Where-Object Extension -In '.java','.sql','.yml')+@(
    Get-Item -LiteralPath (Join-Path $evalRoot 'backend\pom.xml'),$evalCatalog,(Join-Path $PSScriptRoot 'Evaluate-AgentLive.ps1'),(Join-Path $PSScriptRoot 'lib\AgentLiveReport.ps1')
)
$evalHashes=@($evalFiles | Sort-Object FullName | ForEach-Object { [ordered]@{file=[IO.Path]::GetRelativePath($evalRoot,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash} })
$evalFingerprint=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($evalHashes | ConvertTo-Json -Depth 4 -Compress))))
$evalSaved=@{}
foreach ($evalName in @('DEEPSEEK_API_KEY','DEEPSEEK_ENABLED','CAMPUS_AGENT_LIVE_EVAL')) { $evalSaved[$evalName]=[Environment]::GetEnvironmentVariable($evalName,'Process') }
$evalCode=1
$evalStarted=[DateTimeOffset]::UtcNow
try {
    if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
        $evalCredential=Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'ZzuitCampus\deepseek-api-key.dpapi'
        if (-not (Test-Path -LiteralPath $evalCredential)) { throw 'Local DeepSeek credential is unavailable.' }
        $evalSecret=[IO.File]::ReadAllText($evalCredential).Trim() | ConvertTo-SecureString
        $evalPointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($evalSecret)
        try { $env:DEEPSEEK_API_KEY=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($evalPointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($evalPointer); $evalSecret.Dispose(); Remove-Variable evalSecret,evalPointer }
    }
    $env:DEEPSEEK_ENABLED='true'
    $env:CAMPUS_AGENT_LIVE_EVAL='true'
    Write-Output 'Running 40 synthetic cases against DeepSeek, one attempt per case, with an isolated in-memory database.'
    Write-Output "Run: $evalRunId"
    Push-Location -LiteralPath (Join-Path $evalRoot 'backend')
    try {
        & mvn -B -Pagent-live-eval "-Dagent.live.reportDirectory=$evalJUnit" "-Dcampus.agent.live.catalog=$evalCatalog" "-Dcampus.agent.live.output=$evalDirectory" test *> (Join-Path $evalDirectory 'maven.log')
        $evalCode=$LASTEXITCODE
    } finally { Pop-Location }
} catch { Write-Output 'Live evaluation could not finish; missing results will remain unsuccessful.' }
finally {
    foreach ($evalName in $evalSaved.Keys) { [Environment]::SetEnvironmentVariable($evalName,$evalSaved[$evalName],'Process') }
    $evalSaved.Clear()
}
$evalReport=Get-AgentLiveReport -Definition $evalDefinition -ObservationPath (Join-Path $evalDirectory 'observations.json') -MavenExitCode $evalCode
$evalReport | Add-Member -NotePropertyName runId -NotePropertyValue $evalRunId
$evalReport | Add-Member -NotePropertyName startedAtUtc -NotePropertyValue $evalStarted.ToString('o')
$evalReport | Add-Member -NotePropertyName completedAtUtc -NotePropertyValue ([DateTimeOffset]::UtcNow.ToString('o'))
$evalReport | Add-Member -NotePropertyName catalogSha256 -NotePropertyValue (Get-FileHash -LiteralPath $evalCatalog -Algorithm SHA256).Hash
$evalReport | Add-Member -NotePropertyName sourceFingerprint -NotePropertyValue $evalFingerprint
$evalReport | Add-Member -NotePropertyName sourceFiles -NotePropertyValue $evalHashes
$evalReport | Add-Member -NotePropertyName mavenExitCode -NotePropertyValue $evalCode
$evalReport | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $evalDirectory 'report.json') -Encoding utf8
$evalMarkdown=ConvertTo-AgentLiveMarkdown -Report $evalReport
$evalMarkdown += [Environment]::NewLine+[Environment]::NewLine+"Run: $evalRunId; source fingerprint: $evalFingerprint."
$evalMarkdown | Set-Content -LiteralPath (Join-Path $evalDirectory 'report.md') -Encoding utf8
Write-Output "Result: $($evalReport.passed)/40; validRun=$($evalReport.validRun); meetsTarget=$($evalReport.meetsTarget); allPassed=$($evalReport.allPassed)."
Write-Output "Report: $(Join-Path $evalDirectory 'report.md')"
if (-not $evalReport.meetsTarget) { exit 1 }
