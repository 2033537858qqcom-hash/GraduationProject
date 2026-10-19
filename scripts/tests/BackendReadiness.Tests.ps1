$ErrorActionPreference = 'Stop'
$waitScript = Join-Path $PSScriptRoot '..\Wait-Backend.ps1'
$backendProbeState = @{ Calls = 0; Mode = 'UP'; ObservedUrl = '' }

# Probe the launcher without starting Vite or stopping the user's running backend.
function Invoke-RestMethod {
    param([string]$Uri, [int]$TimeoutSec)
    $backendProbeState.Calls++
    $backendProbeState.ObservedUrl = $Uri
    if ($backendProbeState.Mode -eq 'REFUSED' -or ($backendProbeState.Mode -eq 'STARTING' -and $backendProbeState.Calls -eq 1)) { throw 'Connection refused' }
    if ($backendProbeState.Mode -eq 'DOWN') { return @{ status = 'DOWN' } }
    return @{ status = 'UP' }
}

& $waitScript -ApiTarget 'http://127.0.0.1:18080/' -TimeoutSeconds 2 | Out-Null
if ($backendProbeState.Calls -ne 1 -or $backendProbeState.ObservedUrl -ne 'http://127.0.0.1:18080/api/health') { throw '自定义后端端口的就绪探测失败。' }
Write-Output 'PASS 自定义端口与已就绪后端'

$backendProbeState.Mode = 'STARTING'; $backendProbeState.Calls = 0
& $waitScript -TimeoutSeconds 3 | Out-Null
if ($backendProbeState.Calls -ne 2) { throw '后端启动期间的暂时拒绝连接未被正确等待。' }
Write-Output 'PASS 等待后端从拒绝连接恢复'

foreach ($testMode in @('DOWN', 'REFUSED')) {
    $backendProbeState.Mode = $testMode; $backendProbeState.Calls = 0
    $didFail = $false
    try { & $waitScript -TimeoutSeconds 1 | Out-Null } catch { $didFail = $true }
    if (-not $didFail -or $backendProbeState.Calls -lt 1) { throw "未就绪后端 $testMode 不应继续启动前端。" }
    Write-Output "PASS $testMode 超时阻止继续启动"
}

foreach ($badTarget in @('ftp://127.0.0.1', 'http://user:password@127.0.0.1', 'http://127.0.0.1:7080?query=1', 'invalid')) {
    $backendProbeState.Calls = 0; $didFail = $false
    try { & $waitScript -ApiTarget $badTarget -TimeoutSeconds 1 | Out-Null } catch { $didFail = $true }
    if (-not $didFail -or $backendProbeState.Calls -ne 0) { throw '无效后端地址应在发送请求前被拒绝。' }
}
Write-Output 'PASS 无效地址在发送请求前被拒绝'
