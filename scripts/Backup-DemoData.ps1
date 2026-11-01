[CmdletBinding()]
param(
    [string]$SourceDirectory,
    [string]$OutputDirectory,
    [switch]$IncludeTrace
)

$ErrorActionPreference = 'Stop'

$backupScriptVersion = '1.0.0'
$backupProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

if ([string]::IsNullOrWhiteSpace($SourceDirectory)) {
    $SourceDirectory = Join-Path (Join-Path $backupProjectRoot 'backend') 'data'
}
$backupSourcePath = [IO.Path]::GetFullPath($SourceDirectory)
if (-not (Test-Path -LiteralPath $backupSourcePath -PathType Container)) {
    throw "源目录不存在：$backupSourcePath。请用 -SourceDirectory 指定包含 campus.mv.db 的目录。"
}
$backupMainDatabase = Join-Path $backupSourcePath 'campus.mv.db'
if (-not (Test-Path -LiteralPath $backupMainDatabase -PathType Leaf)) {
    throw "源目录中未找到 campus.mv.db：$backupSourcePath。请确认 -SourceDirectory，或先启动后端生成演示数据库。"
}

# 输出目录：默认 artifacts/backups/<UTC 时间戳>；重复运行必须生成新目录，不覆盖既有备份。
$backupUsesDefaultOutput = [string]::IsNullOrWhiteSpace($OutputDirectory)
if ($backupUsesDefaultOutput) {
    $backupTimestamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmss') + 'Z'
    $backupOutputPath = Join-Path (Join-Path (Join-Path $backupProjectRoot 'artifacts') 'backups') $backupTimestamp
    while (Test-Path -LiteralPath $backupOutputPath) {
        $backupUniqueSuffix = [guid]::NewGuid().ToString('N').Substring(0, 8)
        $backupOutputPath = Join-Path (Join-Path (Join-Path $backupProjectRoot 'artifacts') 'backups') ($backupTimestamp + '-' + $backupUniqueSuffix)
    }
} else {
    $backupOutputPath = [IO.Path]::GetFullPath($OutputDirectory)
    if (Test-Path -LiteralPath $backupOutputPath) {
        if (-not (Test-Path -LiteralPath $backupOutputPath -PathType Container)) {
            throw "备份输出路径已存在且不是目录：$backupOutputPath"
        }
        if (@(Get-ChildItem -LiteralPath $backupOutputPath -Force).Count -gt 0) {
            throw "备份输出目录非空，拒绝覆盖：$backupOutputPath。请改用新的 -OutputDirectory，或省略参数使用自动时间戳目录。"
        }
    }
}
$null = New-Item -ItemType Directory -Path $backupOutputPath -Force

Write-Output '离线备份开始。请确认后端服务已停止，避免复制到正在写入的 H2 文件。'
Write-Output "源目录：$backupSourcePath"
Write-Output "备份目录：$backupOutputPath"

$backupSourceFiles = [Collections.Generic.List[object]]::new()
foreach ($backupFile in @(Get-ChildItem -LiteralPath $backupSourcePath -File -Filter '*.mv.db' | Sort-Object Name)) {
    $backupSourceFiles.Add($backupFile)
}
if ($IncludeTrace) {
    foreach ($backupFile in @(Get-ChildItem -LiteralPath $backupSourcePath -File -Filter '*.trace.db' | Sort-Object Name)) {
        $backupSourceFiles.Add($backupFile)
    }
}
if (-not @($backupSourceFiles | Where-Object Name -EQ 'campus.mv.db').Count) {
    throw "源目录中未找到 campus.mv.db：$backupSourcePath"
}

$backupEntries = [Collections.Generic.List[object]]::new()
$backupTotalBytes = [long]0
foreach ($backupFile in $backupSourceFiles) {
    $backupDestination = Join-Path $backupOutputPath $backupFile.Name
    $backupSourceHash = (Get-FileHash -LiteralPath $backupFile.FullName -Algorithm SHA256).Hash
    Copy-Item -LiteralPath $backupFile.FullName -Destination $backupDestination -Force
    $backupCopyHash = (Get-FileHash -LiteralPath $backupDestination -Algorithm SHA256).Hash
    $backupCopySize = [long](Get-Item -LiteralPath $backupDestination).Length
    if ($backupSourceHash -ne $backupCopyHash -or $backupCopySize -ne [long]$backupFile.Length) {
        throw "备份副本校验失败：$($backupFile.Name)。请停止后端服务后重试。"
    }
    $backupKind = if ($backupFile.Name -like '*.trace.db') { 'trace' } else { 'database' }
    $backupEntries.Add([ordered]@{
        name = [string]$backupFile.Name
        kind = $backupKind
        sizeBytes = $backupCopySize
        sha256 = $backupCopyHash
        lastWriteTimeUtc = $backupFile.LastWriteTimeUtc.ToString('o')
    })
    $backupTotalBytes += $backupCopySize
    Write-Output ("已备份 {0}（{1} 字节，SHA256 {2}）" -f $backupFile.Name, $backupCopySize, $backupCopyHash)
}

$backupGitHead = $null
if (Get-Command git -ErrorAction SilentlyContinue) {
    try {
        $backupGitOutput = & git -C $backupProjectRoot rev-parse HEAD 2>$null
        if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace(($backupGitOutput | Out-String))) {
            $backupGitHead = [string](@($backupGitOutput)[0]).Trim()
        }
    } catch {
        $backupGitHead = $null
    }
}

$backupManifest = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    script = [ordered]@{
        name = 'Backup-DemoData.ps1'
        version = $backupScriptVersion
        restoreScript = 'Restore-DemoData.ps1'
    }
    sourceDirectory = $backupSourcePath
    includeTrace = [bool]$IncludeTrace
    gitHead = $backupGitHead
    fileCount = $backupEntries.Count
    totalBytes = $backupTotalBytes
    files = @($backupEntries.ToArray())
    notes = @(
        '本清单仅记录文件元数据与 SHA256，不包含数据库内容和任何凭据。',
        '恢复前请停止后端服务；恢复脚本会先校验清单与备份文件哈希。'
    )
}
$backupManifestPath = Join-Path $backupOutputPath 'manifest.json'
$backupManifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $backupManifestPath -Encoding utf8

$backupMarkdown = [Collections.Generic.List[string]]::new()
$backupMarkdown.Add('# 校园演示数据库离线备份清单')
$backupMarkdown.Add('')
$backupMarkdown.Add("生成时间（UTC）：$($backupManifest.generatedAtUtc)")
$backupMarkdown.Add("生成脚本：Backup-DemoData.ps1 v$backupScriptVersion（task-8 工程入口修复：停服备份、校验、恢复脚本）")
$backupMarkdown.Add("Git HEAD：$(if ($null -ne $backupGitHead) { $backupGitHead } else { '不可用（未检测到 git）' })")
$backupMarkdown.Add("源目录：$backupSourcePath")
$backupMarkdown.Add("是否包含 trace 文件：$([bool]$IncludeTrace)")
$backupMarkdown.Add("文件数：$($backupEntries.Count)，总大小：$backupTotalBytes 字节")
$backupMarkdown.Add('')
$backupMarkdown.Add('| 文件 | 类型 | 大小（字节） | SHA256 | 最后修改（UTC） |')
$backupMarkdown.Add('| --- | --- | --- | --- | --- |')
foreach ($backupEntry in $backupEntries) {
    $backupMarkdown.Add("| $($backupEntry.name) | $($backupEntry.kind) | $($backupEntry.sizeBytes) | $($backupEntry.sha256) | $($backupEntry.lastWriteTimeUtc) |")
}
$backupMarkdown.Add('')
$backupMarkdown.Add('## 恢复方法')
$backupMarkdown.Add('')
$backupMarkdown.Add('1. 停止后端服务，确认没有进程占用演示数据库文件（脚本不会结束进程或启动服务）。')
$backupMarkdown.Add('2. 运行：pwsh -NoProfile -File scripts/Restore-DemoData.ps1 -BackupDirectory <本目录> -TargetDirectory backend/data')
$backupMarkdown.Add('3. 目标目录已有同名库文件时必须显式加 -Force；脚本仍会先创建 <文件名>.before-restore-<UTC时间戳> 安全副本。')
$backupMarkdown.Add('4. 恢复完成后手动启动后端服务并抽查业务数据。')
$backupMarkdown.Add('')
$backupMarkdown.Add('本清单只记录备份文件元数据与哈希，不记录、不输出任何数据库内容或凭据。')
$backupMarkdownPath = Join-Path $backupOutputPath 'manifest.md'
$backupMarkdown | Set-Content -LiteralPath $backupMarkdownPath -Encoding utf8

Write-Output "manifest.json：$backupManifestPath"
Write-Output "manifest.md：$backupMarkdownPath"
Write-Output "备份完成：$($backupEntries.Count) 个文件，共 $backupTotalBytes 字节。"
Write-Output $backupOutputPath
