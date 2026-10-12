param([ValidateRange(1024,65535)][int]$Port = 5173)

$ErrorActionPreference = 'Stop'
$projectDirectory = Join-Path $PSScriptRoot '..\frontend'
if (-not (Get-Command pnpm -ErrorAction SilentlyContinue)) { throw '未找到 pnpm，请先检查 PATH。' }
if (-not (Test-Path -LiteralPath (Join-Path $projectDirectory 'node_modules'))) {
    throw '请先在 frontend 目录执行 pnpm install --frozen-lockfile。'
}
Push-Location -LiteralPath $projectDirectory
try {
    & pnpm run dev --port $Port
    if ($LASTEXITCODE -ne 0) { throw "前端进程退出，代码：$LASTEXITCODE" }
} finally { Pop-Location }
