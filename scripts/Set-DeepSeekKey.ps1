$ErrorActionPreference = 'Stop'
if (-not [Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::Windows)) { throw '此脚本使用 Windows 当前用户加密，请在 Windows PowerShell 中运行。' }
$agentCredentialDirectory = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'ZzuitCampus'
$agentCredentialPath = Join-Path $agentCredentialDirectory 'deepseek-api-key.dpapi'
$agentSecret = Read-Host '输入本地 DeepSeek API Key（不回显）' -AsSecureString
try {
    if ($agentSecret.Length -lt 20 -or $agentSecret.Length -gt 200) { throw '密钥长度不符合预期，未保存。' }
    $null = New-Item -ItemType Directory -Path $agentCredentialDirectory -Force
    $agentSecret | ConvertFrom-SecureString | Set-Content -LiteralPath $agentCredentialPath -Encoding utf8
    Write-Output 'DeepSeek 密钥已使用 Windows 当前用户加密保存；未写入项目文件。'
} finally { $agentSecret.Dispose() }
