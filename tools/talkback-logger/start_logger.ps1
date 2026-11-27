# Start the TalkBack logger in the background (hidden window). Does nothing if already running.
$ErrorActionPreference = 'Stop'
$Base    = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogDir  = Join-Path $Base 'logs'
$PidFile = Join-Path $LogDir 'logger.pid'
$Script  = Join-Path $Base 'talkback_logger.py'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

if (Test-Path $PidFile) {
    $oldPid = (Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($oldPid -match '^\d+$') {
        $p = Get-CimInstance Win32_Process -Filter "ProcessId=$oldPid" -ErrorAction SilentlyContinue
        if ($p -and $p.CommandLine -and $p.CommandLine.IndexOf($Script, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            Write-Host "Logger is already running (PID $oldPid). Nothing to do."
            exit 0
        }
    }
    Remove-Item $PidFile -Force
}

$py = (Get-Command python -ErrorAction Stop).Source
$proc = Start-Process -FilePath $py -ArgumentList @('-X', 'utf8', "`"$Script`"") `
    -WorkingDirectory $Base -WindowStyle Hidden -PassThru
Set-Content -Path $PidFile -Value $proc.Id -Encoding ascii
Write-Host "Logger started (PID $($proc.Id)). Daily files: $LogDir\YYYY-MM-DD.txt"
