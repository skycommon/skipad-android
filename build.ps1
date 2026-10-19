# 构建「跳过广告」APK —— 不依赖 Gradle，直接调用官方 Android SDK 工具链。
#
#   aapt2 编译资源 -> aapt2 链接生成 R.java -> javac -> d8 转 dex
#   -> aapt add 把 dex 塞进 APK -> zipalign 对齐 -> apksigner 签名
#
# 用法：  pwsh -File build.ps1
#
# 脚本可整个目录搬到任意位置（包括含中文的路径），所有路径都由脚本自身推导。
#
# 【重要】aapt2 在 Windows 上有个已知缺陷：无法打开路径中含非 ASCII 字符的文件，
# 例如把本工程放在 "D:\手机跳广告（安卓版）\源码" 下时，
#     aapt2 compile --dir <带中文的路径>
# 会报 "failed to open directory: 系统找不到指定的文件"。
# 因此当工程路径含中文时，本脚本会自动把构建过程搬到一个纯 ASCII 的临时目录里执行，
# 完成后再把产物拷回 out\。对使用者是透明的，产物位置不变。

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$root = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }

function Step($msg) { Write-Host "`n>>> $msg" -ForegroundColor Cyan }
function Die($msg) { Write-Host "!!! $msg" -ForegroundColor Red; exit 1 }

# ------------------------------------------------------------------ 自动定位工具链
# 目录名带版本号（jdk-17.0.2 / android-14 / android-34），自动查找，避免写死。

function FindOne($parent, $probe) {
    if (-not (Test-Path $parent)) { return $null }
    if (Test-Path (Join-Path $parent $probe)) { return $parent }
    $cand = Get-ChildItem $parent -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName $probe) } |
            Select-Object -First 1
    if ($cand) { return $cand.FullName }
    return $null
}

$srcTools = "$root\tools"
$jdk      = FindOne "$srcTools\jdk"         'bin\javac.exe'
$btools   = FindOne "$srcTools\build-tools" 'aapt2.exe'
$platDir  = FindOne "$srcTools\platform"    'android.jar'

if (-not $jdk)     { Die "未找到 JDK（需要 tools\jdk\*\bin\javac.exe）" }
if (-not $btools)  { Die "未找到 build-tools（需要 tools\build-tools\*\aapt2.exe）" }
if (-not $platDir) { Die "未找到 android.jar（需要 tools\platform\*\android.jar）" }

$javac     = "$jdk\bin\javac.exe"
$keytool   = "$jdk\bin\keytool.exe"
$aapt      = "$btools\aapt.exe"
$aapt2     = "$btools\aapt2.exe"
$d8bat     = "$btools\d8.bat"
$zipalign  = "$btools\zipalign.exe"
$apksigner = "$btools\apksigner.bat"

foreach ($f in @($javac,$keytool,$aapt,$aapt2,$d8bat,$zipalign,$apksigner)) {
    if (-not (Test-Path $f)) { Die "缺少构建工具：$f" }
}
# 这两个 .bat 依赖 JAVA_HOME
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

$srcMain  = "$root\app\src\main"
if (-not (Test-Path "$srcMain\AndroidManifest.xml")) { Die "缺少 AndroidManifest.xml：$srcMain" }

$pkgName     = "com.lbx.skipad"
$versionCode = "8"
$versionName = "3.4"
$minSdk      = "24"
$targetSdk   = "34"
$ksPass      = "skipad123"

$finalName = "跳过广告-v$versionName.apk"
$finalApk  = "$root\out\$finalName"

# ------------------------------------------------------------------ 决定构建目录
# 工程路径含非 ASCII 时，需要换到纯 ASCII 目录里做（aapt2 的限制）。
$rootIsAscii = ($root -notmatch '[^\x00-\x7F]')

$staging = $null
if ($rootIsAscii) {
    $work = $root
} else {
    $base = if ($env:TEMP -and $env:TEMP -notmatch '[^\x00-\x7F]') { $env:TEMP } else { 'C:\Windows\Temp' }
    $staging = Join-Path $base ("skipad-build-" + [guid]::NewGuid().ToString('N').Substring(0, 10))
    $work = $staging
    Write-Host "检测到工程路径含中文：" -ForegroundColor Yellow
    Write-Host "  $root" -ForegroundColor Yellow
    Write-Host "aapt2 不支持非 ASCII 路径，本次构建将在临时目录中进行：" -ForegroundColor Yellow
    Write-Host "  $work" -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path "$work\app\src\main" | Out-Null
    Copy-Item "$srcMain\*" "$work\app\src\main" -Recurse -Force
}

$out        = "$work\out"
$genDir     = "$work\gen"
$dexDir     = "$out\dex"
$classesDir = "$out\classes"
$resDir     = "$work\app\src\main\res"
$manifest   = "$work\app\src\main\AndroidManifest.xml"

# aapt2 的 -I 参数也必须是 ASCII 路径：把 android.jar 复制进构建目录。
$origAndroidJar = Join-Path $platDir 'android.jar'
if ($rootIsAscii) {
    # 工程路径本来就是 ASCII，直接用原路径即可，不必多复制一份
    $androidJar = $origAndroidJar
} else {
    $androidJar = "$work\android.jar"
    Copy-Item $origAndroidJar $androidJar -Force
}

Write-Host "`n工具链：" -ForegroundColor DarkGray
Write-Host "  JDK        $jdk" -ForegroundColor DarkGray
Write-Host "  build-tools $btools" -ForegroundColor DarkGray
Write-Host "  android.jar $platDir\android.jar" -ForegroundColor DarkGray

function Cleanup {
    if ($staging -and (Test-Path $staging)) {
        Remove-Item -Recurse -Force $staging -ErrorAction SilentlyContinue
    }
}

try {
    # -------------------------------------------------------------- 清理
    Step "清理输出目录"
    if (Test-Path $out)    { Remove-Item -Recurse -Force $out }
    if (Test-Path $genDir) { Remove-Item -Recurse -Force $genDir }
    New-Item -ItemType Directory -Force -Path $out, $genDir, $dexDir, $classesDir | Out-Null

    # -------------------------------------------------------------- 1. aapt2 compile
    Step "aapt2 compile —— 编译资源"
    $compiledZip = "$out\compiled_res.zip"
    # 用显式文件列表而不是 --dir，这样即使在临时目录外也能稳定工作
    $resFiles = Get-ChildItem -Path $resDir -Recurse -File | ForEach-Object { $_.FullName }
    if (-not $resFiles) { Die "资源目录为空：$resDir" }
    & $aapt2 compile @resFiles -o $compiledZip
    if ($LASTEXITCODE -ne 0) { Die "aapt2 compile 失败" }
    Write-Host "    资源编译完成：$([math]::Round((Get-Item $compiledZip).Length/1KB,1)) KB（$($resFiles.Count) 个文件）"

    # -------------------------------------------------------------- 2. aapt2 link
    Step "aapt2 link —— 链接资源并生成 R.java"
    $baseApk = "$out\base-unsigned.apk"
    & $aapt2 link `
        -o $baseApk `
        -I $androidJar `
        --manifest $manifest `
        --java $genDir `
        --min-sdk-version $minSdk `
        --target-sdk-version $targetSdk `
        --version-code $versionCode `
        --version-name $versionName `
        --no-version-vectors `
        $compiledZip
    if ($LASTEXITCODE -ne 0) { Die "aapt2 link 失败" }

    $rJava = Join-Path $genDir "$($pkgName -replace '\.','\')\R.java"
    if (-not (Test-Path $rJava)) { Die "未找到生成的 R.java：$rJava" }

    # -------------------------------------------------------------- 3. javac
    Step "javac —— 编译 Java 源码"
    $sources = @()
    $sources += Get-ChildItem -Path "$work\app\src\main\java" -Recurse -Filter *.java |
                ForEach-Object { $_.FullName }
    $sources += $rJava

    # 注意：javac 的 @argfile 会把反斜杠当转义字符，路径必须统一用正斜杠。
    function ToSlash($p) { return ($p -replace '\\', '/') }

    $argFile = "$out\javac-args.txt"
    $lines = @(
        "-encoding", "UTF-8",
        "--release", "11",
        "-nowarn",
        "-d", "`"$(ToSlash $classesDir)`"",
        "-classpath", "`"$(ToSlash $androidJar)`""
    )
    foreach ($s in $sources) { $lines += "`"$(ToSlash $s)`"" }
    Set-Content -Path $argFile -Value $lines -Encoding UTF8

    & $javac "@$argFile"
    if ($LASTEXITCODE -ne 0) { Die "javac 编译失败" }
    $classCount = (Get-ChildItem -Path $classesDir -Recurse -Filter *.class).Count
    Write-Host "    编译出 $classCount 个 class 文件"

    # -------------------------------------------------------------- 4. d8
    Step "d8 —— 转换为 Dalvik 字节码"
    $classFiles = Get-ChildItem -Path $classesDir -Recurse -Filter *.class |
                  ForEach-Object { $_.FullName }
    $d8ArgsFile = "$out\d8-args.txt"
    # d8.bat 直接把参数交给 java，@argfile 里不能带引号
    # 输出目录必须已存在，否则 d8 会把它当成想创建的 zip 名
    $dl = @("--release", "--min-api", $minSdk, "--lib", (ToSlash $androidJar),
            "--output", (ToSlash $dexDir))
    foreach ($c in $classFiles) { $dl += (ToSlash $c) }
    Set-Content -Path $d8ArgsFile -Value $dl -Encoding ASCII

    & $d8bat "@$d8ArgsFile"
    if ($LASTEXITCODE -ne 0) { Die "d8 转换失败" }
    $dexPath = "$dexDir\classes.dex"
    if (-not (Test-Path $dexPath)) { Die "未生成 classes.dex" }
    Write-Host "    classes.dex：$([math]::Round((Get-Item $dexPath).Length/1KB,1)) KB"

    # -------------------------------------------------------------- 5. 把 dex 塞进 APK
    Step "aapt add —— 把 classes.dex 加入 APK"
    # 用 aapt add 而不是重写 zip：它只追加新条目，不会动 resources.arsc 的
    # 「不压缩 + 4 字节对齐」状态（targetSdk 30+ 的硬性要求）。
    Push-Location $dexDir
    try {
        & $aapt add $baseApk "classes.dex"
        if ($LASTEXITCODE -ne 0) { Die "aapt add 失败" }
    } finally {
        Pop-Location
    }
    Write-Host "    已加入 classes.dex"

    # -------------------------------------------------------------- 6. zipalign
    Step "zipalign —— 4 字节对齐"
    $alignedApk = "$out\app-aligned.apk"
    & $zipalign -f -p 4 $baseApk $alignedApk
    if ($LASTEXITCODE -ne 0) { Die "zipalign 失败" }

    # -------------------------------------------------------------- 7. 签名
    Step "apksigner —— 签名"
    # 密钥库放在工程目录（若工程路径含中文，就放在 ASCII 的 out 旁边再拷回来）
    $ksDir = "$root\keystore"
    $ks = "$ksDir\skipad.jks"
    $ksWork = if ($rootIsAscii) { $ks } else { "$work\skipad.jks" }
    if ($staging -and (Test-Path $ks)) { Copy-Item $ks $ksWork -Force }

    if (-not (Test-Path $ksWork)) {
        New-Item -ItemType Directory -Force -Path (Split-Path $ksWork) | Out-Null
        Write-Host "    生成签名密钥库（仅首次）..."
        & $keytool -genkeypair -v `
            -keystore $ksWork `
            -alias skipad `
            -keyalg RSA -keysize 2048 -validity 10950 `
            -storepass $ksPass -keypass $ksPass `
            -dname "CN=SkipAd, OU=Personal, O=LBX, L=CN, ST=CN, C=CN" 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { Die "生成密钥库失败" }
    }

    $signedApk = "$out\signed.apk"
    & $apksigner sign `
        --ks $ksWork `
        --ks-key-alias skipad `
        --ks-pass "pass:$ksPass" `
        --key-pass "pass:$ksPass" `
        --v1-signing-enabled true `
        --v2-signing-enabled true `
        --v4-signing-enabled false `
        --out $signedApk `
        $alignedApk
    if ($LASTEXITCODE -ne 0) { Die "签名失败" }

    Step "校验签名"
    & $apksigner verify --print-certs $signedApk
    if ($LASTEXITCODE -ne 0) { Die "签名校验失败" }
    & $zipalign -c -v 4 $signedApk | Out-Null
    if ($LASTEXITCODE -ne 0) { Die "对齐校验失败" }

    # -------------------------------------------------------------- 8. 产物就位
    New-Item -ItemType Directory -Force -Path "$root\out" | Out-Null
    Copy-Item $signedApk $finalApk -Force
    # 首次构建出来的密钥库要落到工程里，方便以后升级用同一个签名
    if ($staging) {
        New-Item -ItemType Directory -Force -Path $ksDir | Out-Null
        Copy-Item $ksWork $ks -Force
    }

    $size = [math]::Round((Get-Item $finalApk).Length / 1KB, 1)
    Write-Host "`n===== 构建成功 =====" -ForegroundColor Green
    Write-Host "APK : $finalApk"
    Write-Host "大小: $size KB"
} finally {
    Cleanup
}
