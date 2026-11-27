# One-time, self-contained local backend bootstrap for ezTIK.
# Owns its cache under ezTIK/.lab; NEVER reads ezTube directories.
param([string]$LabDir = "")
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Pinned = "d438d72fa903aa0ecb6f104fa9b915928134fe0a"
if (-not $LabDir) { $LabDir = Join-Path $Root ".lab\guest-backend" }

function Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed (exit $LASTEXITCODE)" }
}

foreach ($name in @("git", "node", "python", "npx.cmd")) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "Missing executable: $name"
    }
}
$Version = (& node --version).Trim()
if ([int]([regex]::Match($Version, '^v(\d+)').Groups[1].Value) -lt 22) {
    throw "ezTIK guest backend requires Node.js 22+"
}
New-Item -ItemType Directory -Path $LabDir -Force | Out-Null
$Source = Join-Path $LabDir "upstream"
$Venv = Join-Path $LabDir ".venv"
$Python = Join-Path $Venv "Scripts\python.exe"
$Built = Join-Path $Source "dist\main.js"

if (-not (Test-Path (Join-Path $Source ".git"))) {
    if (Test-Path $Source) { throw "Incomplete existing source at $Source; inspect before retry" }
    Write-Host "===== CLONE PINNED GUEST BACKEND (ISOLATED) =====" -ForegroundColor Cyan
    Checked "git" @("clone","--no-checkout","--filter=blob:none","https://github.com/sangzun-han/Tiktok-Api.git",$Source)
    Checked "git" @("-C",$Source,"fetch","--depth=1","origin",$Pinned)
    Checked "git" @("-C",$Source,"checkout","--detach",$Pinned)
}
$Sha = (& git -C $Source rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $Sha -ne $Pinned) { throw "Unexpected upstream SHA; abort" }
if (git -C $Source status --porcelain --untracked-files=no) {
    throw "Upstream checkout has modified tracked files; abort"
}

if (-not (Test-Path $Python)) {
    Write-Host "===== ISOLATED PYTHON ENVIRONMENT =====" -ForegroundColor Cyan
    Checked "python" @("-m","venv",$Venv)
}
try {
    Checked $Python @("-c","import curl_cffi; assert curl_cffi.__version__ == '0.17.0'")
} catch {
    Write-Host "===== INSTALL PINNED TLS TRANSPORT =====" -ForegroundColor Cyan
    Checked $Python @("-m","pip","install","--no-input","--disable-pip-version-check","curl_cffi==0.17.0")
}
Checked $Python @("-c","import curl_cffi; assert curl_cffi.__version__ == '0.17.0'")

if (-not (Test-Path $Built)) {
    Write-Host "===== BUILD PINNED NODE BACKEND =====" -ForegroundColor Cyan
    Push-Location $Source
    try {
        Checked "npx.cmd" @("--yes","pnpm@11","install","--frozen-lockfile")
        Checked "npx.cmd" @("--yes","pnpm@11","run","build")
    } finally { Pop-Location }
}
if (-not (Test-Path $Built)) { throw "Pinned backend build missing" }
Write-Host "PASS: ezTIK self-managed backend cache" -ForegroundColor Green
Write-Host "LAB_DIR=$LabDir"
