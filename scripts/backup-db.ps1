# ============================================================================
# Jotlog 数据库备份（Windows 用，配任务计划程序每天跑）
#
# 首次用：先把下面的参数改成你自己的（或在系统环境变量里设同名变量）。
# 建计划任务（每天 04:17），用普通 PowerShell 执行：
#   schtasks /Create /TN "Jotlog DB Backup" /SC DAILY /ST 04:17 `
#     /TR "powershell -NoProfile -ExecutionPolicy Bypass -File E:\path\to\Jotlog\scripts\backup-db.ps1"
#
# 产出 .sql.gz，默认留 14 天。只依赖 mysqldump（MySQL 安装目录的 bin 下）。
# ============================================================================

$ErrorActionPreference = 'Stop'

$DbHost   = if ($env:JOTLOG_DB_HOST)     { $env:JOTLOG_DB_HOST }     else { '81.70.233.174' }
$DbPort   = if ($env:JOTLOG_DB_PORT)     { $env:JOTLOG_DB_PORT }     else { '3306' }
$DbName   = if ($env:JOTLOG_DB_NAME)     { $env:JOTLOG_DB_NAME }     else { 'jotlog' }
$DbUser   = if ($env:JOTLOG_DB_USER)     { $env:JOTLOG_DB_USER }     else { 'root' }
$DbPass   = if ($env:JOTLOG_DB_PASSWORD) { $env:JOTLOG_DB_PASSWORD } else { '' }
$BackupDir= if ($env:JOTLOG_BACKUP_DIR)  { $env:JOTLOG_BACKUP_DIR }  else { Join-Path $PSScriptRoot '..\backups' }
$KeepDays = 14

if (-not $DbPass) { throw '没给数据库密码：设 JOTLOG_DB_PASSWORD 环境变量或改本文件' }

# mysqldump 在 PATH 里就用 PATH，否则去常见安装位置找
$dump = Get-Command mysqldump.exe -ErrorAction SilentlyContinue
if (-not $dump) {
    $candidates = @(
        'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe',
        'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqldump.exe'
    )
    $found = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $found) { throw '找不到 mysqldump.exe，把 MySQL 的 bin 目录加进 PATH 或改本文件路径' }
    $dumpExe = $found
} else {
    $dumpExe = $dump.Source
}

New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$sqlOut = Join-Path $BackupDir "jotlog-$stamp.sql"
$gzOut  = "$sqlOut.gz"

# MYSQL_PWD 走环境变量，不进进程参数，任务历史里看不到密码
$env:MYSQL_PWD = $DbPass
try {
    & $dumpExe --host=$DbHost --port=$DbPort --user=$DbUser `
        --single-transaction --routines --triggers --events `
        --set-gtid-purged=OFF --default-character-set=utf8mb4 `
        --result-file="$sqlOut" $DbName
    if ($LASTEXITCODE -ne 0) { throw "mysqldump 失败，退出码 $LASTEXITCODE" }
} finally {
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
}

# 用 .NET GZipStream 压成 .gz（Windows 没有原生 gzip 命令）
$inStream = $null
$outStream = $null
try {
    $inStream = [IO.File]::OpenRead($sqlOut)
    $outStream = [IO.File]::Create($gzOut)
    $gz = New-Object IO.Compression.GzipStream($outStream, [IO.Compression.CompressionLevel]::Optimal)
    $inStream.CopyTo($gz)
    $gz.Dispose()
} finally {
    $inStream?.Dispose()
    $outStream?.Dispose()
}
Remove-Item $sqlOut -Force

$cutoff = (Get-Date).AddDays(-$KeepDays)
Get-ChildItem $BackupDir -Filter 'jotlog-*.sql.gz' |
    Where-Object { $_.LastWriteTime -lt $cutoff } |
    Remove-Item -Force

$left = @(Get-ChildItem $BackupDir -Filter 'jotlog-*.sql.gz').Count
$sizeKb = [math]::Round((Get-Item $gzOut).Length / 1KB)
Write-Host "[OK] $gzOut ($sizeKb KB)，目录现存 $left 份"
