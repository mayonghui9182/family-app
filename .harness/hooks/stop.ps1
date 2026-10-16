# Harness Stop hook: check if .handoffs/current.md is stale (>1h)
# Invoked by .claude/settings.json hooks.Stop
# Output to stdout is shown to the user by Claude Code

$ErrorActionPreference = 'SilentlyContinue'

$handoff = 'C:\mayh\code\family-app\.handoffs\current.md'
$warn = $false

if (-not (Test-Path $handoff)) {
    Write-Output '[HARNESS] WARNING: .handoffs/current.md does not exist'
    $warn = $true
} else {
    $mod = (Get-Item $handoff).LastWriteTime
    $diffSec = [int]((Get-Date) - $mod).TotalSeconds
    if ($diffSec -gt 3600) {
        $min = [int]($diffSec / 60)
        Write-Output "[HARNESS] WARNING: .handoffs/current.md has not been updated for $min minutes"
        $warn = $true
    }
}

if ($warn) {
    Write-Output ''
    Write-Output 'Before ending the session, please:'
    Write-Output '  (1) Update .handoffs/current.md'
    Write-Output '  (2) Commit code + handoff together (if git is initialized)'
} else {
    Write-Output '[HARNESS] OK: handoff is fresh'
}

# exit 0 so Claude Code ends the session normally; hook is advisory only
exit 0
