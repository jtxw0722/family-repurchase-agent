param(
    [int]$TimeoutMs = 30000
)

$ErrorActionPreference = "Stop"

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$mcpTarget = Join-Path $repoRoot "adapters/mcp/family-repurchase-mcp-java-server/target"
$mcpJar = Get-ChildItem -Path $mcpTarget -Filter "family-repurchase-mcp-java-server-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike "original-*" -and $_.Name -notlike "*.original" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($null -eq $mcpJar) {
    throw "MCP jar not found. Run: mvn -f adapters/mcp/family-repurchase-mcp-java-server/pom.xml package"
}

Write-Host "Using MCP jar: $($mcpJar.FullName)"

$processInfo = [System.Diagnostics.ProcessStartInfo]::new()
$processInfo.FileName = "java"
if ($null -ne $processInfo.ArgumentList) {
    $processInfo.ArgumentList.Add("-jar")
    $processInfo.ArgumentList.Add($mcpJar.FullName)
} else {
    $processInfo.Arguments = "-jar `"$($mcpJar.FullName)`""
}
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

$process = [System.Diagnostics.Process]::Start($processInfo)

try {
    function Send-McpRequest {
        param(
            [Parameter(Mandatory = $true)]
            [string]$Json
        )

        $process.StandardInput.WriteLine($Json)
        $process.StandardInput.Flush()
    }

    function Read-McpResponse {
        param(
            [Parameter(Mandatory = $true)]
            [int]$Id
        )

        $deadline = (Get-Date).AddMilliseconds($TimeoutMs)
        while ((Get-Date) -lt $deadline) {
            if ($process.HasExited) {
                $stderr = $process.StandardError.ReadToEnd()
                throw "MCP server exited before response id=$Id. exitCode=$($process.ExitCode) stderr: $stderr"
            }

            $remaining = [Math]::Max(1, [int]($deadline - (Get-Date)).TotalMilliseconds)
            $readTask = $process.StandardOutput.ReadLineAsync()
            if (-not $readTask.Wait($remaining)) {
                throw "Timed out waiting for MCP response id=$Id"
            }

            $line = $readTask.Result
            if ([string]::IsNullOrWhiteSpace($line)) {
                continue
            }

            try {
                $response = $line | ConvertFrom-Json
            } catch {
                throw "MCP server wrote non-JSON stdout line: $line"
            }

            if ($response.id -eq $Id) {
                return $response
            }
        }

        throw "Timed out waiting for MCP response id=$Id"
    }

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

    Send-McpRequest -Json $initializeRequest

    $initializeResponse = Read-McpResponse -Id 1
    if ($null -eq $initializeResponse.result) {
        throw "initialize response did not include result"
    }

    Send-McpRequest -Json '{"jsonrpc":"2.0","method":"notifications/initialized"}'
    Send-McpRequest -Json '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

    $toolsResponse = Read-McpResponse -Id 2
    if ($null -eq $toolsResponse.result) {
        throw "tools/list response did not include result"
    }

    $tools = @($toolsResponse.result.tools)
    $toolNames = @($tools | ForEach-Object { $_.name })

    foreach ($expectedTool in @("import_file", "compare_price", "get_price_baseline", "generate_report")) {
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

    function Assert-OutputSchemaProperties {
        param(
            [Parameter(Mandatory = $true)]
            [object]$Tool,

            [Parameter(Mandatory = $true)]
            [string[]]$Properties
        )

        if ($null -eq $Tool.outputSchema) {
            throw "tool '$($Tool.name)' missing outputSchema"
        }

        if ($null -eq $Tool.outputSchema.properties) {
            throw "tool '$($Tool.name)' outputSchema missing properties"
        }

        $actualProperties = @($Tool.outputSchema.properties.PSObject.Properties.Name)
        foreach ($property in $Properties) {
            if ($actualProperties -notcontains $property) {
                throw "tool '$($Tool.name)' outputSchema missing property: $property"
            }
        }
    }

    $generateReportTool = Find-Tool -Name "generate_report"
    Assert-OutputSchemaProperties -Tool $generateReportTool -Properties @(
        "month",
        "recordCount",
        "totalAmount",
        "pendingReviewCount",
        "reportPath"
    )

    $getPriceBaselineTool = Find-Tool -Name "get_price_baseline"
    Assert-OutputSchemaProperties -Tool $getPriceBaselineTool -Properties @(
        "productName",
        "normalizedName",
        "baseline",
        "evidence",
        "warnings"
    )

    Write-Host "MCP smoke test passed. Tools: $($toolNames -join ', ')"

    Write-Host "MCP smoke test passed. Tools: $($toolNames -join ', ')"
} finally {
    if ($null -ne $process -and -not $process.HasExited) {
        try {
            $process.StandardInput.Close()
        } catch {
            # ignored
        }
        try {
            $process.Kill()
            $process.WaitForExit()
        } catch {
            # The smoke test has already captured the expected responses; process cleanup should not mask that result.
        }
    }
}
