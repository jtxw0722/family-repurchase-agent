param(
    [string]$ServerName = "family-repurchase-agent",
    [string]$ApiBaseUrl = "http://localhost:8080",
    [string]$JavaCommand = "java",
    [string]$McpJar = "",
    [switch]$Show
)

$ErrorActionPreference = "Stop"

$OpenClawDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Resolve-Path (Join-Path $OpenClawDir "..\..")

if ([string]::IsNullOrWhiteSpace($McpJar)) {
    $McpTargetDir = Join-Path $ProjectRoot "adapters\mcp\family-repurchase-mcp-java-server\target"

    $McpJarFile = Get-ChildItem -Path $McpTargetDir -Filter "family-repurchase-mcp-java-server-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "original-*" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $McpJarFile) {
        throw "MCP Server jar not found. Please run: mvn -f adapters\mcp\family-repurchase-mcp-java-server\pom.xml package"
    }

    $McpJarPath = $McpJarFile.FullName
} else {
    $McpJarPath = (Resolve-Path $McpJar).Path
}

$ExamplesDir = Join-Path $ProjectRoot "examples"
$DataImportsDir = Join-Path $ProjectRoot "data\imports"
$ImportsDir = Join-Path $ProjectRoot "imports"

$config = @{
    command = $JavaCommand
    args = @(
        "-jar",
        $McpJarPath
    )
    env = @{
        FAMILY_AGENT_API_BASE_URL = $ApiBaseUrl
        FAMILY_AGENT_PROJECT_ROOT = $ProjectRoot.Path
        FAMILY_AGENT_IMPORT_ALLOWED_DIRS = "$ExamplesDir;$DataImportsDir;$ImportsDir"
    }
    cwd = $ProjectRoot.Path
}

$json = $config | ConvertTo-Json -Depth 20 -Compress

# Windows PowerShell 在把 JSON 作为 CLI 参数传给 openclaw mcp set 时，
# 可能会剥离 JSON 内部双引号，导致 OpenClaw 解析失败。
# 因此这里对双引号进行转义。
$jsonEscaped = $json -replace '"', '\"'

Write-Host "Registering OpenClaw MCP server: $ServerName" -ForegroundColor Cyan
Write-Host "Project root: $($ProjectRoot.Path)"
Write-Host "MCP jar: $McpJarPath"
Write-Host ""

openclaw mcp set $ServerName $jsonEscaped

if ($LASTEXITCODE -ne 0) {
    throw "openclaw mcp set failed."
}

Write-Host ""
Write-Host "OpenClaw MCP server definition registered: $ServerName" -ForegroundColor Green

if ($Show) {
    Write-Host ""
    openclaw mcp show $ServerName
}