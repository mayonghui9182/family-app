# Family-app dev entry wrapper — forwards to scripts/dev.ps1
# Usage: .\dev.ps1 <command> [options]
# Example: .\dev.ps1 up
param(
  [Parameter(Position=0, ValueFromRemainingArguments=$true)]
  [string[]]$ArgsForward = @()
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$main = Join-Path $root 'scripts\dev.ps1'
if (-not (Test-Path $main)) {
  throw "Cannot locate $main. Please run from repo root."
}
& $main @ArgsForward
exit $LASTEXITCODE
