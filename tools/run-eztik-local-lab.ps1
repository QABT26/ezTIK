# ezTIK isolated Android debug QA, using the already-pinned cached Windows guest-feed backend.
param([string]$Serial='RFCY10HEXGM', [string]$LabDir='')
$ErrorActionPreference='Stop'
$Root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Adb=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$Port=39117
$ExpectedBackend='d438d72fa903aa0ecb6f104fa9b915928134fe0a'
$Server=$null
$ReverseAdded=$false
$keys=@('HOST','PORT','TIKTOK_PYTHON','TIKTOK_TRANSPORT','TIKTOK_MIN_REQUEST_INTERVAL_MS','ANDROID_HOME','ANDROID_SDK_ROOT')
$old=@{}
foreach($key in $keys){$old[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
function Checked([string]$Program, [string[]]$Arguments){
    & $Program @Arguments
    if($LASTEXITCODE -ne 0){throw ('Command failed: '+$Program+' exit='+$LASTEXITCODE)}
}
try {
    Set-Location $Root
    if(-not (Test-Path $Adb)){throw 'Android platform-tools adb missing'}
    if((git rev-parse --is-inside-work-tree).Trim() -ne 'true'){throw 'Not a Git worktree'}
    if(git status --porcelain){throw 'WORKTREE NOT CLEAN; preserve changes before QA'}

    # Windows Android Studio keeps the SDK under LOCALAPPDATA. A fresh Git clone
    # deliberately has no local.properties, so Gradle needs SDK discovery.
    Write-Host '===== ANDROID SDK AUTO-DISCOVERY =====' -ForegroundColor Cyan
    $SdkCandidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique
    $SdkHome = $null
    foreach ($Candidate in $SdkCandidates) {
        if ((Test-Path (Join-Path $Candidate 'platform-tools\adb.exe')) -and
            (Test-Path (Join-Path $Candidate 'platforms\android-35\android.jar'))) {
            $SdkHome = (Resolve-Path $Candidate).Path
            break
        }
    }
    if (-not $SdkHome) {
        throw 'Android SDK 35 not found. Verify Android Studio SDK Manager and LOCALAPPDATA\Android\Sdk.'
    }
    $env:ANDROID_HOME = $SdkHome
    $env:ANDROID_SDK_ROOT = $SdkHome
    $Adb = Join-Path $SdkHome 'platform-tools\adb.exe'
    $LocalProperties = Join-Path $Root 'local.properties'
    if (-not (Test-Path $LocalProperties)) {
        $SdkGradlePath = $SdkHome.Replace('\','/')
        Set-Content -Path $LocalProperties -Value "sdk.dir=$SdkGradlePath" -Encoding Ascii
        Write-Host 'Created ignored local.properties for this Windows checkout.'
    } else {
        # Never silently overwrite developer-specific SDK settings.
        $ExistingSdkLine = Get-Content $LocalProperties | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if (-not $ExistingSdkLine) {
            throw 'Existing local.properties has no sdk.dir; review it before continuing.'
        }
    }
    Write-Host "PASS: SDK=$SdkHome" -ForegroundColor Green

    if(-not (Get-Command node -ErrorAction SilentlyContinue)){throw 'Node.js not found'}
    Write-Host '===== GATEWAY UNIT TESTS =====' -ForegroundColor Cyan
    Checked 'node' @('--test',(Join-Path $Root 'backend\tiktok\feed-gateway.test.js'))
    $Wrapper=Join-Path $Root 'gradlew.bat'
    if(Test-Path $Wrapper){
        Write-Host '===== BUILD ezTIK WITH OWN PINNED GRADLE WRAPPER =====' -ForegroundColor Cyan
        Checked $Wrapper @('testDebugUnitTest','assembleDebug')
    } elseif(Get-Command gradle -ErrorAction SilentlyContinue){
        Checked 'gradle' @('-p',$Root,'testDebugUnitTest','assembleDebug')
    } else {throw 'No ezTIK Gradle wrapper or Gradle 8.9 installed'}
    $Apk=Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
    if(-not (Test-Path $Apk)){throw 'ezTIK debug APK missing'}
    if(-not $LabDir){
        $LabDir=Get-ChildItem ([IO.Path]::GetTempPath()) -Directory -Filter 'eztube-tiktok-native-*' |
            Where-Object {Test-Path (Join-Path $_.FullName 'upstream\dist\main.js')} |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
    }
    if(-not $LabDir){throw 'Pinned feed backend cache not found. This standalone debug lab cannot fetch a feed without it.'}
    $Source=Join-Path $LabDir 'upstream'
    $Python=Join-Path $LabDir '.venv\Scripts\python.exe'
    if(-not (Test-Path $Python)){throw 'Pinned backend Python environment missing'}
    if((git -C $Source rev-parse HEAD).Trim() -ne $ExpectedBackend){throw 'BACKEND COMMIT NOT PINNED'}
    $env:HOST='127.0.0.1'
    $env:PORT="$Port"
    $env:TIKTOK_PYTHON=$Python
    $env:TIKTOK_TRANSPORT='tls'
    $env:TIKTOK_MIN_REQUEST_INTERVAL_MS='2000'
    $StandardOut=Join-Path $LabDir 'eztik-lab.stdout.log'
    $StandardErr=Join-Path $LabDir 'eztik-lab.stderr.log'
    Write-Host '===== LOCAL FEED SERVICE (NO VPS) =====' -ForegroundColor Cyan
    $Server=Start-Process -FilePath (Get-Command node).Source -ArgumentList @('dist/main.js') -WorkingDirectory $Source -RedirectStandardOutput $StandardOut -RedirectStandardError $StandardErr -WindowStyle Hidden -PassThru
    $Base='http://127.0.0.1:39117'
    $ready=$false
    for($i=0;$i -lt 25;$i++){
        if($Server.HasExited){throw 'BACKEND EXITED'}
        Start-Sleep -Seconds 2
        try {
            $health=Invoke-RestMethod -Uri "$Base/internal/health" -TimeoutSec 8
            if($health.status -eq 'ok' -and $health.transport.inUse -eq 'tls'){$ready=$true;break}
        } catch {}
    }
    if(-not $ready){throw 'Backend health unavailable'}
    $feed=Invoke-RestMethod -Uri "$Base/tiktok/trending?count=16" -TimeoutSec 35
    if(@($feed.itemList).Count -lt 3){throw 'No usable guest-feed batch'}
    Write-Host 'PASS: BACKEND GUEST FEED' -ForegroundColor Green
    Write-Host '===== SAMSUNG USER 0 ONLY =====' -ForegroundColor Cyan
    Checked $Adb @('-s',$Serial,'get-state')
    Checked $Adb @('-s',$Serial,'install','--user','0','-r',$Apk)
    Checked $Adb @('-s',$Serial,'reverse','tcp:39117','tcp:39117')
    $ReverseAdded=$true
    Checked $Adb @('-s',$Serial,'logcat','-c')
    Checked $Adb @('-s',$Serial,'shell','am','start','--user','0','-n','com.qabt.eztik/.MainActivity')
    Write-Host 'ezTIK should display TikTok video. Swipe 5+ clips, refresh, check audio.' -ForegroundColor Cyan
    Write-Host 'Keep this PowerShell window open; Enter ends local backend and ADB reverse.'
    Read-Host 'Press Enter after Samsung QA'
    Checked $Adb @('-s',$Serial,'logcat','-d','-s','ezTikFeed:V','ezTikPlayer:V','*:S')
} finally {
    if($ReverseAdded){ & $Adb -s $Serial reverse --remove 'tcp:39117' 2>&1 | Out-Null }
    if($null -ne $Server -and -not $Server.HasExited){Stop-Process -Id $Server.Id -Force -ErrorAction SilentlyContinue}
    foreach($key in $keys){[Environment]::SetEnvironmentVariable($key,$old[$key],'Process')}
}
