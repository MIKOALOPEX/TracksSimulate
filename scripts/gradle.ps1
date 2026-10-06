param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$proxyOptions = @()
# Resolve on EVERY invocation; do not copy the stale user Gradle proxy configuration.
$offline = $GradleArgs -contains '--offline'
$internet = $null
if (!$offline) {
    $internet = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
}
if (!$offline -and $internet.ProxyEnable -eq 1 -and $internet.ProxyServer) {
    $proxyText = $internet.ProxyServer
    foreach ($protocol in @('http', 'https')) {
        $endpoint = $proxyText
        if ($proxyText.Contains('=')) {
            $entry = $proxyText.Split(';') | Where-Object { $_ -like "$protocol=*" } | Select-Object -First 1
            if (!$entry) { throw "No $protocol endpoint in active Windows proxy" }
            $endpoint = $entry.Substring($protocol.Length + 1)
        }
        if (!$endpoint.Contains('://')) { $endpoint = "http://$endpoint" }
        $uri = [Uri]$endpoint
        $proxyOptions += "-D${protocol}.proxyHost=$($uri.Host)"
        $proxyOptions += "-D${protocol}.proxyPort=$($uri.Port)"
    }
    $proxyOptions += '-Dhttp.nonProxyHosts=localhost'
    $proxyOptions += '-Dhttps.nonProxyHosts=localhost'
    Write-Host "Using current Windows proxy: $($internet.ProxyServer)"
} else {
    $proxyOptions += '-Dhttp.proxyHost='
    $proxyOptions += '-Dhttps.proxyHost='
    if ($offline) { Write-Host 'Offline mode: using local Gradle caches; proxy configuration is skipped.' }
}
Push-Location $projectRoot
try {
    & .\gradlew.bat @proxyOptions @GradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
