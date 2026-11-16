#!/usr/bin/env pwsh
# 把原版「双蒙版」视频转成带 Alpha 的动画 WebP + 独立音轨。
#
# 输入：assets/source/mj-drop-dual-mask.mp4 / mj-swing-dual-mask.mp4
# 输出：app/src/main/assets/mj/mj_drop.webp / mj_swing.webp / *.m4a
#
# 双蒙版布局：左半 = 白色 Alpha 蒙版，右半 = 彩色画面（白色不透明、黑色透明）。

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$ffmpeg = Join-Path $root "tools\ffmpeg\ffmpeg-master-latest-win64-gpl\bin\ffmpeg.exe"
if (-not (Test-Path $ffmpeg)) {
    throw "ffmpeg not found: $ffmpeg (download BtbN win64-gpl build into tools/ffmpeg)"
}

$width = 720
$outDir = Join-Path $root "app\src\main\assets\mj"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$filter = "[0:v]split=2[cs][ms];" +
    "[cs]crop=iw/2:ih:iw/2:0,scale=${width}:-2[color];" +
    "[ms]crop=iw/2:ih:0:0,format=gray,scale=${width}:-2[alpha];" +
    "[color][alpha]alphamerge[out]"

$jobs = @(
    @{ src = "mj-drop-dual-mask.mp4";  name = "mj_drop" },
    @{ src = "mj-swing-dual-mask.mp4"; name = "mj_swing" }
)

foreach ($job in $jobs) {
    $input = Join-Path $root ("assets\source\" + $job.src)
    $webp = Join-Path $outDir ($job.name + ".webp")
    $audio = Join-Path $outDir ($job.name + ".m4a")
    Write-Host "==> $($job.name)"

    & $ffmpeg -v error -y -i $input -filter_complex $filter -map "[out]" `
        -c:v libwebp_anim -lossless 0 -q:v 70 -compression_level 4 -loop 0 $webp
    if ($LASTEXITCODE -ne 0) { throw "webp transcode failed: $($job.src)" }

    & $ffmpeg -v error -y -i $input -vn -c:a aac -b:a 96k $audio
    if ($LASTEXITCODE -ne 0) { throw "audio extraction failed: $($job.src)" }
}

Get-ChildItem $outDir | Select-Object Name, Length
