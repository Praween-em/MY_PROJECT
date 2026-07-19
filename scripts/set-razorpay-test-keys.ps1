# Paste your Razorpay TEST keys into env files
# Run from project root:  .\scripts\set-razorpay-test-keys.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

Write-Host "`nPrime Clicker — Razorpay TEST key setup`n" -ForegroundColor Cyan
Write-Host "Get keys from: https://dashboard.razorpay.com/app/keys`n"

$keyId = Read-Host "Razorpay Test Key ID (rzp_test_...)"
$secret = Read-Host "Razorpay Test Key Secret"
$webhook = Read-Host "Webhook Secret (press Enter to skip — add after first deploy)"

if (-not $keyId.StartsWith("rzp_test_")) {
  Write-Warning "Key ID should start with rzp_test_ for test mode"
}

# App .env
$appEnv = @"
# Auto-generated — TEST keys
EXPO_PUBLIC_RAZORPAY_MODE=test
EXPO_PUBLIC_API_URL=http://localhost:3000
EXPO_PUBLIC_RAZORPAY_KEY_ID=$keyId
"@
Set-Content -Path (Join-Path $root ".env") -Value $appEnv -Encoding UTF8
Write-Host "  Updated .env" -ForegroundColor Green

# Backend .env.dev
$wh = if ($webhook) { $webhook } else { "PASTE_WEBHOOK_SECRET_AFTER_DEPLOY" }
$backendEnv = @"
AWS_REGION=ap-south-1
DYNAMODB_TABLE=primeclicker-users-dev
PORT=3000
NODE_ENV=development

RAZORPAY_KEY_ID=$keyId
RAZORPAY_KEY_SECRET=$secret
RAZORPAY_WEBHOOK_SECRET=$wh
"@
$backendPath = Join-Path $root "backend"
Set-Content -Path (Join-Path $backendPath ".env.dev") -Value $backendEnv -Encoding UTF8
Set-Content -Path (Join-Path $backendPath ".env") -Value $backendEnv -Encoding UTF8
Write-Host "  Updated backend/.env.dev and backend/.env" -ForegroundColor Green

Write-Host "`nNext steps:" -ForegroundColor Yellow
Write-Host "  1. cd backend && npm run deploy"
Write-Host "  2. Copy API URL into .env as EXPO_PUBLIC_API_URL"
Write-Host "  3. Razorpay Dashboard -> Webhooks -> add secret to backend/.env.dev -> redeploy"
Write-Host "  4. npx expo run:android`n"
