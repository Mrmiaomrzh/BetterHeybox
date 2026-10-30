#!/usr/bin/env pwsh
param(
    [string]$Root = (Join-Path $PSScriptRoot '..\app\src\main\java'),
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path $Root).Path

function Get-Count([string]$text, [string]$pattern) {
    if (-not $text) { return 0 }
    return [regex]::Matches($text, $pattern).Count
}

$catchKtPattern = 'catch\s*\(\s*\w+\s*:\s*Throwable|catch\s*\(\s*Throwable'
$catchJavaPattern = 'catch\s*\(\s*Throwable'

$files = Get-ChildItem $Root -Recurse -Filter *.kt | Sort-Object FullName
$rows = @()
$violations = @()
$duplicates = @()
$partials = @()

foreach ($kt in $files) {
    $rel = $kt.FullName.Substring($Root.Length + 1)
    $javaPath = $kt.FullName -replace '\.kt$', '.java'
    $k = Get-Content $kt.FullName -Raw

    $row = [ordered]@{
        File    = $rel
        Lines   = (Get-Content $kt.FullName).Count
        Classic = Get-Count $k '\.hook\s*\{'
        ViaInst = Get-Count $k '(?<!Main)Module\.TAG\b|\b[a-z]\w*\.[a-z]\w*\.TAG\b'
        KClass  = Get-Count $k '\.kotlin\.|\bKClass\s*<|:\s*KClass\b'
        CatchKt = Get-Count $k $catchKtPattern
        CatchJv = $null
        InterJv = $null
    }

    if (Test-Path $javaPath) {
        $j = Get-Content $javaPath -Raw
        $row.CatchJv = Get-Count $j $catchJavaPattern
        $row.InterJv = Get-Count $j '\.intercept\s*\('
        $row.JavaLines = (Get-Content $javaPath).Count
        $duplicates += $rel
        $row.Partial = $row.JavaLines -gt 0 -and $row.Lines -lt ($row.JavaLines * 0.8)
    }

    $row['ProceedArr'] = Get-Count $k '\.proceed\s*\('
    $row['BadImport'] = Get-Count $k 'import\s+com\.better\.heybox[A-Z]'
    $rows += [pscustomobject]$row

    if ($row.Classic -gt 0) {
        $violations += "$rel : 出现 $($row.Classic) 处 classic 风格 .hook { —— 会吞异常并回退原方法"
    }
    if ($row.ViaInst -gt 0) {
        $violations += "$rel : $($row.ViaInst) 处形如 a.b.TAG 的经实例静态访问 —— Kotlin 应写 Class.TAG"
    }
    if ($null -ne $row.CatchJv -and $row.CatchKt -lt $row.CatchJv) {
        if ($row.Partial) {
            $partials += "$rel : catch $($row.CatchKt)/$($row.CatchJv)（.kt $($row.Lines) 行 vs .java $($row.JavaLines) 行，仍在转换中）"
        } else {
            $violations += "$rel : catch(Throwable) 由 $($row.CatchJv) 减为 $($row.CatchKt) —— 有防御被删"
        }
    }
    if ($row.KClass -gt 0) {
        $violations += "$rel : 出现 $($row.KClass) 处 KClass 反射 —— 会丢 isBridge/isSynthetic"
    }
    if ($row.BadImport -gt 0) {
        $violations += "$rel : $($row.BadImport) 处 import 少写一个点（com.better.heyboxXxx）"
    }
}

if (-not $Quiet) {
    "{0,-52} {1,6} {2,7} {3,7} {4,6} {5,8} {6,8} {7,8} {8,7}" -f `
        '文件', '行数', 'classic', '经实例', 'KClass', 'catch(kt)', 'catch(java)', 'proceed', 'intercept'
    '-' * 118
    foreach ($r in $rows) {
        "{0,-52} {1,6} {2,7} {3,7} {4,6} {5,8} {6,8} {7,8} {8,7}" -f `
            $r.File, $r.Lines, $r.Classic, $r.ViaInst, $r.KClass,
            $r.CatchKt, $(if ($null -eq $r.CatchJv) { '-' } else { $r.CatchJv }),
            $r.ProceedArr, $(if ($null -eq $r.InterJv) { '-' } else { $r.InterJv })
    }
    ''

    $javaAll = Get-ChildItem $Root -Recurse -Filter *.java
    $javaLines = 0
    foreach ($jf in $javaAll) { $javaLines += (Get-Content $jf.FullName).Count }
    $ktLines = 0
    foreach ($r in $rows) { $ktLines += $r.Lines }
    "Kotlin: $($rows.Count) 个文件 / $ktLines 行"
    "Java  : $($javaAll.Count) 个文件 / $javaLines 行"
    ''
}

if ($duplicates.Count -gt 0) {
    ''
    "=== 同名共存（$($duplicates.Count) 对，会导致 Redeclaration 编译失败）==="
    $duplicates | ForEach-Object { "  ⚠ $_" }
    '  转换中的中间状态属正常；若是提交状态则必须先删掉 .java。'
}

if ($partials.Count -gt 0) {
    ''
    "=== 仍在转换中（计数偏少属正常，不计为违规）==="
    $partials | ForEach-Object { "  … $_" }
}

if ($violations.Count -gt 0) {
    ''
    '=== 违规 ==='
    $violations | ForEach-Object { "  ✗ $_" }
    exit 1
}

if (-not $Quiet) { '✓ 未发现纪律违规' }
exit 0
