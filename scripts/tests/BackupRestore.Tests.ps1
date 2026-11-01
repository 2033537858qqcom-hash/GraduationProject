$ErrorActionPreference = 'Stop'

$backupScript = Join-Path $PSScriptRoot '../Backup-DemoData.ps1'
$restoreScript = Join-Path $PSScriptRoot '../Restore-DemoData.ps1'
if (-not (Test-Path -LiteralPath $backupScript -PathType Leaf)) { throw "缺少备份脚本：$backupScript" }
if (-not (Test-Path -LiteralPath $restoreScript -PathType Leaf)) { throw "缺少恢复脚本：$restoreScript" }

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('campus-backup-restore-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $testRoot -Force

$script:backupPass = 0
$script:backupFail = 0
$script:defaultBackupDirectories = [Collections.Generic.List[string]]::new()

function Assert-Backup([bool]$Condition, [string]$Name, [string]$Detail = '') {
    if ($Condition) {
        $script:backupPass++
        Write-Output "PASS $Name"
    } else {
        $script:backupFail++
        if ([string]::IsNullOrWhiteSpace($Detail)) { Write-Output "FAIL $Name" } else { Write-Output "FAIL $Name - $Detail" }
    }
}

function Test-ExpectedFailure([scriptblock]$Action) {
    try {
        & $Action | Out-Null
        return $false
    } catch {
        return $true
    }
}

function New-FakeDatabase([string]$Directory, [string]$Name, [int]$SizeBytes = 2048) {
    $null = New-Item -ItemType Directory -Path $Directory -Force
    $path = Join-Path $Directory $Name
    $bytes = [byte[]]::new($SizeBytes)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    [IO.File]::WriteAllBytes($path, $bytes)
    return $path
}

function Get-FileSha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

$realDatabasePath = Join-Path (Join-Path $repoRoot 'backend/data') 'campus.mv.db'
$realDatabaseHashBefore = $null
if (Test-Path -LiteralPath $realDatabasePath -PathType Leaf) { $realDatabaseHashBefore = Get-FileSha256 $realDatabasePath }
$artifactsBackupsRoot = Join-Path (Join-Path $repoRoot 'artifacts') 'backups'
$artifactsRoot = Join-Path $repoRoot 'artifacts'
$artifactsBackupsExistedBefore = Test-Path -LiteralPath $artifactsBackupsRoot
$artifactsExistedBefore = Test-Path -LiteralPath $artifactsRoot
$artifactsBackupsBefore = @(Get-ChildItem -LiteralPath $artifactsBackupsRoot -Force -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)

try {
    # 1. 正常备份：只复制数据库文件，并生成 manifest.json / manifest.md。
    $sourceA = Join-Path $testRoot 'source-a'
    $dbA = New-FakeDatabase $sourceA 'campus.mv.db' 4096
    $traceA = New-FakeDatabase $sourceA 'campus.trace.db' 512
    $null = Set-Content -LiteralPath (Join-Path $sourceA 'not-a-database-secret.txt') -Value 'ignored' -Encoding utf8
    $dbAHash = Get-FileSha256 $dbA

    $backupOutA = @(& $backupScript -SourceDirectory $sourceA -OutputDirectory (Join-Path $testRoot 'backup-a'))
    $backupDirA = ([string]$backupOutA[-1]).Trim()
    Assert-Backup ([IO.Path]::IsPathRooted($backupDirA)) '备份脚本输出绝对路径'
    Assert-Backup (Test-Path -LiteralPath (Join-Path $backupDirA 'manifest.json') -PathType Leaf) '备份生成 manifest.json'
    Assert-Backup (Test-Path -LiteralPath (Join-Path $backupDirA 'manifest.md') -PathType Leaf) '备份生成 manifest.md'

    $manifestA = Get-Content -LiteralPath (Join-Path $backupDirA 'manifest.json') -Raw -Encoding utf8 | ConvertFrom-Json
    $manifestAFiles = @($manifestA.files)
    $backupDbA = Join-Path $backupDirA 'campus.mv.db'
    Assert-Backup ($manifestAFiles.Count -eq 1 -and ([string]$manifestAFiles[0].name) -eq 'campus.mv.db') '默认备份只包含 campus.mv.db（不含 trace）'
    Assert-Backup ((Get-FileSha256 $backupDbA) -eq ([string]$manifestAFiles[0].sha256)) 'manifest SHA256 与备份文件一致'
    Assert-Backup (([string]$manifestAFiles[0].sha256) -eq $dbAHash) '备份副本与源数据库哈希一致'
    Assert-Backup ([long]$manifestAFiles[0].sizeBytes -eq (Get-Item -LiteralPath $backupDbA).Length) 'manifest 记录的文件大小正确'
    Assert-Backup (-not (Test-Path -LiteralPath (Join-Path $backupDirA 'not-a-database-secret.txt'))) '备份不复制非数据库文件'
    $manifestRaw = Get-Content -LiteralPath (Join-Path $backupDirA 'manifest.json') -Raw -Encoding utf8
    Assert-Backup ($manifestRaw -notmatch '(?i)(password|passwd|secret|api[_-]?key|deepseek|jdbc:|token=)') 'manifest 不写入凭据或连接串'
    Assert-Backup (@($manifestA.PSObject.Properties.Name) -contains 'gitHead') 'manifest 含 git HEAD 字段（不可用时为 null）'

    # 2. -IncludeTrace 同时备份 trace 文件。
    $traceOut = @(& $backupScript -SourceDirectory $sourceA -IncludeTrace -OutputDirectory (Join-Path $testRoot 'backup-trace'))
    $traceBackupDir = ([string]$traceOut[-1]).Trim()
    $manifestTrace = Get-Content -LiteralPath (Join-Path $traceBackupDir 'manifest.json') -Raw -Encoding utf8 | ConvertFrom-Json
    $traceNames = @($manifestTrace.files | ForEach-Object { [string]$_.name })
    Assert-Backup ($traceNames.Count -eq 2 -and $traceNames -contains 'campus.trace.db') '-IncludeTrace 同时备份 trace 文件'
    Assert-Backup ((Get-FileSha256 (Join-Path $traceBackupDir 'campus.trace.db')) -eq (Get-FileSha256 $traceA)) '-IncludeTrace 备份的 trace 文件哈希一致'

    # 3. 重复运行默认输出目录：生成新目录，不覆盖。
    $defaultOut1 = @(& $backupScript -SourceDirectory $sourceA)
    $defaultDir1 = ([string]$defaultOut1[-1]).Trim()
    $script:defaultBackupDirectories.Add($defaultDir1)
    $defaultOut2 = @(& $backupScript -SourceDirectory $sourceA)
    $defaultDir2 = ([string]$defaultOut2[-1]).Trim()
    $script:defaultBackupDirectories.Add($defaultDir2)
    Assert-Backup ($defaultDir1 -ne $defaultDir2) '重复运行生成新的默认时间戳目录'
    Assert-Backup ((Test-Path -LiteralPath (Join-Path $defaultDir1 'manifest.json')) -and (Test-Path -LiteralPath (Join-Path $defaultDir2 'manifest.json'))) '两次默认备份的 manifest 均存在'
    Assert-Backup ($defaultDir1 -like '*artifacts*backups*') '默认备份目录位于 artifacts/backups 下'

    # 4. 源目录缺少 campus.mv.db 或不存在时明确报错。
    $missingSource = Join-Path $testRoot 'source-missing'
    $null = New-Item -ItemType Directory -Path $missingSource -Force
    Assert-Backup (Test-ExpectedFailure { & $backupScript -SourceDirectory $missingSource }) '源目录缺少 campus.mv.db 时报错'
    Assert-Backup (Test-ExpectedFailure { & $backupScript -SourceDirectory (Join-Path $testRoot 'source-absent') }) '源目录不存在时报错'

    # 5. 正常恢复到空临时目标并比对哈希。
    $targetEmpty = Join-Path $testRoot 'target-empty'
    $null = & $restoreScript -BackupDirectory $backupDirA -TargetDirectory $targetEmpty
    $restoredDb = Join-Path $targetEmpty 'campus.mv.db'
    Assert-Backup ((Get-FileSha256 $restoredDb) -eq $dbAHash) '正常恢复到临时目标且哈希与源一致'
    Assert-Backup ((Get-FileSha256 $restoredDb) -eq ([string]$manifestAFiles[0].sha256)) '恢复后哈希与 manifest 一致'

    # 6. 篡改备份文件后恢复必须失败。
    $tamperBackupDir = Join-Path $testRoot 'backup-tampered'
    Copy-Item -LiteralPath $backupDirA -Destination $tamperBackupDir -Recurse -Force
    $tamperFilePath = Join-Path $tamperBackupDir 'campus.mv.db'
    [IO.File]::AppendAllBytes($tamperFilePath, [byte[]](0x00, 0xFF))
    $tamperTarget = Join-Path $testRoot 'target-tampered'
    Assert-Backup (Test-ExpectedFailure { & $restoreScript -BackupDirectory $tamperBackupDir -TargetDirectory $tamperTarget }) '篡改备份文件后恢复被拒绝'
    Assert-Backup (-not (Test-Path -LiteralPath $tamperTarget)) '校验失败时不创建目标目录'

    # 7. 篡改 manifest 中的 SHA256 后恢复必须失败。
    $manifestTamperDir = Join-Path $testRoot 'backup-manifest-tampered'
    Copy-Item -LiteralPath $backupDirA -Destination $manifestTamperDir -Recurse -Force
    $manifestTamperPath = Join-Path $manifestTamperDir 'manifest.json'
    $manifestTamper = Get-Content -LiteralPath $manifestTamperPath -Raw -Encoding utf8 | ConvertFrom-Json
    $manifestTamper.files[0].sha256 = ('0' * 64)
    $manifestTamper | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestTamperPath -Encoding utf8
    Assert-Backup (Test-ExpectedFailure { & $restoreScript -BackupDirectory $manifestTamperDir -TargetDirectory (Join-Path $testRoot 'target-manifest-tampered') -Force }) 'manifest 哈希被篡改后恢复被拒绝'

    # 8. 目标已有数据库：默认拒绝；-Force 覆盖前保留带时间戳安全副本。
    $existingTarget = Join-Path $testRoot 'target-existing'
    $existingDb = New-FakeDatabase $existingTarget 'campus.mv.db' 1024
    $existingHash = Get-FileSha256 $existingDb
    Assert-Backup (Test-ExpectedFailure { & $restoreScript -BackupDirectory $backupDirA -TargetDirectory $existingTarget }) '目标已有数据库时未加 -Force 拒绝恢复'
    $safetyAfterRefusal = @(Get-ChildItem -LiteralPath $existingTarget -File | Where-Object { $_.Name -like 'campus.mv.db.before-restore-*' })
    Assert-Backup ($safetyAfterRefusal.Count -eq 0 -and (Get-FileSha256 $existingDb) -eq $existingHash) '拒绝覆盖时不修改目标文件也不生成安全副本'

    $null = & $restoreScript -BackupDirectory $backupDirA -TargetDirectory $existingTarget -Force
    $safetyAfterForce = @(Get-ChildItem -LiteralPath $existingTarget -File | Where-Object { $_.Name -like 'campus.mv.db.before-restore-*' })
    Assert-Backup ($safetyAfterForce.Count -eq 1 -and (Get-FileSha256 $safetyAfterForce[0].FullName) -eq $existingHash) '-Force 覆盖前创建带时间戳安全副本且内容为原数据库'
    Assert-Backup ((Get-FileSha256 $existingDb) -eq $dbAHash) '-Force 恢复后目标数据库哈希与备份一致'

    $null = & $restoreScript -BackupDirectory $backupDirA -TargetDirectory $existingTarget -Force
    $safetySecond = @(Get-ChildItem -LiteralPath $existingTarget -File | Where-Object { $_.Name -like 'campus.mv.db.before-restore-*' })
    Assert-Backup ($safetySecond.Count -eq 2 -and $safetySecond[0].Name -ne $safetySecond[1].Name) '重复恢复生成互不覆盖的安全副本'

    $traceTarget = Join-Path $testRoot 'target-trace'
    $null = & $restoreScript -BackupDirectory $traceBackupDir -TargetDirectory $traceTarget
    Assert-Backup ((Get-FileSha256 (Join-Path $traceTarget 'campus.trace.db')) -eq (Get-FileSha256 $traceA)) '-IncludeTrace 备份可正常恢复 trace 文件'

    # 9. 危险目标路径必须拒绝。
    $rootTarget = [IO.Path]::GetPathRoot([IO.Path]::GetFullPath($testRoot))
    Assert-Backup (Test-ExpectedFailure { & $restoreScript -BackupDirectory $backupDirA -TargetDirectory $rootTarget -Force }) '拒绝恢复到文件系统根目录'
    Assert-Backup (Test-ExpectedFailure { & $restoreScript -BackupDirectory $backupDirA -TargetDirectory '   ' -Force }) '拒绝空白目标目录'
    Assert-Backup (Test-ExpectedFailure { & $restoreScript -BackupDirectory $backupDirA -TargetDirectory $backupDirA -Force }) '拒绝目标目录与备份目录相同'

    # 10. 缺少 manifest.json 或备份文件时必须拒绝。
    $noManifestDir = Join-Path $testRoot 'backup-no-manifest'
    $null = New-Item -ItemType Directory -Path $noManifestDir -Force
    Assert-Backup (Test-ExpectedFailure { & $restoreScript -BackupDirectory $noManifestDir -TargetDirectory (Join-Path $testRoot 'target-no-manifest') -Force }) '缺少 manifest.json 时拒绝恢复'
    $missingFileDir = Join-Path $testRoot 'backup-missing-file'
    Copy-Item -LiteralPath $backupDirA -Destination $missingFileDir -Recurse -Force
    Remove-Item -LiteralPath (Join-Path $missingFileDir 'campus.mv.db') -Force
    Assert-Backup (Test-ExpectedFailure { & $restoreScript -BackupDirectory $missingFileDir -TargetDirectory (Join-Path $testRoot 'target-missing-file') -Force }) '备份文件缺失时拒绝恢复'

    # 11. 全程不得触碰真实演示数据库。
    if ($null -ne $realDatabaseHashBefore) {
        Assert-Backup ((Get-FileSha256 $realDatabasePath) -eq $realDatabaseHashBefore) '全程未修改真实 backend/data/campus.mv.db'
    } else {
        Assert-Backup $true '真实数据库不存在（CI 只读检出），未触碰'
    }
} catch {
    $script:backupFail++
    Write-Output "FAIL 未预期异常 - $($_.Exception.Message)"
} finally {
    foreach ($defaultBackupDirectory in $script:defaultBackupDirectories) {
        if (Test-Path -LiteralPath $defaultBackupDirectory) { Remove-Item -LiteralPath $defaultBackupDirectory -Recurse -Force }
    }
    if (-not $artifactsBackupsExistedBefore -and (Test-Path -LiteralPath $artifactsBackupsRoot) -and @(Get-ChildItem -LiteralPath $artifactsBackupsRoot -Force).Count -eq 0) {
        Remove-Item -LiteralPath $artifactsBackupsRoot -Force
    }
    if (-not $artifactsExistedBefore -and (Test-Path -LiteralPath $artifactsRoot) -and @(Get-ChildItem -LiteralPath $artifactsRoot -Force).Count -eq 0) {
        Remove-Item -LiteralPath $artifactsRoot -Force
    }
    # 兜底清理：删除本次测试在 artifacts/backups 下新建的目录，避免污染仓库。
    foreach ($leftoverDirectory in @(Get-ChildItem -LiteralPath $artifactsBackupsRoot -Force -ErrorAction SilentlyContinue)) {
        if ($artifactsBackupsBefore -notcontains $leftoverDirectory.FullName) { Remove-Item -LiteralPath $leftoverDirectory.FullName -Recurse -Force }
    }
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}

Write-Output "Backup/restore scripts: $($script:backupPass) passed, $($script:backupFail) failed."
if ($script:backupFail -gt 0) { exit 1 }
