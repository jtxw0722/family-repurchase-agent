param(
    [string]$JarPath = "target/family-consumption-mcp-java-server-0.3.0-SNAPSHOT.jar"
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
    # Write all MCP JSON-RPC messages first, then close stdin. This lets us read the complete stdout text
    # instead of parsing a very long tools/list JSON response with ReadLine().
    $messages = @(
        '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke-test","version":"1.0.0"}}}'
        '{"jsonrpc":"2.0","method":"notifications/initialized"}'
        '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
        '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"compare_price","arguments":{"productName":"猫砂"}}}'
    )

    foreach ($message in $messages) {
        $process.StandardInput.WriteLine($message)
    }
    $process.StandardInput.Close()

    if (-not $process.WaitForExit(10000)) {
        $process.Kill()
        throw "MCP server did not exit within timeout."
    }

    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()

    if ($process.ExitCode -ne 0) {
        throw "MCP server exited with code $($process.ExitCode). stderr=$stderr"
    }

    $responses = @()
    foreach ($line in ($stdout -split "`r?`n")) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed)) {
            continue
        }

        try {
            $responses += ($trimmed | ConvertFrom-Json)
        } catch {
            throw "Failed to parse MCP JSON line: $trimmed`n$($_.Exception.Message)"
        }
    }

    function Get-ResponseById {
        param(
            [Parameter(Mandatory = $true)]
            [int]$Id
        )

        $response = $responses | Where-Object { $_.id -eq $Id } | Select-Object -First 1
        if ($null -eq $response) {
            throw "Missing MCP response id=$Id. stdout=$stdout stderr=$stderr"
        }
        return $response
    }

    $initializeResponse = Get-ResponseById -Id 1
    if ($initializeResponse.result.serverInfo.name -ne "family-consumption-agent") {
        throw "initialize response server name mismatch"
    }

    $toolsResponse = Get-ResponseById -Id 2
    $toolNames = @($toolsResponse.result.tools | ForEach-Object { $_.name })

    foreach ($expectedTool in @("import_file", "compare_price", "generate_report")) {
        if ($toolNames -notcontains $expectedTool) {
            throw "missing tool: $expectedTool"
        }
    }

    $toolErrorResponse = Get-ResponseById -Id 3
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
