[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BackupDirectory,
    [string]$TargetDirectory,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$restoreScriptVersion = '1.0.0'
$restoreProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

if ([string]::IsNullOrWhiteSpace($BackupDirectory)) { throw '必须指定 -BackupDirectory（Backup-DemoData.ps1 生成的备份目录）。' }
if (-not $PSBoundParameters.ContainsKey('TargetDirectory') -or $null -eq $TargetDirectory) {
    $TargetDirectory = Join-Path (Join-Path $restoreProjectRoot 'backend') 'data'
}
if ([string]::IsNullOrWhiteSpace($TargetDirectory)) { throw '目标目录不能为空路径。' }

$restoreBackupPath = [IO.Path]::GetFullPath($BackupDirectory)
if (-not (Test-Path -LiteralPath $restoreBackupPath -PathType Container)) {
    throw "备份目录不存在：$restoreBackupPath"
}
$restoreTargetPath = [IO.Path]::GetFullPath($TargetDirectory)

# 路径安全检查：拒绝文件系统根目录、与备份目录相同的目标等危险路径。
$restoreSeparators = [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
$restoreTargetRoot = [IO.Path]::GetPathRoot($restoreTargetPath)
if (-not [string]::IsNullOrEmpty($restoreTargetRoot) -and $restoreTargetPath.TrimEnd($restoreSeparators) -eq $restoreTargetRoot.TrimEnd($restoreSeparators)) {
    throw "目标目录不能是文件系统根目录：$restoreTargetPath"
}
if ($restoreTargetPath.TrimEnd($restoreSeparators) -eq $restoreBackupPath.TrimEnd($restoreSeparators)) {
    throw "目标目录不能与备份目录相同：$restoreTargetPath。请指定独立的恢复目标目录。"
}
if (Test-Path -LiteralPath $restoreTargetPath -PathType Leaf) {
    throw "目标路径是文件而不是目录：$restoreTargetPath"
}

$restoreManifestPath = Join-Path $restoreBackupPath 'manifest.json'
if (-not (Test-Path -LiteralPath $restoreManifestPath -PathType Leaf)) {
    throw "备份目录缺少 manifest.json，拒绝恢复：$restoreBackupPath"
}
try {
    $restoreManifest = Get-Content -LiteralPath $restoreManifestPath -Raw -Encoding utf8 | ConvertFrom-Json
} catch {
    throw "manifest.json 无法解析，拒绝恢复：$restoreManifestPath"
}
if ($null -eq $restoreManifest -or $restoreManifest.schemaVersion -ne 1) {
    throw 'manifest.json 的 schemaVersion 不受支持，拒绝恢复。'
}
$restoreEntries = @($restoreManifest.files)
if ($restoreEntries.Count -eq 0) { throw 'manifest.json 未列出任何文件，拒绝恢复。' }

$restoreNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($restoreEntry in $restoreEntries) {
    $restoreName = [string]$restoreEntry.name
    if ([string]::IsNullOrWhiteSpace($restoreName) -or $restoreName -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$' -or $restoreName -eq '.' -or $restoreName -eq '..') {
        throw "manifest.json 中存在非法文件名，拒绝恢复：$restoreName"
    }
    if (-not $restoreNames.Add($restoreName)) { throw "manifest.json 存在重复文件名，拒绝恢复：$restoreName" }
}
if (-not $restoreNames.Contains('campus.mv.db')) { throw 'manifest.json 未包含 campus.mv.db，拒绝恢复。' }

Write-Output '正在校验 manifest.json 与备份文件哈希（只读取哈希，不读取数据库内容）...'
foreach ($restoreEntry in $restoreEntries) {
    $restoreSourceFile = Join-Path $restoreBackupPath $restoreEntry.name
    if (-not (Test-Path -LiteralPath $restoreSourceFile -PathType Leaf)) {
        throw "备份文件缺失，拒绝恢复：$($restoreEntry.name)"
    }
    $restoreExpectedHash = ([string]$restoreEntry.sha256).ToUpperInvariant()
    if ($restoreExpectedHash -notmatch '^[0-9A-F]{64}$') { throw "manifest.json 中 SHA256 格式无效，拒绝恢复：$($restoreEntry.name)" }
    $restoreActualSize = [long](Get-Item -LiteralPath $restoreSourceFile).Length
    if ($null -ne $restoreEntry.sizeBytes -and $restoreActualSize -ne [long]$restoreEntry.sizeBytes) {
        throw "备份文件大小与 manifest.json 不一致，拒绝恢复：$($restoreEntry.name)"
    }
    $restoreActualHash = (Get-FileHash -LiteralPath $restoreSourceFile -Algorithm SHA256).Hash
    if ($restoreActualHash -ne $restoreExpectedHash) {
        throw "备份文件 SHA256 与 manifest.json 不一致，拒绝恢复：$($restoreEntry.name)"
    }
}
Write-Output '备份清单与备份文件校验通过。'

Write-Output '提示：恢复前请确认后端服务与所有 H2 连接已停止；本脚本不会结束进程，也不会启动服务。'
$restoreExistingFiles = [Collections.Generic.List[string]]::new()
foreach ($restoreEntry in $restoreEntries) {
    $restoreDestination = Join-Path $restoreTargetPath $restoreEntry.name
    if (Test-Path -LiteralPath $restoreDestination -PathType Leaf) { $restoreExistingFiles.Add($restoreDestination) }
}
if ($restoreExistingFiles.Count -gt 0 -and -not $Force) {
    $restoreExistingNames = ($restoreExistingFiles | ForEach-Object { [IO.Path]::GetFileName($_) }) -join '、'
    throw "目标目录已存在同名数据库文件（$restoreExistingNames），拒绝直接覆盖。请先停止后端服务，确认后加 -Force 重试；-Force 仍会先创建带 UTC 时间戳的安全副本。"
}

$null = New-Item -ItemType Directory -Path $restoreTargetPath -Force
$restoreStamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmss') + 'Z'
$restoreSafetyCopies = [Collections.Generic.List[string]]::new()
foreach ($restoreExistingFile in $restoreExistingFiles) {
    $restoreSafetyName = [IO.Path]::GetFileName($restoreExistingFile) + '.before-restore-' + $restoreStamp
    $restoreSafetyPath = Join-Path $restoreTargetPath $restoreSafetyName
    $restoreSafetySuffix = 1
    while (Test-Path -LiteralPath $restoreSafetyPath) {
        $restoreSafetyPath = Join-Path $restoreTargetPath ($restoreSafetyName + '-' + $restoreSafetySuffix)
        $restoreSafetySuffix++
    }
    $restoreOriginalHash = (Get-FileHash -LiteralPath $restoreExistingFile -Algorithm SHA256).Hash
    Copy-Item -LiteralPath $restoreExistingFile -Destination $restoreSafetyPath -Force
    $restoreSafetyHash = (Get-FileHash -LiteralPath $restoreSafetyPath -Algorithm SHA256).Hash
    if ($restoreSafetyHash -ne $restoreOriginalHash) { throw "安全副本校验失败：$restoreSafetyPath" }
    $restoreSafetyCopies.Add($restoreSafetyPath)
    Write-Output "已创建安全副本：$restoreSafetyPath"
}
if ($Force -and $restoreSafetyCopies.Count -gt 0) {
    Write-Output '已使用 -Force：仍保留上述安全副本，未删除任何原有数据库文件。'
}

foreach ($restoreEntry in $restoreEntries) {
    $restoreSourceFile = Join-Path $restoreBackupPath $restoreEntry.name
    $restoreDestination = Join-Path $restoreTargetPath $restoreEntry.name
    Copy-Item -LiteralPath $restoreSourceFile -Destination $restoreDestination -Force
    $restoreRestoredHash = (Get-FileHash -LiteralPath $restoreDestination -Algorithm SHA256).Hash
    if ($restoreRestoredHash -ne ([string]$restoreEntry.sha256).ToUpperInvariant()) {
        throw "恢复后 SHA256 校验失败：$restoreDestination。原有数据库安全副本已保留，请手动回滚。"
    }
    Write-Output "已恢复 $($restoreEntry.name)（$($restoreEntry.sizeBytes) 字节，SHA256 $restoreRestoredHash）"
}

Write-Output '恢复完成：已重新计算目标文件哈希并与 manifest.json 比对一致。'
if ($restoreSafetyCopies.Count -gt 0) { Write-Output ('本次安全副本：' + ($restoreSafetyCopies -join '；')) }
Write-Output '请手动启动后端服务并抽查业务数据；本脚本不会启动服务。'
Write-Output $restoreTargetPath
