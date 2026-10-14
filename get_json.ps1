# 下载用于 JVM 单元测试的 org.json 实现（android.jar 里的 org.json 是运行时会抛异常的桩）
$ErrorActionPreference='Stop'
$dest = "D:\AI\DSH\_skipad\tools\lib"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$url = "https://maven.aliyun.com/repository/public/org/json/json/20231013/json-20231013.jar"
$out = "$dest\json-20231013.jar"
if (Test-Path $out) { Write-Host "已存在"; exit 0 }
# 注意：本机 Invoke-WebRequest 走 TLS 会认证失败，统一改用 node 下载
node "D:\AI\DSH\_skipad\dl.js" $url $out
if ($LASTEXITCODE -ne 0) { throw "下载失败" }
Write-Host "OK $([math]::Round((Get-Item $out).Length/1KB,1)) KB"
