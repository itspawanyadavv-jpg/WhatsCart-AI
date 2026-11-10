$ErrorActionPreference = "Stop"

Write-Host "Starting DRC WhatsApp AI Ordering Bot..." -ForegroundColor Cyan

if (-not (Test-Path ".env") -and (Test-Path ".env.example")) {
    Copy-Item ".env.example" ".env"
    Write-Host "Created .env from .env.example" -ForegroundColor Yellow
}

if (Test-Path ".env") {
    Get-Content ".env" | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line -match "^([^=]+)=(.*)$") {
            $key = $matches[1].Trim()
            $val = $matches[2].Trim()
            if (-not [string]::IsNullOrWhiteSpace($val)) {
                [Environment]::SetEnvironmentVariable($key, $val, "Process")
            }
        }
    }
    Write-Host "Loaded non-empty variables from .env" -ForegroundColor Yellow
}

if (-not (Test-Path "frontend\node_modules")) {
    Write-Host "Installing frontend dependencies..." -ForegroundColor Yellow
    Push-Location "frontend"
    npm install
    Pop-Location
}

Write-Host "Launching Spring Boot API on http://localhost:8090 ..." -ForegroundColor Green
$backendJob = Start-Process powershell -PassThru -ArgumentList "-NoExit", "-Command", "cd '$PSScriptRoot\backend'; mvn spring-boot:run"

Write-Host "Launching React Web Simulator on http://localhost:5175 ..." -ForegroundColor Green
$frontendJob = Start-Process powershell -PassThru -ArgumentList "-NoExit", "-Command", "cd '$PSScriptRoot\frontend'; npm run dev"

Write-Host ""
Write-Host "Web Simulator UI : http://localhost:5175" -ForegroundColor Cyan
Write-Host "Swagger API Docs : http://localhost:8090/swagger-ui.html" -ForegroundColor Cyan
Write-Host "Health Endpoint  : http://localhost:8090/api/health" -ForegroundColor Cyan

