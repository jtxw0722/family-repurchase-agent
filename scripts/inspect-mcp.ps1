param(
    [string]$ApiBaseUrl = "http://127.0.0.1:8080",
    [string[]]$ImportAllowedDirs = @("examples", "data/imports", "imports")
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$mcpTarget = Join-Path $repoRoot "adapters\mcp\family-repurchase-mcp-java-server\target"
$mcpJar = Get-ChildItem -Path $mcpTarget -Filter "family-repurchase-mcp-java-server-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike "*.original" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($null -eq $mcpJar) {
    Write-Error "MCP jar not found. Run: mvn -pl adapters/mcp/family-repurchase-mcp-java-server -am package"
    exit 1
}

$resolvedImportDirs = $ImportAllowedDirs | ForEach-Object {
    if ([System.IO.Path]::IsPathRooted($_)) {
        $_
    } else {
        Join-Path $repoRoot $_
    }
}

$env:FAMILY_AGENT_API_BASE_URL = $ApiBaseUrl
$env:FAMILY_AGENT_IMPORT_ALLOWED_DIRS = $resolvedImportDirs -join [System.IO.Path]::PathSeparator

Write-Host "Starting MCP Inspector for Family Repurchase Agent."
Write-Host "Backend URL: $env:FAMILY_AGENT_API_BASE_URL"
Write-Host "Allowed import dirs: $env:FAMILY_AGENT_IMPORT_ALLOWED_DIRS"
Write-Host "MCP jar: $($mcpJar.FullName)"
Write-Host "Spring Boot backend is not started by this script."
Write-Host ""

$mcpJarForInspector = $mcpJar.FullName.Replace('\', '/')
& npx -y "@modelcontextprotocol/inspector" -- java -jar $mcpJarForInspector
