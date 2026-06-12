<#
.SYNOPSIS
    Register family-repurchase-agent MCP server for OpenClaw.

.DESCRIPTION
    This script registers a local stdio MCP jar into OpenClaw.
    It is designed for the Jenkins + SSH Tunnel deployment mode:
      - Spring Boot backend runs on the remote server and is reached via local SSH tunnel.
      - MCP Server jar runs on the Windows local machine.
      - OpenClaw launches the local MCP jar through java -jar.

    Priority for MCP jar path:
      1. -McpJar parameter
      2. FAMILY_AGENT_MCP_JAR environment variable
      3. interactive input
      4. default runtime path

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\adapters\openclaw\openclaw-set-mcp.ps1 -Show

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\adapters\openclaw\openclaw-set-mcp.ps1 `
      -McpJar "D:\mcp-runtime\family-repurchase-agent\family-repurchase-mcp-java-server.jar" `
      -Reload `
      -Show
#>

param(
    [string]$ServerName = "family-repurchase-agent",
    [string]$McpJar = "",
    [string]$ApiBaseUrl = "http://127.0.0.1:8080",
    [string]$ProjectRoot = "",
    [string]$OpenClawCommand = "openclaw",
    [switch]$Reload,
    [switch]$Show,
    [switch]$Probe,
    [switch]$NonInteractive
)

$ErrorActionPreference = "Stop"

function Write-Info {
    param([string]$Message)
    Write-Host $Message -ForegroundColor Cyan
}

function Write-Warn {
    param([string]$Message)
    Write-Host $Message -ForegroundColor Yellow
}

function Write-Err {
    param([string]$Message)
    Write-Host $Message -ForegroundColor Red
}

function Resolve-ProjectRoot {
    param([string]$InputProjectRoot)

    if (-not [string]::IsNullOrWhiteSpace($InputProjectRoot)) {
        return (Resolve-Path -LiteralPath $InputProjectRoot).Path
    }

    if (-not [string]::IsNullOrWhiteSpace($PSScriptRoot)) {
        $openclawDir = Split-Path -Parent $PSScriptRoot
        $rootDir = Split-Path -Parent $openclawDir
        if (Test-Path -LiteralPath (Join-Path $rootDir "pom.xml")) {
            return (Resolve-Path -LiteralPath $rootDir).Path
        }
    }

    return (Get-Location).Path
}

function Resolve-McpJarPath {
    param(
        [string]$InputMcpJar,
        [switch]$InputNonInteractive
    )

    $defaultJar = "D:\mcp-runtime\family-repurchase-agent\family-repurchase-mcp-java-server.jar"
    $envJar = [Environment]::GetEnvironmentVariable("FAMILY_AGENT_MCP_JAR", "Process")

    if ([string]::IsNullOrWhiteSpace($envJar)) {
        $envJar = [Environment]::GetEnvironmentVariable("FAMILY_AGENT_MCP_JAR", "User")
    }

    if (-not [string]::IsNullOrWhiteSpace($InputMcpJar)) {
        return $InputMcpJar.Trim('"')
    }

    if (-not [string]::IsNullOrWhiteSpace($envJar)) {
        return $envJar.Trim('"')
    }

    if ($InputNonInteractive) {
        return $defaultJar
    }

    Write-Host "Please input MCP Server jar path."
    Write-Host "Priority: -McpJar parameter > FAMILY_AGENT_MCP_JAR env > interactive input."
    $inputValue = Read-Host "MCP jar path [$defaultJar]"

    if ([string]::IsNullOrWhiteSpace($inputValue)) {
        return $defaultJar
    }

    return $inputValue.Trim('"')
}

function Assert-ExecutableExists {
    param([string]$CommandName)

    $resolved = Get-Command $CommandName -ErrorAction SilentlyContinue
    if ($null -eq $resolved) {
        throw "Command not found: $CommandName. Please make sure OpenClaw CLI is installed and available in PATH."
    }
}

function Assert-FileExists {
    param([string]$FilePath)

    if (-not (Test-Path -LiteralPath $FilePath -PathType Leaf)) {
        throw "MCP jar not found: $FilePath"
    }
}

function Invoke-OpenClaw {
    param([string[]]$Arguments)

    Write-Info ("openclaw " + ($Arguments -join " "))
    & $OpenClawCommand @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "openclaw command failed. Exit code: $LASTEXITCODE"
    }
}

$resolvedProjectRoot = Resolve-ProjectRoot -InputProjectRoot $ProjectRoot
$resolvedMcpJar = Resolve-McpJarPath -InputMcpJar $McpJar -InputNonInteractive:$NonInteractive
$resolvedMcpJar = [System.IO.Path]::GetFullPath($resolvedMcpJar)

Assert-ExecutableExists -CommandName $OpenClawCommand
Assert-FileExists -FilePath $resolvedMcpJar

$examplesDir = Join-Path $resolvedProjectRoot "examples"
$dataImportsDir = Join-Path $resolvedProjectRoot "data\imports"
$importsDir = Join-Path $resolvedProjectRoot "imports"
$allowedDirs = $examplesDir + ";" + $dataImportsDir + ";" + $importsDir

Write-Info "Registering OpenClaw MCP server: $ServerName"
Write-Info "Project root: $resolvedProjectRoot"
Write-Info "API base URL: $ApiBaseUrl"
Write-Info "MCP jar: $resolvedMcpJar"
Write-Info "Allowed import dirs: $allowedDirs"

try {
    & $OpenClawCommand mcp unset $ServerName | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Warn "Existing MCP server was not removed. It may not exist yet. Continuing."
    }
} catch {
    Write-Warn "Existing MCP server was not removed. It may not exist yet. Continuing."
}

$addArgs = @(
    "mcp",
    "add",
    $ServerName,
    "--command",
    "java",
    "--arg",
    "-jar",
    "--arg",
    $resolvedMcpJar,
    "--cwd",
    $resolvedProjectRoot,
    "--env",
    "FAMILY_AGENT_API_BASE_URL=$ApiBaseUrl",
    "--env",
    "FAMILY_AGENT_PROJECT_ROOT=$resolvedProjectRoot",
    "--env",
    "FAMILY_AGENT_IMPORT_ALLOWED_DIRS=$allowedDirs"
)

Invoke-OpenClaw -Arguments $addArgs

if ($Reload) {
    Write-Info "Reloading OpenClaw MCP runtime cache."
    Invoke-OpenClaw -Arguments @("mcp", "reload")
}

if ($Show) {
    Write-Info "Showing registered MCP server."
    Invoke-OpenClaw -Arguments @("mcp", "show", $ServerName)
}

if ($Probe) {
    Write-Info "Probing registered MCP server."
    Invoke-OpenClaw -Arguments @("mcp", "probe", $ServerName)
}

Write-Info "Done."
