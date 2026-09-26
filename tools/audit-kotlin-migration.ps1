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
    }

    $row['ProceedArr'] = Get-Count $k '\.proceed\s*\('
    $rows += [pscustomobject]$row

    if ($row.Classic -gt 0) {
        $violations += "$rel : 出现 $($row.Classic) 处 classic 风格 .hook { —— 会吞异常并回退原方法"
    }
    if ($row.ViaInst -gt 0) {
        $violations += "$rel : $($row.ViaInst) 处形如 a.b.TAG 的经实例静态访问 —— Kotlin 应写 Class.TAG"
    }
    if ($null -ne $row.CatchJv -and $row.CatchKt -lt $row.CatchJv) {
        $violations += "$rel : catch(Throwable) 由 $($row.CatchJv) 减为 $($row.CatchKt) —— 有防御被删"
    }
    if ($row.KClass -gt 0) {
        $violations += "$rel : 出现 $($row.KClass) 处 KClass/::class.java —— 反射场景会丢 isBridge/isSynthetic"
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
    "共 $($rows.Count) 个 Kotlin 文件。"
}

if ($violations.Count -gt 0) {
    ''
    '=== 违规 ==='
    $violations | ForEach-Object { "  ✗ $_" }
    exit 1
}

if (-not $Quiet) { '✓ 未发现纪律违规' }
exit 0
