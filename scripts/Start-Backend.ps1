$ErrorActionPreference = 'Stop'
$projectDirectory = Join-Path $PSScriptRoot '..\backend'

# 自动优先使用 JDK 21（如 Temurin 21）
$temurin21 = Join-Path $env:USERPROFILE '.jdks\temurin-21.0.11'
if (Test-Path -LiteralPath $temurin21) {
    $env:JAVA_HOME = $temurin21
    $env:PATH = "$temurin21\bin;$env:PATH"
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw '未找到 Maven，请先检查 PATH。' }
$agentCredentialPath = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'ZzuitCampus\deepseek-api-key.dpapi'
$agentLoadedKey = $false
$agentPreviousEnabled = $env:DEEPSEEK_ENABLED
if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY) -and (Test-Path -LiteralPath $agentCredentialPath)) {
    try { $agentSecret = [System.IO.File]::ReadAllText($agentCredentialPath).Trim() | ConvertTo-SecureString }
    catch { throw '无法读取当前用户的本地 DeepSeek 凭据，请重新运行 Set-DeepSeekKey.ps1。' }
    $agentSecretPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($agentSecret)
    try { $env:DEEPSEEK_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($agentSecretPtr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($agentSecretPtr); $agentSecret.Dispose(); Remove-Variable agentSecret, agentSecretPtr }
    $agentLoadedKey = $true
    if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_ENABLED)) { $env:DEEPSEEK_ENABLED = 'true' }
    Write-Output '已读取本地加密凭据；DeepSeek 是否启用遵循后端配置。'
}
Push-Location -LiteralPath $projectDirectory
try {
    & mvn spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw "后端进程退出，代码：$LASTEXITCODE" }
} finally {
    Pop-Location
    if ($agentLoadedKey) { Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue; $env:DEEPSEEK_ENABLED = $agentPreviousEnabled }
}
