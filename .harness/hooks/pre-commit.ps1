# Harness PreToolUse hook: when Bash runs git commit, check handoff freshness
# Invoked by .claude/settings.json hooks.PreToolUse
# stdin receives JSON: {"tool_name":"Bash","tool_input":{"command":"..."}}
# Project is NOT a git repo yet; this hook will not fire until `git init`

$ErrorActionPreference = 'SilentlyContinue'

# Read stdin and parse JSON
$payload = $null
try {
    $stdinText = [Console]::In.ReadToEnd()
    if ($stdinText) {
        $payload = $stdinText | ConvertFrom-Json
    }
} catch {
    # If JSON parse fails, just pass through without blocking
    exit 0
}

if (-not $payload) { exit 0 }

$cmd = $payload.tool_input.command
if (-not $cmd) { exit 0 }

# Only check when matching `git commit`
if ($cmd -notmatch 'git\s+commit') { exit 0 }

$handoff = 'C:\mayh\code\family-app\.handoffs\current.md'
$warn = $false

if (-not (Test-Path $handoff)) {
    Write-Output '[HARNESS PRE-COMMIT] WARNING: .handoffs/current.md does not exist'
    $warn = $true
} else {
    $mod = (Get-Item $handoff).LastWriteTime
    $diffSec = [int]((Get-Date) - $mod).TotalSeconds
    # PreToolUse threshold is looser than Stop (2h vs 1h) to avoid noisy commits
    if ($diffSec -gt 7200) {
        $min = [int]($diffSec / 60)
        Write-Output "[HARNESS PRE-COMMIT] WARNING: .handoffs/current.md has not been updated for $min minutes"
        $warn = $true
    }
}

if ($warn) {
    Write-Output 'Consider updating .handoffs/current.md before running git commit'
}

# exit 0 to let commit proceed; hook is advisory only
exit 0
