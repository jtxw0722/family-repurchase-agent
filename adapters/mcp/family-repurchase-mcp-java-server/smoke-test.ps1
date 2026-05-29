param(
    [string]$JarPath = "target/family-repurchase-mcp-java-server-0.4.0.jar"
)

$ErrorActionPreference = "Stop"

# The MCP Java SDK writes JSON-RPC messages as UTF-8 JSON lines.
# Explicitly set redirected stream encodings to avoid garbled Chinese text breaking ConvertFrom-Json on Windows.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$processInfo = New-Object System.Diagnostics.ProcessStartInfo
$processInfo.FileName = "java"
$processInfo.Arguments = "-jar `"$JarPath`""
$processInfo.WorkingDirectory = $PSScriptRoot
$processInfo.RedirectStandardInput = $true
$processInfo.RedirectStandardOutput = $true
$processInfo.RedirectStandardError = $true
$processInfo.UseShellExecute = $false
$processInfo.CreateNoWindow = $true

# These properties exist in modern PowerShell / .NET. Guard them so the script remains compatible with older runtimes.
if ($processInfo.PSObject.Properties.Name -contains "StandardInputEncoding") {
    $processInfo.StandardInputEncoding = [System.Text.Encoding]::UTF8
}
if ($processInfo.PSObject.Properties.Name -contains "StandardOutputEncoding") {
    $processInfo.StandardOutputEncoding = [System.Text.Encoding]::UTF8
}
if ($processInfo.PSObject.Properties.Name -contains "StandardErrorEncoding") {
    $processInfo.StandardErrorEncoding = [System.Text.Encoding]::UTF8
}

$process = [System.Diagnostics.Process]::Start($processInfo)

try {
    function Read-McpResponse {
        param(
            [Parameter(Mandatory = $true)]
            [int]$Id,
            [int]$TimeoutMs = 10000
        )

        $started = Get-Date
        while (((Get-Date) - $started).TotalMilliseconds -lt $TimeoutMs) {
            $line = $process.StandardOutput.ReadLine()
            if ([string]::IsNullOrWhiteSpace($line)) {
                continue
            }
            $response = $line | ConvertFrom-Json
            if ($response.id -eq $Id) {
                return $response
            }
        }

        throw "Timed out waiting for MCP response id=$Id"
    }

    $process.StandardInput.WriteLine('{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke-test","version":"1.0.0"}}}')
    $initializeResponse = Read-McpResponse -Id 1
    if ($initializeResponse.result.serverInfo.name -ne "family-repurchase-agent") {
        throw "initialize response server name mismatch"
    }

    $process.StandardInput.WriteLine('{"jsonrpc":"2.0","method":"notifications/initialized"}')
    $process.StandardInput.WriteLine('{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}')
    $toolsResponse = Read-McpResponse -Id 2
    $toolNames = @($toolsResponse.result.tools | ForEach-Object { $_.name })

    foreach ($expectedTool in @("import_file", "compare_price", "generate_report")) {
        if ($toolNames -notcontains $expectedTool) {
            throw "missing tool: $expectedTool"
        }
    }

    $process.StandardInput.WriteLine('{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"compare_price","arguments":{"productName":"猫砂"}}}')
    $toolErrorResponse = Read-McpResponse -Id 3
    if ($toolErrorResponse.result.isError -ne $true) {
        throw "compare_price missing argument should return tool error"
    }

    if ($toolErrorResponse.result.content[0].text -notmatch "price must be a positive number") {
        throw "unexpected tool error message: $($toolErrorResponse.result.content[0].text)"
    }

    Write-Output "MCP smoke test passed."
} finally {
    if ($null -ne $process -and -not $process.HasExited) {
        try {
            $process.StandardInput.Close()
        } catch {
            # ignored
        }
        $process.Kill()
    }
}
