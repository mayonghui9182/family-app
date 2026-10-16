#Requires -Version 5.1
<#
.SYNOPSIS
  family-app unified build / package / image / deploy / runtime control script.
  Windows (PowerShell 5.1+) version — Linux 原版: scripts/dev.sh

.DESCRIPTION
  适用版本：Change 13（日程/日历；含 Change 9 移动端 / Change 11 微信公众号入口 / Change 12 模板推送）
    - V10 Flyway migration 自动创建 wechat_bind 表（Change 11）
    - V11 Flyway migration 自动创建 calendar_events 表（Change 13）
    - 7 个容器：nginx / postgres / backend / frontend / tool-service / agent-service / wechat-service
    - 新增环境变量（.env.example 段5）：WECHAT_APP_ID/SECRET/TOKEN/OAUTH_BASE（Change 11）
      + WECHAT_NOTIFY_ENABLED / WECHAT_TPL_TASK_DIGEST_ID / WECHAT_TPL_SUMMARY_ID
      / WECHAT_TPL_TASK_ASSIGN_ID（Change 12）/ WECHAT_TPL_EVENT_DIGEST_ID（Change 13）
      （四个模板 ID 留空即对应推送事件静默跳过，preflight 计数分母为 4）

  One-stop entry covering compile, package, docker image build, compose
  start/stop/restart/logs/status, interactive shells, and cleanup.

  Usage examples:
    .\scripts\dev.ps1 help
    .\scripts\dev.ps1 package
    .\scripts\dev.ps1 build-images
    .\scripts\dev.ps1 up
    .\scripts\dev.ps1 down
    .\scripts\dev.ps1 logs backend

  All commands execute relative to the repository root (parent of scripts\).
#>

[CmdletBinding(DefaultParameterSetName='Command')]
param(
  [Parameter(ParameterSetName='Command', Position=0)]
  [ValidateSet(
    'help','preflight',
    'compile','package','test',
    'build-images','build-backend-image','build-frontend-image',
    'up','down','restart','status',
    'logs','db-shell','backend-logs','frontend-logs',
    'clean','purge',
    'e2e','e2e-flyway','e2e-config'
  )]
  [string]$Command = 'help',

  [Parameter(ParameterSetName='Command', Position=1, ValueFromRemainingArguments=$true)]
  [string[]]$CommandArgs = @(),

  # === up / build-images ===
  [switch]$NoCache,
  [switch]$Quick,           # up: skip docker compose build

  # === down ===
  [switch]$PurgeData,       # down: also delete .volumes/postgres (DANGER!)
  [switch]$Force,           # down -PurgeData: skip confirmation prompt (CI)

  # === purge ===
  [switch]$PurgeContainers, # purge: also prune stopped containers
  [switch]$PurgeImages,     # purge: prune dangling/unused images
  [switch]$PurgeAll         # purge: enable everything + answer yes to prompts
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentContinue'
$OutputEncoding        = [System.Text.Encoding]::UTF8

# ---------------------------------------------------------------------------
# Paths (always resolve relative to the repository root)
# ---------------------------------------------------------------------------
$REPO_ROOT = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$BACKEND_DIR = Join-Path $REPO_ROOT 'backend'
$FRONTEND_DIR = Join-Path $REPO_ROOT 'frontend'
$COMPOSE_FILE = Join-Path $REPO_ROOT 'docker-compose.yml'
$VOLUMES_DIR  = Join-Path $REPO_ROOT '.volumes'
$PG_DATA_DIR  = Join-Path $VOLUMES_DIR 'postgres'

Set-Location $REPO_ROOT

# ---------------------------------------------------------------------------
# .env.example -> .env 自动复制（首次部署友好）
# ---------------------------------------------------------------------------
$envFile = Join-Path $REPO_ROOT '.env'
$envExample = Join-Path $REPO_ROOT '.env.example'
if ((-not (Test-Path $envFile)) -and (Test-Path $envExample)) {
  Copy-Item $envExample $envFile
  Write-Host "[+] 首次部署：已从 .env.example 复制到 .env（请按需修改密码和密钥）" -ForegroundColor Cyan
}

# ---------------------------------------------------------------------------
# Optional .env loader (JAVA_HOME / NODE_HOME / GRADLE_USER_HOME / etc.)
# ---------------------------------------------------------------------------
if (Test-Path $envFile) {
  foreach ($line in Get-Content $envFile) {
    $trimmed = ($line -replace '#.*$','').Trim()
    if (-not $trimmed -or $trimmed -notmatch '^(?<k>[A-Za-z_][A-Za-z0-9_]*)=(?<v>.*)$') { continue }
    $k = $matches['k']; $v = $matches['v'].Trim('"', "'")
    [Environment]::SetEnvironmentVariable($k, $v, 'Process')
  }
}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
function Write-Step($msg)  { Write-Host "[+] $msg" -ForegroundColor Cyan }
function Write-OK($msg)    { Write-Host "[OK] $msg" -ForegroundColor Green }
function Write-Warn($msg)  { Write-Host "[!!] $msg" -ForegroundColor Yellow }
function Write-Fail($msg)  { Write-Host "[XX] $msg" -ForegroundColor Red }
function Write-Gray($msg)  { Write-Host $msg -ForegroundColor DarkGray }

function Test-Command([string]$name) {
  return [bool] (Get-Command $name -ErrorAction SilentlyContinue)
}

function Invoke-Safe([string]$exe, [string[]]$args) {
  Write-Debug "> $exe $($args -join ' ')"
  & $exe @args
  if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $exe $($args -join ' ')" }
}

function New-DirIfMissing([string]$path) {
  if (-not (Test-Path $path)) { New-Item -ItemType Directory -Path $path -Force | Out-Null }
}

# ---------------------------------------------------------------------------
# Preflight checks (gradlew 行尾 / wrapper jar / 权限)
# ---------------------------------------------------------------------------
function Test-HasCrlf([string]$path) {
  if (-not (Test-Path $path)) { return $false }
  $bytes = [System.IO.File]::ReadAllBytes($path)
  for ($i = 0; $i -lt $bytes.Length - 1; $i++) {
    if ($bytes[$i] -eq 13 -and $bytes[$i+1] -eq 10) { return $true }
  }
  return $false
}

function Convert-CrlfToLf([string]$path) {
  if (-not (Test-Path $path)) { return }
  $content = [System.IO.File]::ReadAllText($path)
  $content = $content -replace "`r`n", "`n"
  [System.IO.File]::WriteAllText($path, $content, (New-Object System.Text.UTF8Encoding $false))
}

function Invoke-Preflight {
  Write-Step "Preflight checks ..."
  $errors = 0

  $gw = Join-Path $BACKEND_DIR 'gradlew'
  if (Test-Path $gw) {
    if (Test-HasCrlf $gw) {
      Convert-CrlfToLf $gw
      if (Test-HasCrlf $gw) {
        Write-Fail "backend/gradlew still has CRLF after fix."
        $errors++
      } else {
        Write-OK "backend/gradlew line endings OK (auto-fixed)."
      }
    } else {
      Write-Gray "  -> gradlew OK"
    }
  }

  $wrapperJar = Join-Path $BACKEND_DIR 'gradle\wrapper\gradle-wrapper.jar'
  if (-not (Test-Path $wrapperJar)) {
    Write-Fail "Missing gradle-wrapper.jar — Gradle wrapper not initialised."
    $errors++
  }

  if ($errors -gt 0) {
    Write-Fail "Preflight found $errors blocker(s). Fix above and retry."
    throw "Preflight failed"
  }
  Write-OK "Preflight passed."
}

# ---------------------------------------------------------------------------
# Cloudflare Tunnel 检查
# ---------------------------------------------------------------------------
$CLOUDFLARED_TUNNEL_NAME = "familyapp"
$CLOUDFLARED_HOSTNAME = "app.mayh.net.cn"
$CLOUDFLARED_CONFIG = Join-Path $HOME '.cloudflared\config.yml'

function Find-Cloudflared {
  $candidates = @(
    (Join-Path $HOME '.local\bin\cloudflared.exe'),
    'C:\Program Files\cloudflared\cloudflared.exe',
    "$env:LOCALAPPDATA\cloudflared\cloudflared.exe"
  )
  foreach ($c in $candidates) {
    if (Test-Path $c) { return $c }
  }
  if (Test-Command 'cloudflared') { return 'cloudflared' }
  return $null
}

function Get-TunnelConnections([string]$bin) {
  try {
    $info = & $bin tunnel info $CLOUDFLARED_TUNNEL_NAME 2>$null
    return ($info | Select-String -Pattern '^[0-9a-f]{8}-[0-9a-f]{4}').Count
  } catch { return 0 }
}

function Invoke-CheckCloudflared {
  Write-Step "检查 Cloudflare Tunnel ($CLOUDFLARED_TUNNEL_NAME) ..."

  $bin = Find-Cloudflared
  if (-not $bin) {
    Write-Warn "cloudflared 二进制未找到，跳过 tunnel 检查。"
    return
  }

  $conn = Get-TunnelConnections $bin
  if ($conn -gt 0) {
    Write-OK "Cloudflare Tunnel 运行中（$conn 条连接）-> https://$CLOUDFLARED_HOSTNAME/"
    return
  }

  Write-Warn "Tunnel 未运行（0 条连接）。"
  Write-Gray "  提示：如需持久化，运行 cloudflared service install + systemctl enable（Linux）；"
  Write-Gray "  Windows 下可用 nssm 注册为服务，或前台运行 cloudflared tunnel run。"
}

# ---------------------------------------------------------------------------
# Environment checks (per command)
# ---------------------------------------------------------------------------
function Assert-Docker {
  if (-not (Test-Command docker)) { throw "Docker is required but not on PATH. Install Docker Desktop and try again." }
  $verOut = & docker compose version 2>&1
  if ($LASTEXITCODE -ne 0) { throw "docker compose plugin required but unavailable: $verOut" }
}

function Assert-Java {
  if ($env:JAVA_HOME) {
    $javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (-not (Test-Path $javaExe)) { throw "JAVA_HOME is set but $javaExe does not exist." }
    $env:PATH = "$(Join-Path $env:JAVA_HOME 'bin');$env:PATH"
    return
  }
  if (-not (Test-Command java)) { throw "Java is required but not found. Install JDK 17+ or set JAVA_HOME in .env." }
}

function Assert-Gradle([ref]$gradleCmd) {
  $bat = Join-Path $BACKEND_DIR 'gradlew.bat'
  if (Test-Path $bat) { $gradleCmd.Value = $bat; return }
  if (Test-Command gradle) { $gradleCmd.Value = 'gradle'; return }
  throw "Neither backend\gradlew.bat nor global 'gradle' were found."
}

function Assert-Node {
  if ($env:NODE_HOME) {
    $nodeExe = Join-Path $env:NODE_HOME 'node.exe'
    if (-not (Test-Path $nodeExe)) { throw "NODE_HOME is set but $nodeExe does not exist." }
    $env:PATH = "$(Join-Path $env:NODE_HOME);$env:PATH"
    return
  }
  if (-not (Test-Command node)) { throw "Node.js is required but not on PATH. Install Node 20+ or set NODE_HOME in .env." }
  if (-not (Test-Command npm))  { throw "npm not found alongside Node.js." }
}

function Ensure-FrontendNodeModules {
  Assert-Node
  $nm = Join-Path $FRONTEND_DIR 'node_modules'
  if (-not (Test-Path $nm)) {
    Write-Step "Running 'npm install' in frontend (first-time bootstrap)..."
    Push-Location $FRONTEND_DIR
    try   { Invoke-Safe 'npm' @('install','--no-audit','--no-fund') }
    finally { Pop-Location }
  }
}

# Locate the most recent backend bootJar
function Find-BackendJar {
  $libsDir = Join-Path $BACKEND_DIR 'build\libs'
  if (-not (Test-Path $libsDir)) { return $null }
  $jar = Get-ChildItem $libsDir -Filter '*.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($jar) { return $jar.FullName }
  return $null
}

function Ensure-BackendBootJar([bool]$forceRebuild) {
  $jar = Find-BackendJar
  if ((-not $jar) -or $forceRebuild) {
    if (-not $jar) {
      Write-Warn "No backend bootJar found. Packaging locally first..."
    } else {
      Write-Step "-NoCache set: rebuilding backend bootJar locally ..."
    }
    Invoke-PackageBackend $forceRebuild
    $jar = Find-BackendJar
    if (-not $jar) { throw "bootJar not produced under backend/build/libs/." }
  } else {
    Write-OK "Using existing backend bootJar: $($jar.Substring($REPO_ROOT.Length+1))"
  }
}

# ---------------------------------------------------------------------------
# Help
# ---------------------------------------------------------------------------
function Write-Help {
  if ($PSCommandPath) {
    $rootScript = Split-Path -Leaf $PSCommandPath
  } else {
    $rootScript = 'dev.ps1'
  }
  $cmd = if ($rootScript -eq 'dev.ps1') { 'dev.ps1' } else { Join-Path 'scripts' $rootScript }
  @"
USAGE:
  $cmd <command> [options]

  Pre-flight
    preflight            Check repo is build-ready (gradlew line endings,
                         wrapper jar, permissions). Auto-fixes CRLF -> LF.

  Build / Compile (local, no Docker)
    compile              Compile backend + frontend (no tests)
    package              Build backend bootJar + frontend production dist
    test                 Run backend unit tests + frontend build

  Docker Images
    build-images         Build backend + frontend images (-NoCache to rebuild)
    build-backend-image  Build only backend image
    build-frontend-image Build only frontend image

  Deploy / Runtime (docker compose)
    up          [-Quick] Start all 7 services
                         (nginx, postgres, backend, frontend, tool-service,
                          agent-service, wechat-service). Default: build first.
                         -Quick skips the compose build step (uses cache).
                         首次运行自动从 .env.example 复制到 .env。
    down        [-PurgeData] [-Force]
                         Stop and remove containers. -PurgeData deletes
                         .volumes/postgres (needs -Force or Y prompt).
    restart     [svc]    Restart all or one service
                         (nginx|postgres|backend|frontend|tool-service|
                          agent-service|wechat-service)
    status               docker compose ps + health (7 服务)

  E2E 冒烟测试
    e2e                  运行 E2E 冒烟脚本（需先 docker compose up）
    e2e-flyway           运行 Flyway 幂等性验证（仅启动 postgres）
    e2e-config           仅验证 /api/agent/v1/config 端点（快速健康检查）

  Interact
    logs        [svc]    Tail 50 lines of all or a single service logs.
    backend-logs         Shortcut for logs backend
    frontend-logs        Shortcut for logs frontend
    db-shell             Enter a postgres psql shell inside the postgres container

  Cleanup
    clean                Remove local build artifacts (backend/build + frontend/dist)
    purge                clean + optionally prune images/containers. Use
                         -PurgeContainers / -PurgeImages / -PurgeAll to select.

  Meta
    help / -h            Show this help.

EXAMPLES
  # 1. Fresh run (compile + build images + start)
  $cmd package
  $cmd build-images
  $cmd up -Quick

  # 2. Just start (after first build)
  $cmd up -Quick

  # 3. See backend logs
  $cmd logs backend

  # 4. Stop (keep postgres data)
  $cmd down

  # 5. Stop + FULLY RESET postgres data (prompt)
  $cmd down -PurgeData
"@ | Out-Host
}

function Assert-ComposeFile {
  if (-not (Test-Path $COMPOSE_FILE)) { throw "docker-compose.yml not found at $COMPOSE_FILE" }
}

# --- Build (local) ---------------------------------------------------------
function Invoke-Compile {
  Write-Step "Compiling backend ..."
  Assert-Java; $gradle = $null; Assert-Gradle ([ref]$gradle)
  Push-Location $BACKEND_DIR
  try   { Invoke-Safe $gradle @('compileJava','compileTestJava','bootJar','-x','test','--no-daemon') }
  finally { Pop-Location }

  Write-Step "Compiling frontend (npm run build) ..."
  Ensure-FrontendNodeModules
  Push-Location $FRONTEND_DIR
  try   { Invoke-Safe 'npm' @('run','build') }
  finally { Pop-Location }

  Write-OK "Compile complete."
}

function Invoke-PackageBackend([bool]$clean) {
  Write-Step "Packaging backend bootJar ..."
  Assert-Java; $gradle = $null; Assert-Gradle ([ref]$gradle)
  Invoke-Preflight
  $args = @('bootJar','-x','test','--no-daemon')
  if ($clean) { $args = @('clean') + $args }
  Push-Location $BACKEND_DIR
  try   { Invoke-Safe $gradle $args }
  finally { Pop-Location }
  $jar = Find-BackendJar
  if ($jar) {
    $sz = [math]::Round((Get-Item $jar).Length / 1KB, 0)
    Write-Gray "  -> $($jar.Substring($REPO_ROOT.Length+1))  $sz KB"
  } else {
    throw "bootJar not produced under backend/build/libs/."
  }
}

function Invoke-Package {
  Invoke-PackageBackend $true
  Write-Step "Packaging frontend dist ..."
  Ensure-FrontendNodeModules
  Push-Location $FRONTEND_DIR
  try   { Invoke-Safe 'npm' @('run','build') }
  finally { Pop-Location }
  $dist = Join-Path $FRONTEND_DIR 'dist'
  if (Test-Path $dist) {
    $sz = [math]::Round(((Get-ChildItem $dist -Recurse -File | Measure-Object Length -Sum).Sum/1KB),0)
    Write-Gray "  -> frontend/dist  $sz KB"
  }
  Write-OK "Package complete."
}

function Invoke-Test {
  Write-Step "Running backend unit tests ..."
  Assert-Java; $gradle = $null; Assert-Gradle ([ref]$gradle)
  Push-Location $BACKEND_DIR
  try   { Invoke-Safe $gradle @('test','--no-daemon') }
  finally { Pop-Location }

  Write-Step "Verifying frontend build ..."
  Ensure-FrontendNodeModules
  Push-Location $FRONTEND_DIR
  try   { Invoke-Safe 'npm' @('run','build') }
  finally { Pop-Location }

  Write-OK "All tests + frontend build passed."
}

# --- Docker images ---------------------------------------------------------
function Invoke-BuildImages([string[]]$services, [bool]$noCache) {
  Assert-Docker; Assert-ComposeFile
  Invoke-Preflight

  $buildBackend = ($services.Count -eq 0) -or ($services -contains 'backend')
  if ($buildBackend) { Ensure-BackendBootJar $noCache }

  $args = @('-f',$COMPOSE_FILE,'build')
  if ($noCache) { $args += '--no-cache' }
  if ($services.Count -gt 0) { $args += $services }
  Write-Step "docker compose build (services: $($services -join ', '); noCache=$noCache) ..."
  Invoke-Safe 'docker' $('compose') $args
  Write-OK "Image build complete."
}

# --- Compose lifecycle -----------------------------------------------------
function Invoke-Up([bool]$quick, [bool]$noCache) {
  Assert-Docker; Assert-ComposeFile
  Invoke-Preflight
  New-DirIfMissing $VOLUMES_DIR
  New-DirIfMissing $PG_DATA_DIR

  # backend Dockerfile 需要预构建的 bootJar
  Ensure-BackendBootJar $noCache

  if (-not $quick) {
    Write-Step "Building docker images first (disable with -Quick) ..."
    $args = @('-f',$COMPOSE_FILE,'build')
    if ($noCache) { $args += '--no-cache' }
    Invoke-Safe 'docker' $('compose') $args
  } else {
    Write-Step "Skipping explicit docker compose build (-Quick set)."
  }

  Write-Step "Starting services (postgres -> backend -> frontend via healthcheck) ..."
  Invoke-Safe 'docker' $('compose') @('-f',$COMPOSE_FILE,'up','-d')

  Write-Step "Waiting for services healthy (max ~120s) ..."
  $waited = 0; $intervalSec = 5; $maxSec = 120; $allHealthy = $false
  do {
    Start-Sleep -Seconds $intervalSec
    $waited += $intervalSec
    $allHealthy = $true
    $ps = (& docker compose -f $COMPOSE_FILE ps --format json 2>$null)
    if (-not $ps) { Write-Gray "  compose ps not ready yet ..."; continue }
    foreach ($raw in (@($ps))) {
      if (-not $raw) { continue }
      try { $o = $raw | ConvertFrom-Json } catch { continue }
      $state = [string]$o.State; $health = [string]$o.Health
      if ($state -ne 'running') { $allHealthy = $false; continue }
      if ($health -and $health -ne '' -and $health -ne 'healthy') { $allHealthy = $false }
    }
    Write-Gray "  waited ${waited}s / ${maxSec}s"
  } while ((-not $allHealthy) -and $waited -lt $maxSec)

  Invoke-Status

  if (-not $allHealthy) {
    Write-Warn "Timeout reached before all services healthy. Run 'dev.ps1 logs [svc]' to diagnose."
  } else {
    Write-OK "All services healthy. (7/7: nginx, postgres, backend, frontend, tool-service, agent-service, wechat-service)"
    Write-Host ""
    $nginxPort = if ($env:NGINX_PORT) { $env:NGINX_PORT } else { '80' }
    Write-Host "  Frontend (nginx):       http://localhost:$nginxPort/"
    Write-Host "  Backend API:            http://localhost:$nginxPort/api/（8080 无宿主端口，经 nginx 分流）"
    Write-Host "  WeChat 入口:            http://localhost:$nginxPort/wechat/（authorize / oauth/callback / callback）"
    Write-Host "  Postgres (local):       localhost:5432 db=familyapp user=familyapp_user"
    Write-Host "  tool-service (内部):    http://localhost:$nginxPort/api/tools/ (经 backend 代理)"
    Write-Host "  agent-service (内部):   http://localhost:$nginxPort/api/agent/ (经 backend 代理)"
    Write-Host "  wechat-service (内部):  经 nginx /wechat/ 与 backend infra-tier 代理，无宿主端口"
    $aiFlag = if (Test-Path $envFile) { (Get-Content $envFile | Select-String '^AI_DECOMPOSE_ENABLED=' | ForEach-Object { ($_ -split '=')[1].Trim() }) | Select-Object -First 1 } else { 'true（默认）' }
    $notifyFlag = if (Test-Path $envFile) { (Get-Content $envFile | Select-String '^WECHAT_NOTIFY_ENABLED=' | ForEach-Object { ($_ -split '=')[1].Trim() }) | Select-Object -First 1 } else { 'true（默认）' }
    $tplCount = if (Test-Path $envFile) { (Get-Content $envFile | Select-String '^WECHAT_TPL_.+=.+' ).Count } else { 0 }
    Write-Host "  AI 拆解开关:           AI_DECOMPOSE_ENABLED=$aiFlag"
    Write-Host "  微信推送开关:           WECHAT_NOTIFY_ENABLED=$notifyFlag"
    Write-Host "  微信模板 ID:            WECHAT_TPL_*=$tplCount/4 已配置（0=全部事件静默跳过）"
    Invoke-CheckCloudflared
  }
}

function Invoke-Down([bool]$purgeData, [bool]$force) {
  Assert-Docker; Assert-ComposeFile
  Write-Step "Stopping and removing containers ..."
  Invoke-Safe 'docker' $('compose') @('-f',$COMPOSE_FILE,'down')

  if ($purgeData) {
    if (-not (Test-Path $PG_DATA_DIR)) { Write-Warn "No postgres data dir to remove ($PG_DATA_DIR)."; return }
    if (-not $force) {
      $answer = Read-Host "This will PERMANENTLY DELETE '$PG_DATA_DIR' (cannot recover). Type 'YES' to continue"
      if ($answer -ne 'YES') { Write-Warn "Skipped purge-data."; return }
    }
    Write-Step "Removing $PG_DATA_DIR ..."
    Remove-Item $PG_DATA_DIR -Recurse -Force
    Write-OK "Purge-data complete."
  } else {
    Write-OK "Containers stopped. Data kept at $PG_DATA_DIR."
  }
}

function Invoke-Restart([string]$target) {
  Assert-Docker; Assert-ComposeFile
  $args = @('-f',$COMPOSE_FILE,'restart')
  if ($target) { $args += $target }
  Invoke-Safe 'docker' $('compose') $args
  Invoke-Status
}

function Invoke-Status {
  Assert-Docker; Assert-ComposeFile
  & docker compose -f $COMPOSE_FILE ps
  if ($LASTEXITCODE -ne 0) { Write-Fail "compose ps failed with $LASTEXITCODE" }
  Write-Host ""
  $bin = Find-Cloudflared
  if ($bin) {
    $conn = Get-TunnelConnections $bin
    if ($conn -gt 0) {
      Write-Host "  Cloudflare Tunnel: $conn connections -> https://$CLOUDFLARED_HOSTNAME/" -ForegroundColor Green
    } else {
      Write-Host "  Cloudflare Tunnel: NOT running (0 connections) -> $CLOUDFLARED_HOSTNAME" -ForegroundColor Yellow
    }
  } else {
    Write-Host "  Cloudflare Tunnel: cloudflared not installed" -ForegroundColor DarkGray
  }
}

function Invoke-Logs([string]$target) {
  Assert-Docker; Assert-ComposeFile
  $args = @('-f',$COMPOSE_FILE,'logs','--tail','50','-f')
  if ($target) { $args += $target }
  & docker @('compose') @args
}

function Invoke-DbShell {
  Assert-Docker; Assert-ComposeFile
  $name = (& docker compose -f $COMPOSE_FILE ps --format '{{.Service}}|{{.Name}}' 2>$null) `
    | ForEach-Object { if ($_ -match '^postgres\|(?<n>.+)$') { $matches['n'] } }
  if (-not $name) { throw "postgres container is not running. Run 'dev.ps1 up' first." }
  & docker exec -it $name psql -U familyapp_user -d familyapp
}

# --- Cleanup ---------------------------------------------------------------
function Invoke-Clean {
  Write-Step "Removing local build artifacts ..."
  $dirs = @(
    (Join-Path $BACKEND_DIR 'build'),
    (Join-Path $FRONTEND_DIR 'dist')
  )
  foreach ($d in $dirs) {
    if (Test-Path $d) {
      Write-Gray "  rm $d"
      Remove-Item $d -Recurse -Force
    }
  }
  Write-OK "Clean complete."
}

function Confirm-Action([string]$prompt, [bool]$yes) {
  if ($yes) { return $true }
  $ans = Read-Host "$prompt (y/N)"
  return ($ans -eq 'y' -or $ans -eq 'Y' -or $ans -eq 'yes')
}

function Invoke-Purge {
  Invoke-Clean
  Assert-Docker

  if (-not $PurgeAll -and -not $PurgeContainers -and -not $PurgeImages) {
    Write-Warn "Purge invoked but no targets selected. Add one of: -PurgeContainers / -PurgeImages / -PurgeAll"
    return
  }

  $doContainers = $PurgeAll -or $PurgeContainers
  $doImages     = $PurgeAll -or $PurgeImages

  if ($doContainers) {
    if (Confirm-Action "Prune ALL stopped containers" $PurgeAll) {
      Write-Step "docker container prune -f"
      & docker container prune -f
    }
  }

  if ($doImages) {
    if (Confirm-Action "Prune DANGLING images (-a would remove ALL unused; not doing that by default)" $PurgeAll) {
      Write-Step "docker image prune -f"
      & docker image prune -f
    }
  }
  Write-OK "Purge complete."
}

# --- E2E 冒烟测试 ----------------------------------------------------------
function Invoke-E2e([string]$subcmd = 'smoke') {
  $e2eDir = Join-Path $REPO_ROOT 'scripts\e2e'
  if ($subcmd -eq 'smoke' -or -not $subcmd) {
    Write-Step "运行 E2E 冒烟测试（AI_DECOMPOSE_ENABLED=false 降级模式）..."
    Assert-Docker; Assert-ComposeFile
    $env:AI_DECOMPOSE_ENABLED = 'false'
    & bash (Join-Path $e2eDir 'run_smoke.sh') '--skip-build'
    if ($LASTEXITCODE -ne 0) { Write-Fail "E2E 冒烟测试失败。查看 /tmp/e2e_run*.log 排查。"; throw "E2E failed" }
    Write-OK "E2E 冒烟测试通过。"
  } elseif ($subcmd -eq 'flyway') {
    Write-Step "运行 Flyway 幂等性验证..."
    & bash (Join-Path $e2eDir 'flyway_idempotent.sh')
    if ($LASTEXITCODE -ne 0) { Write-Fail "Flyway 幂等性验证失败。"; throw "Flyway E2E failed" }
    Write-OK "Flyway 幂等性验证通过。"
  } elseif ($subcmd -eq 'config') {
    Write-Step "验证 /api/agent/v1/config 端点..."
    Assert-Docker; Assert-ComposeFile
    $env:AI_DECOMPOSE_ENABLED = 'false'
    & bash (Join-Path $e2eDir 'run_smoke.sh') '--only-config'
    if ($LASTEXITCODE -ne 0) { Write-Fail "config 端点验证失败。"; throw "E2E config failed" }
    Write-OK "config 端点验证通过。"
  } else {
    Write-Fail "未知子命令: $subcmd。可用: smoke | flyway | config"
    throw "Unknown e2e subcommand"
  }
}

# ---------------------------------------------------------------------------
# Main dispatch
# ---------------------------------------------------------------------------
try {
  switch ($Command) {
    'help'                { Write-Help; exit 0 }
    'preflight'           { Invoke-Preflight }
    'compile'             { Invoke-Compile }
    'package'             { Invoke-Package }
    'test'                { Invoke-Test }
    'build-images'        { Invoke-BuildImages -services @() -noCache $NoCache.IsPresent }
    'build-backend-image' { Invoke-BuildImages -services @('backend') -noCache $NoCache.IsPresent }
    'build-frontend-image'{ Invoke-BuildImages -services @('frontend') -noCache $NoCache.IsPresent }
    'up'                  { Invoke-Up -quick $Quick.IsPresent -noCache $NoCache.IsPresent }
    'down'                { Invoke-Down -purgeData $PurgeData.IsPresent -force $Force.IsPresent }
    'restart'             { Invoke-Restart -target ($CommandArgs[0]) }
    'status'              { Invoke-Status }
    'logs'                { Invoke-Logs -target ($CommandArgs[0]) }
    'backend-logs'        { Invoke-Logs -target 'backend' }
    'frontend-logs'       { Invoke-Logs -target 'frontend' }
    'db-shell'            { Invoke-DbShell }
    'clean'               { Invoke-Clean }
    'purge'               { Invoke-Purge }
    'e2e'                 { Invoke-E2e -subcmd ($CommandArgs[0]) }
    'e2e-flyway'          { Invoke-E2e -subcmd 'flyway' }
    'e2e-config'          { Invoke-E2e -subcmd 'config' }
    default { Write-Help; exit 1 }
  }
  exit 0
} catch {
  Write-Fail $_.Exception.Message
  if ($_.ScriptStackTrace) { Write-Debug $_.ScriptStackTrace }
  exit 1
}
