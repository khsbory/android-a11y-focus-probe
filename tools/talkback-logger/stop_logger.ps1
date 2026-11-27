# Stop only the TalkBack logger process (found via the PID file) and its adb child.
$Base    = Split-Path -Parent $MyInvocation.MyCommand.Path
$PidFile = Join-Path $Base 'logs\logger.pid'

if (-not (Test-Path $PidFile)) { Write-Host 'No PID file. Logger is not running.'; exit 0 }
$target = (Get-Content $PidFile | Select-Object -First 1)
if ($target -notmatch '^\d+$') { Remove-Item $PidFile -Force; Write-Host 'Invalid PID file removed.'; exit 0 }

$p = Get-CimInstance Win32_Process -Filter "ProcessId=$target" -ErrorAction SilentlyContinue
if ($p -and $p.CommandLine -and $p.CommandLine.IndexOf((Join-Path $Base 'talkback_logger.py'), [StringComparison]::OrdinalIgnoreCase) -ge 0) {
    # Stop the adb logcat child started by the logger first.
    Get-CimInstance Win32_Process -Filter "ParentProcessId=$target" |
        Where-Object { $_.Name -eq 'adb.exe' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Stop-Process -Id ([int]$target) -Force
    Write-Host "Logger stopped (PID $target)."
} else {
    Write-Host "PID $target is not the logger. Nothing was stopped."
}
Remove-Item $PidFile -Force
