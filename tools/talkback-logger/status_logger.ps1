# Show logger status: running or not, phone connection, today's file, last record time.
$Base    = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogDir  = Join-Path $Base 'logs'
$PidFile = Join-Path $LogDir 'logger.pid'
$Adb     = if ($env:ADB_PATH) { $env:ADB_PATH } else { 'adb' }

$running = $false
if (Test-Path $PidFile) {
    $id = (Get-Content $PidFile | Select-Object -First 1)
    if ($id -match '^\d+$') {
        $p = Get-CimInstance Win32_Process -Filter "ProcessId=$id" -ErrorAction SilentlyContinue
        if ($p -and $p.CommandLine -and $p.CommandLine.IndexOf((Join-Path $Base 'talkback_logger.py'), [StringComparison]::OrdinalIgnoreCase) -ge 0) { $running = $true; Write-Host "Running: yes (PID $id)" }
    }
}
if (-not $running) { Write-Host 'Running: no' }

$devs = & $Adb devices 2>$null | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
if ($devs) { Write-Host ("Phone connected: yes (" + (($devs | ForEach-Object { ($_ -split '\s+')[0] }) -join ', ') + ")") }
else { Write-Host 'Phone connected: no' }

$today = Join-Path $LogDir ((Get-Date -Format 'yyyy-MM-dd') + '.txt')
if (Test-Path $today) {
    $f = Get-Item $today
    Write-Host ("Today's file: {0} ({1} bytes)" -f $f.Name, $f.Length)
} else { Write-Host "Today's file: none yet ($((Get-Date -Format 'yyyy-MM-dd')).txt)" }

$state = Join-Path $LogDir 'logger-state.json'
if (Test-Path $state) {
    $s = Get-Content $state -Raw | ConvertFrom-Json
    Write-Host "Last record time: $($s.last_ts)"
} else { Write-Host 'Last record time: none' }
