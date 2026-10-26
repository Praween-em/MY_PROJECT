# Patch 2 integration build. Run this yourself; do not invoke it from an agent.
# Uses the project Gradle wrapper (9.3.1) and keeps caches on D: so C: does not fill up.
#
#   powershell -ExecutionPolicy Bypass -File scripts\android-build.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\android-build.ps1 -CompileOnly

param(
  [switch]$CompileOnly
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root "android"
$gradleHome = "D:\gradle-user-home"
$wrapperBat = Join-Path $android "gradlew.bat"

if (-not (Test-Path $wrapperBat)) {
  throw "Missing $wrapperBat — generate the Android project first (expo prebuild)."
}

New-Item -ItemType Directory -Force -Path $gradleHome | Out-Null
$env:GRADLE_USER_HOME = $gradleHome
$env:ORG_GRADLE_PROJECT_org.gradle.jvmargs = "-Xmx2g -XX:MaxMetaspaceSize=512m"

Write-Host "C free GB: $([math]::Round((Get-PSDrive C).Free/1GB, 2))"
Write-Host "D free GB: $([math]::Round((Get-PSDrive D).Free/1GB, 2))"
Write-Host "GRADLE_USER_HOME=$env:GRADLE_USER_HOME"
Write-Host "wrapper=$wrapperBat"

Set-Location $android
& $wrapperBat --version
if ($LASTEXITCODE -ne 0) { throw "Gradle wrapper failed to start" }

Write-Host "==== :app:compileDebugJavaWithJavac ===="
& $wrapperBat :app:compileDebugJavaWithJavac --max-workers=2
if ($LASTEXITCODE -ne 0) { throw "Java compile failed" }

if ($CompileOnly) {
  Write-Host "Compile-only requested. Skipping assembleDebug."
  exit 0
}

Write-Host "==== assembleDebug (embeds JS; do not skip Metro by using a stale debug APK) ===="
& $wrapperBat assembleDebug --max-workers=2
if ($LASTEXITCODE -ne 0) { throw "assembleDebug failed" }

$apk = Join-Path $android "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) {
  throw "APK missing: $apk"
}
Write-Host "APK $apk"
Write-Host "Next (on your machine, with a device):"
Write-Host "  adb install -r `"$apk`""
Write-Host "  adb shell dumpsys package com.ridio.app | findstr versionName"
Write-Host "  adb shell am start -n com.ridio.app/.MainActivity"
