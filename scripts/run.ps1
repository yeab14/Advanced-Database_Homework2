# One entry point for the three homework demos.
# Called by ..\hw2.cmd. You can also run this file directly.
param(
    [Parameter(Position = 0)]
    [string]$Target = "",
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$JavaArgs
)

$ErrorActionPreference = "Stop"
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

function Show-Usage {
    Write-Host @"
Usage (from the homework2 folder):
  .\hw Blocking
  .\hw RestartBlocking
  .\hw NoLiveLocks
  .\hw all

Optional load knobs, same for RestartBlocking and NoLiveLocks:
  .\hw RestartBlocking -Dhw2.tasks=40 -Dhw2.queries=5
  .\hw NoLiveLocks -Dhw2.tasks=40 -Dhw2.queries=5
"@
}

function Import-DotEnv {
    $envFile = Join-Path $Root ".env"
    if (-not (Test-Path $envFile)) {
        Write-Host "No .env file. Using defaults (port 5432, database adb_hw2, user hw2)."
        Write-Host "Copy .env.example to .env and set HW2_DB_PORT if your PostgreSQL is not on 5432."
        return
    }
    foreach ($raw in Get-Content -LiteralPath $envFile) {
        $line = $raw.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith("#")) { continue }
        if ($line.StartsWith("export ")) { $line = $line.Substring(7).Trim() }
        $eq = $line.IndexOf("=")
        if ($eq -lt 1) { continue }
        $key = $line.Substring(0, $eq).Trim()
        $value = $line.Substring($eq + 1).Trim().Trim('"').Trim("'")
        Set-Item -Path "Env:$key" -Value $value
    }
}

function Compile-Sources {
    $out = Join-Path $Root "out"
    $jar = Join-Path $Root "lib\postgresql-42.2.18.jar"
    if (-not (Test-Path $jar)) {
        throw "Missing JDBC driver: $jar"
    }
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    $sources = @(Get-ChildItem (Join-Path $Root "src\*.java") | ForEach-Object { $_.FullName })
    & javac -cp $jar -d $out @sources
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

function Invoke-Demo([string]$Class) {
    $cp = "$(Join-Path $Root 'out');$(Join-Path $Root 'lib\postgresql-42.2.18.jar')"
    $all = @("-cp", $cp)
    if ($JavaArgs) { $all += $JavaArgs }
    $all += $Class
    Write-Host ""
    Write-Host "---- $Class ----"
    & java @all
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$aliases = @{
    "blocking"         = "Blocking"
    "restart"          = "RestartBlocking"
    "restartblocking"  = "RestartBlocking"
    "nolive"           = "NoLiveLocks"
    "nolivelocks"      = "NoLiveLocks"
}

if ([string]::IsNullOrWhiteSpace($Target) -or $Target -eq "help" -or $Target -eq "-h") {
    Show-Usage
    exit 0
}

Import-DotEnv
Compile-Sources

$key = $Target.ToLowerInvariant()
if ($key -eq "all") {
    Invoke-Demo "Blocking"
    Invoke-Demo "RestartBlocking"
    Invoke-Demo "NoLiveLocks"
    exit 0
}

if (-not $aliases.ContainsKey($key)) {
    Write-Host "Unknown demo: $Target"
    Show-Usage
    exit 1
}

Invoke-Demo $aliases[$key]
exit 0
