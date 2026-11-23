$ErrorActionPreference = "Continue"
$versions = @("1.19.4", "1.20.1", "1.21.1", "1.21.10", "26.3")
foreach ($v in $versions) {
    $log = "versions/$v/run/logs/latest.log"
    if (Test-Path $log) { Remove-Item $log -Force }
    Write-Host "===== selftest $v"
    $env:LIQUIDPHASE_SELFTEST = "true"
    $env:LIQUIDPHASE_SELFTEST_STOP = "true"
    & .\gradlew --offline ":$v`:runServer" 2>&1 | Out-Null
    if (Test-Path $log) {
        Select-String -Path $log -Pattern "selftest\] result|selftest\] FAIL" | Select-Object -First 12 -ExpandProperty Line
    } else {
        Write-Host "NO LOG for $v"
    }
}
