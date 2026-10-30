[CmdletBinding()]
param(
    [ValidateRange(1024,65535)][int]$FrontendPort = 5173,
    [ValidateRange(1024,65535)][int]$BackendPort = 7080,
    [switch]$SkipServices,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskChecks = [System.Collections.Generic.List[object]]::new()
function Add-ReadinessCheck([string]$Name, [bool]$Passed, [string]$Detail) {
    $taskChecks.Add([pscustomobject]@{ name = $Name; passed = $Passed; detail = $Detail })
    $taskLabel = if ($Passed) { 'PASS' } else { 'FAIL' }
    Write-Output "[$taskLabel] $Name - $Detail"
}

foreach ($taskCommand in @('node', 'pnpm', 'mvn', 'java')) {
    $taskExecutable = Get-Command $taskCommand -ErrorAction SilentlyContinue
    Add-ReadinessCheck "command:$taskCommand" ([bool]$taskExecutable) $(if ($taskExecutable) { 'Available on PATH' } else { 'Missing from PATH' })
}
if (Get-Command node -ErrorAction SilentlyContinue) {
    $taskNodeVersion = (& node -p 'process.versions.node').Trim()
    $taskNodeExit = $LASTEXITCODE
    Add-ReadinessCheck 'node-version' ($taskNodeExit -eq 0 -and [version]$taskNodeVersion -ge [version]'22.12.0') "Node $taskNodeVersion; requires >=22.12.0"
}
if (Get-Command java -ErrorAction SilentlyContinue) {
    $taskJavaVersion = (& java -version 2>&1 | Out-String)
    $taskJavaMatch = [regex]::Match($taskJavaVersion, 'version\s+"(\d+)')
    $taskJavaMajor = if ($taskJavaMatch.Success) { [int]$taskJavaMatch.Groups[1].Value } else { 0 }
    Add-ReadinessCheck 'java-version' ($taskJavaMajor -ge 21) "Java major $taskJavaMajor; requires >=21; startup script may select its configured JDK 21"
}
foreach ($taskRelativePath in @('frontend/node_modules', 'frontend/pnpm-lock.yaml', 'backend/pom.xml', 'frontend/src/router.ts')) {
    Add-ReadinessCheck "file:$taskRelativePath" (Test-Path -LiteralPath (Join-Path $taskRoot $taskRelativePath)) $taskRelativePath
}

$taskMigrations = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'backend/src/main/resources/db/migration') -File |
    Where-Object { $_.Name -match '^V\d+__.+\.sql$' } |
    Sort-Object { [int]([regex]::Match($_.Name, '^V(\d+)').Groups[1].Value) })
$taskLatestMigration = if ($taskMigrations.Count) { $taskMigrations[-1].Name } else { '' }
Add-ReadinessCheck 'migration-files' ($taskMigrations.Count -gt 0) "$($taskMigrations.Count) files; latest source migration: $taskLatestMigration; live database version not checked"

$taskRevision = ''
$taskDirtyCount = $null
if (Get-Command git -ErrorAction SilentlyContinue) {
    $taskRevisionOutput = & git -C $taskRoot rev-parse --short HEAD 2>$null
    if ($LASTEXITCODE -eq 0) {
        $taskRevision = [string]$taskRevisionOutput
        $taskDirtyCount = @(& git -C $taskRoot status --porcelain).Count
    }
}

if (-not $SkipServices) {
    foreach ($taskPort in @($BackendPort, $FrontendPort)) {
        $taskName = if ($taskPort -eq $BackendPort) { 'backend-health' } else { 'frontend-api-proxy' }
        try {
            $taskHealth = Invoke-RestMethod -Uri "http://127.0.0.1:$taskPort/api/health" -TimeoutSec 5
            Add-ReadinessCheck $taskName ($taskHealth.status -eq 'UP') "127.0.0.1:$taskPort/api/health"
        } catch { Add-ReadinessCheck $taskName $false "127.0.0.1:$taskPort/api/health is unavailable" }
    }
    try {
        $taskPage = Invoke-WebRequest -Uri "http://127.0.0.1:$FrontendPort/" -TimeoutSec 5
        Add-ReadinessCheck 'frontend-entry' ($taskPage.StatusCode -eq 200 -and $taskPage.Content -match '<div\s+id="app"') 'HTTP entry contains the Vue mount point; browser execution not checked'
    } catch { Add-ReadinessCheck 'frontend-entry' $false "127.0.0.1:$FrontendPort is unavailable" }
}

$taskPending = @(
    'Third-batch desktop/mobile interaction, WebGL fallback and final screenshots still require browser verification.',
    'The running backend build, data source path and live migration version must be checked against its startup log.',
    'This command does not run unit tests, build artifacts, log in, read credentials, call an external model, or write business records.'
)
$taskReport = [ordered]@{
    generatedAt = [DateTimeOffset]::UtcNow.ToString('o')
    projectRoot = $taskRoot
    revision = $taskRevision
    workingTreeChangeCount = $taskDirtyCount
    frontendPort = $FrontendPort
    backendPort = $BackendPort
    servicesChecked = -not [bool]$SkipServices
    checks = @($taskChecks.ToArray())
    failedChecks = @($taskChecks | Where-Object { -not $_.passed }).Count
    manualChecks = $taskPending
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory = Join-Path $taskRoot 'artifacts/demo-readiness' }
$taskOutput = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
$taskReportPath = Join-Path $taskOutput ('readiness-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff') + '-' + [guid]::NewGuid().ToString('N').Substring(0,6) + '.json')
$taskReport | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $taskReportPath -Encoding utf8
Write-Output "Report: $taskReportPath"
Write-Output 'Manual review remains required; this is a read-only environment and HTTP preflight.'
if ($taskReport.failedChecks -gt 0) { exit 1 }
