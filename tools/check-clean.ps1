# Fails (exit 1) if any tracked or staged file mentions vendor names, private
# endpoints or credential-looking identifiers carried over from the source app.
# Run before every push:   powershell -ExecutionPolicy Bypass -File tools/check-clean.ps1

$ErrorActionPreference = 'Stop'
$root = git rev-parse --show-toplevel
Set-Location $root

$pattern = '\bather\b|mether|mather|mappls|cerberus|cloudflare|restAPIKey|clientSecret|atlasClient|firebase_token|Uare-not-allowed'

# Search the index (staged + tracked), excluding this script.
# git grep exit codes: 0 = matches, 1 = no matches, anything else = error (fail closed).
$ErrorActionPreference = 'Continue'
$hits = git grep --cached -n -i -I -P $pattern -- . ':!tools/check-clean.ps1'
$code = $LASTEXITCODE
$ErrorActionPreference = 'Stop'

if ($code -gt 1) {
    Write-Host "check-clean: ERROR - git grep failed (exit $code)" -ForegroundColor Red
    exit 2
}
if ($code -eq 0) {
    Write-Host "check-clean: FAILED - forbidden terms found:" -ForegroundColor Red
    $hits | ForEach-Object { Write-Host "  $_" }
    exit 1
}

# Large files (> 5 MB) usually mean an asset slipped in.
$big = git ls-files -s | ForEach-Object {
    $path = ($_ -split "`t", 2)[1]
    if ((Test-Path $path) -and (Get-Item $path).Length -gt 5MB) { $path }
}
if ($big) {
    Write-Host "check-clean: FAILED - files larger than 5 MB:" -ForegroundColor Red
    $big | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "check-clean: OK" -ForegroundColor Green
exit 0
