param(
    [string]$ApiTarget = 'http://127.0.0.1:7080',
    [ValidateRange(1, 300)][int]$TimeoutSeconds = 45
)

$ErrorActionPreference = 'Stop'
$backendUri = $null
if (-not [Uri]::TryCreate($ApiTarget, [UriKind]::Absolute, [ref]$backendUri) -or
    $backendUri.Scheme -notin @('http', 'https') -or $backendUri.UserInfo -or
    $backendUri.Query -or $backendUri.Fragment) {
    throw 'CAMPUS_API_TARGET 必须是有效的 HTTP 或 HTTPS 后端地址，不能包含凭据、查询或片段。'
}
$healthUrl = $ApiTarget.TrimEnd('/') + '/api/health'
$backendWait = [Diagnostics.Stopwatch]::StartNew()
Write-Output "正在等待后端就绪：$healthUrl（最多 $TimeoutSeconds 秒）"
while ($backendWait.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
    try {
        $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 2
        if ($health.status -eq 'UP') {
            Write-Output "后端已就绪：$healthUrl"
            return
        }
    } catch { }
    if ($backendWait.Elapsed.TotalSeconds -lt $TimeoutSeconds) { Start-Sleep -Milliseconds 500 }
}
throw "等待后端就绪超时。请先运行 scripts/Start-Backend.ps1，确认后端启动成功；使用其他端口时，请同步设置 CAMPUS_API_TARGET。"
