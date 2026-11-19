<#
  push.ps1 —— 一键把当前目录推送到 GitHub

  用法：
    .\push.cmd                                  首次提交并推送（逐步确认）
    .\push.cmd -Yes                             跳过确认（全自动）
    .\push.cmd -Message "fix: 修复 xxx"          自定义提交信息
    .\push.cmd -RemoteUrl https://github.com/xxx/yyy.git
    .\push.cmd -Branch master                   改分支名（默认 main）

  设计原则：
    * 提交前强制做「私密信息体检」，命中就中止，绝不把本机路径/用户名推上公网；
    * 任何破坏性动作（commit / push）默认都要你按键确认，-Yes 才全自动；
    * 幂等：重复运行只推增量，不会重复 init 或重复加 remote。
#>
param(
    [string]$RemoteUrl = 'https://github.com/shifeng0110/idea-dynamic-wallpaper.git',
    [string]$Branch    = 'main',
    [string]$Message   = 'Update: Dynamic Wallpaper plugin for IntelliJ IDEA',
    [switch]$Yes
)

# 注意：脚本里大量调用原生 git，若用 Stop 会把 git 的正常 stderr 提示当致命错误
# （新建仓库时 git rev-parse HEAD 就会报 fatal），故统一 Continue + 显式检查退出码。
$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

function Fail([string]$msg) {
    Write-Host ""
    Write-Host "  ✗ $msg" -ForegroundColor Red
    Write-Host ""
    exit 1
}
function Step([string]$msg) {
    Write-Host ""
    Write-Host "== $msg" -ForegroundColor Cyan
}
function Ask([string]$msg) {
    if ($Yes) { return $true }
    $a = Read-Host "$msg [y/N]"
    return ($a -eq 'y' -or $a -eq 'Y' -or $a -eq 'yes')
}
function GitOut([string[]]$cmdArgs) {
    $out = & git @cmdArgs 2>$null
    return ($out | Out-String).Trim()
}

Write-Host ""
Write-Host "  Dynamic Wallpaper — 推送脚本" -ForegroundColor Green
Write-Host "  目录: $root"

# ---------- 1. 环境检查 ----------
Step "1/7 检查 git"
$gitVer = GitOut @('--version')
if (-not $gitVer -or $gitVer -notmatch 'git version') {
    Fail "未找到 git。请先安装 Git for Windows 并重新打开终端。"
}
Write-Host "  $gitVer"

# ---------- 2. 初始化仓库 ----------
Step "2/7 准备仓库"
if (-not (Test-Path (Join-Path $root '.git'))) {
    & git init -q 2>$null | Out-Null
    Write-Host "  已初始化新仓库 (.git)"
} else {
    Write-Host "  已是 git 仓库，沿用现有配置"
}
# symbolic-ref 在「尚无提交」的新仓库上也能取到分支名（rev-parse HEAD 会 fatal）
$currentBranch = GitOut @('symbolic-ref', '--quiet', '--short', 'HEAD')
if (-not $currentBranch) {
    & git checkout -q -B $Branch 2>$null | Out-Null
    $currentBranch = $Branch
}
Write-Host "  当前分支: $currentBranch"

# ---------- 3. 暂存 ----------
Step "3/7 暂存改动"
& git add -A 2>$null | Out-Null
$staged = @(& git diff --cached --name-only 2>$null | Where-Object { $_ })
if ($staged.Count -eq 0) {
    Write-Host "  没有需要提交的改动" -ForegroundColor Yellow
} else {
    Write-Host "  已暂存 $($staged.Count) 个文件"
}

# ---------- 4. 私密信息体检 ----------
Step "4/7 私密信息体检（命中即中止）"
# 提示词用拼接写，避免本脚本自身被检出
$needles = @(
    ('leno' + 'vo'),
    ('C:\Us' + 'ers\'),
    ('idea' + '壁纸测试'),
    ('java' + 'xuexi'),
    ('dpharness' + 'test'),
    ('ghp' + '_'),
    ('github' + '_pat_')
)
$badFiles = @()
foreach ($f in $staged) {
    $full = Join-Path $root $f
    if (-not (Test-Path $full -PathType Leaf)) { continue }
    if ([IO.Path]::GetExtension($f) -in @('.jar', '.png', '.jpg', '.gif', '.zip', '.ico')) { continue }
    $text = Get-Content $full -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    if (-not $text) { continue }
    foreach ($n in $needles) {
        if ($text.Contains($n)) { $badFiles += "$f  ← 命中私密串"; break }
    }
}
# 构建产物/本机配置绝不能进仓库
foreach ($mustIgnore in @('local.properties', 'build/distributions', '.intellijPlatform')) {
    if (Test-Path (Join-Path $root $mustIgnore)) {
        & git check-ignore -q $mustIgnore 2>$null
        if ($LASTEXITCODE -ne 0) { $badFiles += "$mustIgnore  ← 未被 .gitignore 排除" }
    }
}
if ($badFiles.Count -gt 0) {
    Write-Host ""
    $badFiles | ForEach-Object { Write-Host "  ✗ $_" -ForegroundColor Red }
    Fail "发现不该提交的内容，已中止（未做任何提交）。请修好后重试。"
}
Write-Host "  通过 ✔" -ForegroundColor Green

# ---------- 5. 确认并提交 ----------
# ---------- 4.5 提交身份隐私（commit 里的邮箱会公开） ----------
$cfgEmail = (& git config user.email 2>$null | Out-String).Trim()
if ($cfgEmail -and $cfgEmail -notmatch 'noreply') {
    Write-Host ""
    Write-Host "  当前提交邮箱: $cfgEmail" -ForegroundColor Yellow
    Write-Host "  注意：该邮箱会公开显示在本仓库的每个提交中。" -ForegroundColor Yellow
    $suggest = 'shifeng0110@users.noreply.github.com'
    if (Ask "要把【本仓库】的提交邮箱设为 $suggest 吗？") {
        & git config user.email $suggest 2>$null | Out-Null
        Write-Host "  已设置本仓库提交邮箱: $suggest"
    }
}

Step "5/7 提交"
if ($staged.Count -gt 0) {
    $stat = (& git diff --cached --shortstat 2>$null | Out-String).Trim()
    if ($stat) { Write-Host "  $stat" }
    if (-not (Ask "确认提交以上改动？")) { Fail "已取消（未提交）。" }
    & git commit -q -m $Message 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "提交失败（可能是 user.name/user.email 未配置，执行 git config --global user.name/user.email 后重试）。" }
    Write-Host "  已提交: $Message"
} else {
    Write-Host "  跳过（无改动）"
}

# ---------- 6. 远端 ----------
Step "6/7 配置远端"
$remotes = @(& git remote 2>$null | Where-Object { $_ })
if ($remotes -notcontains 'origin') {
    & git remote add origin $RemoteUrl 2>$null | Out-Null
    Write-Host "  已添加 origin: $RemoteUrl"
} else {
    $existing = GitOut @('remote', 'get-url', 'origin')
    if ($existing -ne $RemoteUrl) {
        Write-Host "  现有 origin: $existing" -ForegroundColor Yellow
        Write-Host "  参数指定:   $RemoteUrl" -ForegroundColor Yellow
        if (Ask "要把 origin 改成参数指定的地址吗？") {
            & git remote set-url origin $RemoteUrl 2>$null | Out-Null
            Write-Host "  已更新 origin"
        }
    } else {
        Write-Host "  origin 已指向目标仓库"
    }
}

# ---------- 7. 推送 ----------
Step "7/7 推送"
$target = GitOut @('remote', 'get-url', 'origin')
if (-not (Ask "确认推送到 $target ($currentBranch)？")) {
    Fail "已取消（未推送）。本地提交已保留，之后可直接 git push。"
}
Write-Host "  （若弹出浏览器登录 GitHub，请完成授权）" -ForegroundColor DarkGray
& git push -u origin $currentBranch
if ($LASTEXITCODE -ne 0) {
    Fail "推送失败。常见原因：①远端仓库还没创建 ②未登录 GitHub ③远端已有不同历史（需 git pull --rebase）。"
}

Write-Host ""
Write-Host "  ✔ 推送完成" -ForegroundColor Green
Write-Host "  仓库: $target"
Write-Host ""
Write-Host "  下一步：把该地址填到 JetBrains Marketplace 的 Source code URL"
Write-Host ""
