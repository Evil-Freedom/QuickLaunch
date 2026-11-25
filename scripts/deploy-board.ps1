# 运营部看板一键发布：本地打包 → VPS
#
# 用法（在 QuickLaunch 仓库根目录）：
#   .\scripts\deploy-board.ps1
#   .\scripts\deploy-board.ps1 -SkipTests
#
# 做的事：跑单测 → 打包（排除凭据与运行时数据）→ scp 到 VPS /tmp。
# 不会自动重启服务：改动要人工确认后再执行 deploy-board.sh。
param(
    [string]$BoardDir = "C:\Users\Administrator\WorkBuddy\2026-10-29-16-23-19\team-desk-board",
    [string]$VpsHost  = "root@xbyyb.duckdns.org",
    [string]$Key      = "$env:USERPROFILE\.ssh\ql-vps.pem",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$stage = Join-Path $repo "backups\_stage"
$tar   = Join-Path $repo "backups\board-app.tgz"

if (-not (Test-Path $BoardDir)) { throw "看板目录不存在：$BoardDir" }
if (-not (Test-Path $Key))      { throw "SSH 私钥不存在：$Key" }

if (-not $SkipTests) {
    Write-Host "▶ 运行单测" -ForegroundColor Cyan
    Push-Location $BoardDir
    python -m unittest discover -s tests -t . 2>&1 | Select-Object -Last 3
    if ($LASTEXITCODE -ne 0) { Pop-Location; throw "单测未通过，已中止发布" }
    Pop-Location
}

Write-Host "▶ 打包（排除 .git / board.db / config.json / logs / 缓存）" -ForegroundColor Cyan
if (Test-Path $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage | Out-Null

robocopy $BoardDir $stage /E `
    /XD ".git" "tunnel" "logs" "__pycache__" "_local" ".gradle" `
    /XF "board.db" "config.json" "*.pyc" "*.log" | Out-Null

if (Test-Path $tar) { Remove-Item -LiteralPath $tar -Force }
tar -czf $tar -C $stage .
if ($LASTEXITCODE -ne 0) { throw "打包失败" }

$size = [math]::Round((Get-Item $tar).Length / 1KB)
Write-Host "  包大小：${size} KB"

Write-Host "▶ 上传到 $VpsHost:/tmp/board-app.tgz" -ForegroundColor Cyan
scp -o BatchMode=yes -i $Key $tar "${VpsHost}:/tmp/board-app.tgz"
if ($LASTEXITCODE -ne 0) { throw "上传失败" }

Write-Host "`n✅ 已上传。下一步在 VPS 上执行：" -ForegroundColor Green
Write-Host "   bash /opt/team-desk-board/scripts/deploy-board.sh /tmp/board-app.tgz" -ForegroundColor Yellow
