$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$scriptPath = Join-Path (Split-Path -Parent $PSScriptRoot) "deploy-database-rotation.ps1"
$global:rotationTestState = @{
    Commands = New-Object System.Collections.Generic.List[object]
    MissingInjection = $false
    MismatchedInjection = $false
    FailDeploy = $false
    SecretArn = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:rds!db-example-AbCdEf"
}

function aws {
    $arguments = @($args)
    $global:rotationTestState.Commands.Add($arguments)
    $global:LASTEXITCODE = 0
    if ($arguments[1] -eq "deploy") {
        if ($global:rotationTestState.FailDeploy) {
            $global:LASTEXITCODE = 1
        }
        return
    }
    $query = $arguments[[Array]::IndexOf($arguments, "--query") + 1]
    if ($query -match "DatabaseMasterSecretArn") {
        $stack = $arguments[[Array]::IndexOf($arguments, "--stack-name") + 1]
        if ($stack -eq "example-ecs-service") {
            if ($global:rotationTestState.MissingInjection) { return "None" }
            if ($global:rotationTestState.MismatchedInjection) { return "$($global:rotationTestState.SecretArn)-other" }
        }
        return $global:rotationTestState.SecretArn
    }
    if ($query -match "ClusterName") { return "example-cluster" }
    if ($query -match "ServiceArn") { return "arn:aws:ecs:ap-southeast-1:123456789012:service/example-cluster/example-service" }
    if ($query -match "AlertTopicArn") { return "arn:aws:sns:ap-southeast-1:123456789012:example-monitoring-topic" }
    throw "Unexpected AWS command: $arguments"
}

function Assert-Fails {
    param([string]$ExpectedMessage)
    $global:rotationTestState.Commands.Clear()
    $failed = $false
    try {
        & $scriptPath -Region ap-southeast-1 -StackPrefix example
    }
    catch {
        if ($_.Exception.Message -notlike "*$ExpectedMessage*") { throw }
        $failed = $true
    }
    if (-not $failed) { throw "Expected failure containing '$ExpectedMessage'." }
}

& $scriptPath -Region ap-southeast-1 -StackPrefix example
$deploy = @($global:rotationTestState.Commands | Where-Object { $_[1] -eq "deploy" })
if ($deploy.Count -ne 1) { throw "Expected one deployment." }
foreach ($expected in @(
    "example-database-rotation",
    "DatabaseMasterSecretArn=$($global:rotationTestState.SecretArn)",
    "ClusterName=example-cluster",
    "ServiceArn=arn:aws:ecs:ap-southeast-1:123456789012:service/example-cluster/example-service",
    "AlertTopicArn=arn:aws:sns:ap-southeast-1:123456789012:example-monitoring-topic",
    "AlarmNamePrefix=example-monitoring-database-rotation"
)) {
    if ($deploy[0] -notcontains $expected) { throw "Missing deployment argument '$expected'." }
}
if (@($global:rotationTestState.Commands | Where-Object { $_ -contains "get-secret-value" }).Count -ne 0) {
    throw "Rotation deployment must never read credential values."
}

$global:rotationTestState.MissingInjection = $true
Assert-Fails "Deploy the updated prerequisite stack first"
if (@($global:rotationTestState.Commands | Where-Object { $_[1] -eq "deploy" }).Count -ne 0) {
    throw "Must not deploy automation before direct secret injection."
}
$global:rotationTestState.MissingInjection = $false
$global:rotationTestState.MismatchedInjection = $true
Assert-Fails "ECS must inject credentials from the current database secret"
$global:rotationTestState.MismatchedInjection = $false
$global:rotationTestState.FailDeploy = $true
Assert-Fails "AWS database rotation command failed"

Write-Host "Database rotation deployment script scenarios passed."
Remove-Variable rotationTestState -Scope Global
