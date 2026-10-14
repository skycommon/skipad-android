# 在桌面 JVM 上跑纯逻辑单元测试（验证规则解析与匹配算法）。
#
# 这些测试只调用不触碰 Android API 的静态方法，所以可以脱离设备、脱离模拟器运行。
# 支持工程放在含中文的路径下。

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$root  = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
$tools = "$root\tools"

function Die($msg) { Write-Host "!!! $msg" -ForegroundColor Red; exit 1 }

function FindOne($parent, $probe) {
    if (-not (Test-Path $parent)) { return $null }
    if (Test-Path (Join-Path $parent $probe)) { return $parent }
    $cand = Get-ChildItem $parent -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName $probe) } |
            Select-Object -First 1
    if ($cand) { return $cand.FullName }
    return $null
}

$jdk     = FindOne "$tools\jdk"      'bin\javac.exe'
$platDir = FindOne "$tools\platform" 'android.jar'
if (-not $jdk)     { Die "未找到 JDK（需要 tools\jdk\*\bin\javac.exe）" }
if (-not $platDir) { Die "未找到 android.jar（需要 tools\platform\*\android.jar）" }

$androidJar = Join-Path $platDir 'android.jar'
$javac = "$jdk\bin\javac.exe"
$java  = "$jdk\bin\java.exe"

# org.json 的实现 jar（android.jar 里的 org.json 是运行时会抛 Stub! 的空壳，
# 跑 JVM 单测必须换成真实现）。缺失时自动下载。
$libDir  = "$tools\lib"
$jsonJar = "$libDir\json-20231013.jar"
if (-not (Test-Path $jsonJar)) {
    Write-Host "缺少 $jsonJar，尝试下载..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path $libDir | Out-Null
    $dlJs = "$root\get_json.ps1"
    if (Test-Path $dlJs) {
        & pwsh -NoProfile -File $dlJs
    } else {
        Die "缺少 $jsonJar，且找不到 get_json.ps1。请手动下载 org.json:json:20231013 放到 $libDir"
    }
}
if (-not (Test-Path $jsonJar)) { Die "仍未获得 $jsonJar" }

$testOut = "$root\out\test-classes"
$appOut  = "$root\out\test-app-classes"
foreach ($d in @($testOut, $appOut)) {
    if (Test-Path $d) { Remove-Item -Recurse -Force $d }
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

# RuleStore 引用了 R.raw.default_rules，测试时用一个最小 R 桩即可，
# 不必依赖 build.ps1 生成（这样单测可以独立运行）。
$stubDir = "$root\out\test-stub\com\lbx\skipad"
New-Item -ItemType Directory -Force -Path $stubDir | Out-Null
$rStub = "$stubDir\R.java"
@'
package com.lbx.skipad;
/** 仅供桌面 JVM 单元测试使用的 R 桩，真实 R 由 aapt2 link 生成。 */
final class R {
    static final class raw { static final int default_rules = 1; }
    static final class string { static final int app_name = 1; static final int service_label = 1; static final int service_description = 1; }
    static final class xml { static final int accessibility_service_config = 1; }
    static final class drawable { static final int ic_launcher_background = 1; }
    static final class mipmap { static final int ic_launcher = 1; static final int ic_launcher_foreground = 1; }
    static final class style { static final int AppTheme = 1; }
}
'@ | Set-Content -Path $rStub -Encoding UTF8

$appSources = @(
    "$root\app\src\main\java\com\lbx\skipad\RuleStore.java",
    "$root\app\src\main\java\com\lbx\skipad\SkipEngine.java"
)
foreach ($f in $appSources) { if (-not (Test-Path $f)) { Die "缺少源文件：$f" } }

Write-Host ">>> 编译被测类" -ForegroundColor Cyan
& $javac -encoding UTF-8 -nowarn -classpath "$androidJar" -d $appOut ($appSources + $rStub)
if ($LASTEXITCODE -ne 0) { Die "被测类编译失败" }

Write-Host ">>> 编译测试" -ForegroundColor Cyan
& $javac -encoding UTF-8 -nowarn `
    -classpath "$appOut;$androidJar" -d $testOut `
    "$root\app\src\test\java\com\lbx\skipad\EngineTest.java"
if ($LASTEXITCODE -ne 0) { Die "测试编译失败" }

Write-Host ">>> 运行测试`n" -ForegroundColor Cyan
# json jar 必须排在 android.jar 前面：android.jar 里的 org.json 是空壳
$cp = "$testOut;$appOut;$jsonJar;$androidJar;$root\app\src\main\res\raw"
& $java "-Dfile.encoding=UTF-8" -cp $cp com.lbx.skipad.EngineTest
$code = $LASTEXITCODE
Write-Host ""
if ($code -ne 0) { Die "测试未全部通过 (exit=$code)" }
Write-Host "测试全部通过" -ForegroundColor Green
