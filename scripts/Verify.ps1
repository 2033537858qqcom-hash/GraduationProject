$ErrorActionPreference = 'Stop'
$projectDirectory = Join-Path $PSScriptRoot '..'

# 自动优先使用 JDK 21（如 Temurin 21）避免受系统低版本 JAVA_HOME 影响
$temurin21 = Join-Path $env:USERPROFILE '.jdks\temurin-21.0.11'
if (Test-Path -LiteralPath $temurin21) {
    $env:JAVA_HOME = $temurin21
    $env:PATH = "$temurin21\bin;$env:PATH"
}

function Invoke-ScriptTest([string]$Path) {
    $global:LASTEXITCODE = 0
    & $Path
    if ($LASTEXITCODE -ne 0) { throw "脚本测试失败：$Path（退出码 $LASTEXITCODE）。" }
}

Invoke-ScriptTest (Join-Path $PSScriptRoot 'tests\AgentEvalReport.Tests.ps1')
Invoke-ScriptTest (Join-Path $PSScriptRoot 'tests\AgentLiveReport.Tests.ps1')
Invoke-ScriptTest (Join-Path $PSScriptRoot 'tests\BackendReadiness.Tests.ps1')
Invoke-ScriptTest (Join-Path $PSScriptRoot 'tests\BackupRestore.Tests.ps1')
Push-Location -LiteralPath (Join-Path $projectDirectory 'backend')
try {
    & mvn -B package
    if ($LASTEXITCODE -ne 0) { throw '后端构建或测试失败。' }
} finally { Pop-Location }
Push-Location -LiteralPath (Join-Path $projectDirectory 'frontend')
try {
    & pnpm test
    if ($LASTEXITCODE -ne 0) { throw '前端草稿恢复测试失败。' }
    & pnpm run lint
    if ($LASTEXITCODE -ne 0) { throw '前端静态检查失败。' }
    & pnpm run build
    if ($LASTEXITCODE -ne 0) { throw '前端类型检查或构建失败。' }
} finally { Pop-Location }
