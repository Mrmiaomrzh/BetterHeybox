#!/usr/bin/env pwsh
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [switch]$KeepWork
)

$ErrorActionPreference = 'Continue'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$work = Join-Path $repoRoot '.ktcheck'
New-Item -ItemType Directory -Force -Path $work, "$work\out" | Out-Null

if (-not (Test-Path $Source)) { $Source = Join-Path $repoRoot $Source }
if (-not (Test-Path $Source)) { throw "找不到源文件: $Source" }
$Source = (Resolve-Path $Source).Path
$simpleName = [System.IO.Path]::GetFileNameWithoutExtension($Source)

$gc = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'

function Find-Jar([string]$group, [string]$artifact, [string]$version) {
    $dir = Join-Path $gc "$group\$artifact\$version"
    if (-not (Test-Path $dir)) { return $null }
    $jar = @(Get-ChildItem $dir -Recurse -Filter "$artifact-$version.jar" -ErrorAction SilentlyContinue) |
        Select-Object -First 1
    if ($null -eq $jar) { return $null }
    return [string]$jar.FullName
}

function Find-JarAny([string]$group, [string]$artifact, [string]$filter) {
    $dir = Join-Path $gc "$group\$artifact"
    if (-not (Test-Path $dir)) { return $null }
    $jar = @(Get-ChildItem $dir -Recurse -Filter $filter -ErrorAction SilentlyContinue) |
        Where-Object { $_.Name -notlike '*-sources*' -and $_.Name -notlike '*-javadoc*' } |
        Select-Object -First 1
    if ($null -eq $jar) { return $null }
    return [string]$jar.FullName
}

$kotlinVersion = '2.4.10'
$kc = Find-Jar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' $kotlinVersion
$std = Find-Jar 'org.jetbrains.kotlin' 'kotlin-stdlib' $kotlinVersion
$trove = Find-Jar 'org.jetbrains.intellij.deps' 'trove4j' '1.0.20200330'
$scriptrt = Find-Jar 'org.jetbrains.kotlin' 'kotlin-script-runtime' $kotlinVersion
$ann = Find-Jar 'org.jetbrains' 'annotations' '23.0.0'
$corout = Find-JarAny 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' 'kotlinx-coroutines-core-jvm-*.jar'
$reflect = Find-JarAny 'org.jetbrains.kotlin' 'kotlin-reflect' 'kotlin-reflect-*.jar'

if (-not $kc) { throw "找不到 kotlin-compiler-embeddable $kotlinVersion，请先跑一次 Gradle 构建以下载依赖" }

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$android = Get-ChildItem (Join-Path $sdk 'platforms') -Directory -ErrorAction SilentlyContinue |
    Sort-Object { [int]($_.Name -replace '^android-', '') } -Descending |
    Select-Object -First 1 | ForEach-Object { Join-Path $_.FullName 'android.jar' }
if (-not $android -or -not (Test-Path $android)) { throw "找不到 android.jar，检查 ANDROID_HOME 或 SDK 安装" }

$srcJar = Join-Path $repoRoot 'app\build\intermediates\compile_app_classes_jar\debug\bundleDebugClassesToCompileJar\classes.jar'
$proj = Join-Path $work "proj-no$simpleName.jar"
if (-not (Test-Path $srcJar)) {
    throw "找不到 $srcJar，请先执行一次 :app:assembleDebug（或任意会产出 compile jar 的任务）"
}
if (-not (Test-Path $proj)) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zin = [System.IO.Compression.ZipFile]::OpenRead($srcJar)
    $zout = [System.IO.Compression.ZipFile]::Open($proj, 'Create')
    foreach ($e in $zin.Entries) {
        if ($e.FullName -like "com/better/heybox/*/$simpleName*" -or
            $e.FullName -like "com/better/heybox/$simpleName*") { continue }
        $ne = $zout.CreateEntry($e.FullName)
        $s = $e.Open(); $t = $ne.Open(); $s.CopyTo($t); $t.Dispose(); $s.Dispose()
    }
    $zout.Dispose(); $zin.Dispose()
}

$dk = Join-Path $work 'dexkit-classes.jar'
if (-not (Test-Path $dk)) {
    $aar = Find-JarAny 'org.luckypray' 'dexkit' 'dexkit-*.aar'
    if ($aar) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $extract = Join-Path $work 'dexkit-aar'
        if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
        [System.IO.Compression.ZipFile]::ExtractToDirectory($aar, $extract)
        Copy-Item (Join-Path $extract 'classes.jar') $dk
    }
}

$cpParts = @($android, $proj, $std) | Where-Object { $_ }
if (Test-Path $dk) { $cpParts += $dk }
$cp = $cpParts -join ';'

Get-ChildItem "$work\out" -Recurse -File -ErrorAction SilentlyContinue | Remove-Item -Force

$runtimeCp = @($kc, $std, $trove, $scriptrt, $ann, $corout, $reflect) |
    Where-Object { $_ } | Select-Object -Unique
$runtimeCp = $runtimeCp -join ';'

Write-Host "编译 $simpleName ..." -ForegroundColor Cyan
& java -cp $runtimeCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -cp $cp -jvm-target 17 -nowarn -no-stdlib -no-reflect -d "$work\out" $Source 2>&1 |
    Where-Object { $_ -notmatch 'sun\.misc\.Unsafe|FastJarFileSystemKt|WARNING: Please consider reporting' }

$exit = $LASTEXITCODE
if ($exit -eq 0) {
    Write-Host "✓ $simpleName 单独编译通过" -ForegroundColor Green
} else {
    Write-Host "✗ $simpleName 编译失败（exit $exit）" -ForegroundColor Red
}

if (-not $KeepWork) {
    Remove-Item "$work\out" -Recurse -Force -ErrorAction SilentlyContinue
}

exit $exit
