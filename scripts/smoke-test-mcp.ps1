param(
    [int]$TimeoutMs = 30000,
    [string]$McpJarPath = "",
    [string]$JavaExe = "java"
)

$ErrorActionPreference = "Stop"

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

if ([string]::IsNullOrWhiteSpace($McpJarPath)) {
    $McpJarPath = Join-Path $repoRoot "adapters/mcp/family-repurchase-mcp-java-server/target/family-repurchase-mcp-java-server.jar"

    if (-not (Test-Path $McpJarPath)) {
        throw "MCP jar not found: $McpJarPath. Run: mvn -f adapters/mcp/family-repurchase-mcp-java-server/pom.xml package"
    }

    $McpJarPath = (Resolve-Path $McpJarPath).Path
} else {
    $McpJarPath = (Resolve-Path $McpJarPath).Path
}

Write-Host "Using MCP jar: $McpJarPath"
Write-Host "Using Java executable: $JavaExe"
Write-Host "Repo root: $repoRoot"
Write-Host "TimeoutMs: $TimeoutMs"

$initializeRequest = @{
    jsonrpc = "2.0"
    id = 1
    method = "initialize"
    params = @{
        protocolVersion = "2024-11-05"
        capabilities = @{}
        clientInfo = @{
            name = "ci-smoke-test"
            version = "1.0.0"
        }
    }
} | ConvertTo-Json -Compress -Depth 10

$requests = @(
    $initializeRequest,
    '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}',
    '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
)

$inputText = ($requests -join [Environment]::NewLine) + [Environment]::NewLine

$processInfo = [System.Diagnostics.ProcessStartInfo]::new()
$processInfo.FileName = $JavaExe
$processInfo.Arguments = "-jar `"$McpJarPath`""
$processInfo.WorkingDirectory = $repoRoot
$processInfo.RedirectStandardInput = $true
$processInfo.RedirectStandardOutput = $true
$processInfo.RedirectStandardError = $true
$processInfo.UseShellExecute = $false
$processInfo.CreateNoWindow = $true

if ($processInfo.PSObject.Properties.Name -contains "StandardOutputEncoding") {
    $processInfo.StandardOutputEncoding = [System.Text.Encoding]::UTF8
}

if ($processInfo.PSObject.Properties.Name -contains "StandardErrorEncoding") {
    $processInfo.StandardErrorEncoding = [System.Text.Encoding]::UTF8
}

$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $processInfo

function Stop-McpProcess {
    if ($null -ne $process -and -not $process.HasExited) {
        try {
            $process.Kill()
        } catch {
            # ignored
        }

        try {
            $process.WaitForExit(5000) | Out-Null
        } catch {
            # ignored
        }
    }
}

function Wait-TaskResult {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Task,

        [int]$WaitMs = 5000
    )

    try {
        if (-not $Task.Wait($WaitMs)) {
            return "<task did not complete within ${WaitMs}ms>"
        }

        return $Task.Result
    } catch {
        return "Failed to read task result: $($_.Exception.Message)"
    }
}

function Write-McpDiagnostics {
    param(
        [string]$Reason,
        [string]$Stdout,
        [string]$Stderr
    )

    Write-Host "===== MCP diagnostics: $Reason ====="

    if ($null -ne $process) {
        Write-Host "HasExited: $($process.HasExited)"

        if ($process.HasExited) {
            Write-Host "ExitCode: $($process.ExitCode)"
        } else {
            Write-Host "MCP process is still running."
        }
    }

    Write-Host "===== MCP stdout captured ====="
    if ([string]::IsNullOrWhiteSpace($Stdout)) {
        Write-Host "<empty>"
    } else {
        Write-Host $Stdout
    }

    Write-Host "===== MCP stderr captured ====="
    if ([string]::IsNullOrWhiteSpace($Stderr)) {
        Write-Host "<empty>"
    } else {
        Write-Host $Stderr
    }
}

try {
    if (-not $process.Start()) {
        throw "Failed to start MCP Server process."
    }

    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()

    $process.StandardInput.Write($inputText)
    $process.StandardInput.Flush()
    $process.StandardInput.Close()

    $exited = $process.WaitForExit($TimeoutMs)

    if (-not $exited) {
        Stop-McpProcess
    }

    $stdout = Wait-TaskResult -Task $stdoutTask -WaitMs 5000
    $stderr = Wait-TaskResult -Task $stderrTask -WaitMs 5000

    if (-not $exited) {
        Write-McpDiagnostics -Reason "MCP process did not exit before timeout" -Stdout $stdout -Stderr $stderr
    }

    $jsonLines = @($stdout -split "`r?`n" | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_)
    })

    if ($jsonLines.Count -eq 0) {
        Write-McpDiagnostics -Reason "no stdout JSON-RPC response" -Stdout $stdout -Stderr $stderr
        throw "No JSON-RPC response found from MCP Server."
    }

    $responses = @()

    foreach ($line in $jsonLines) {
        try {
            $responses += ($line | ConvertFrom-Json)
        } catch {
            Write-McpDiagnostics -Reason "non-json stdout" -Stdout $stdout -Stderr $stderr
            throw "MCP server wrote non-JSON stdout line: $line"
        }
    }

    $initializeResponse = $responses | Where-Object { $_.id -eq 1 } | Select-Object -First 1

    if ($null -eq $initializeResponse) {
        Write-McpDiagnostics -Reason "initialize response not found" -Stdout $stdout -Stderr $stderr
        throw "initialize response not found."
    }

    if ($initializeResponse.error) {
        Write-McpDiagnostics -Reason "initialize returned error" -Stdout $stdout -Stderr $stderr
        throw "initialize returned error: $($initializeResponse.error | ConvertTo-Json -Compress -Depth 10)"
    }

    if ($null -eq $initializeResponse.result) {
        Write-McpDiagnostics -Reason "initialize response missing result" -Stdout $stdout -Stderr $stderr
        throw "initialize response did not include result."
    }

    $toolsResponse = $responses | Where-Object { $_.id -eq 2 } | Select-Object -First 1

    if ($null -eq $toolsResponse) {
        Write-McpDiagnostics -Reason "tools/list response not found" -Stdout $stdout -Stderr $stderr
        throw "tools/list response not found."
    }

    if ($toolsResponse.error) {
        Write-McpDiagnostics -Reason "tools/list returned error" -Stdout $stdout -Stderr $stderr
        throw "tools/list returned error: $($toolsResponse.error | ConvertTo-Json -Compress -Depth 10)"
    }

    if ($null -eq $toolsResponse.result) {
        Write-McpDiagnostics -Reason "tools/list response missing result" -Stdout $stdout -Stderr $stderr
        throw "tools/list response did not include result."
    }

    $tools = @($toolsResponse.result.tools)
    $toolNames = @($tools | ForEach-Object { $_.name })

    foreach ($expectedTool in @("import_file", "record_purchase", "compare_price", "get_price_baseline", "search_purchase_records", "generate_report")) {
        if ($toolNames -notcontains $expectedTool) {
            throw "tools/list missing tool: $expectedTool"
        }
    }

    function Find-Tool {
        param(
            [Parameter(Mandatory = $true)]
            [string]$Name
        )

        $tool = $tools | Where-Object { $_.name -eq $Name } | Select-Object -First 1

        if ($null -eq $tool) {
            throw "tools/list missing tool: $Name"
        }

        return $tool
    }

    Write-Host "MCP smoke test passed. Tools: $($toolNames -join ', ')"
    exit 0
} finally {
    Stop-McpProcess
}
