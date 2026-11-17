<#
.SYNOPSIS
Deploys database-rotation-triggered ECS redeployment without rebuilding the image.
.NOTES
Deploy the updated ECS stack and monitoring first. This script never reads secret values.
#>
[CmdletBinding()]
param(
    [string]$Region,
    [string]$StackPrefix = "reasonstation"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Invoke-RotationAws {
    param(
        [Parameter(Mandatory)]
        [string[]]$Arguments
    )

    & aws @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "AWS database rotation command failed with exit code $LASTEXITCODE."
    }
}

function Get-RotationStackOutput {
    param(
        [Parameter(Mandatory)]
        [string]$StackName,
        [Parameter(Mandatory)]
        [string]$OutputKey
    )

    $value = Invoke-RotationAws @(
        "cloudformation", "describe-stacks",
        "--stack-name", $StackName,
        "--region", $Region,
        "--query", "Stacks[0].Outputs[?OutputKey=='$OutputKey'].OutputValue | [0]",
        "--output", "text"
    )
    $value = "$value".Trim()
    if ([string]::IsNullOrWhiteSpace($value) -or $value -eq "None") {
        throw "Stack '$StackName' must expose '$OutputKey'. Deploy the updated prerequisite stack first."
    }
    return $value
}

if ([string]::IsNullOrWhiteSpace($Region)) {
    $configuredRegion = Invoke-RotationAws @("configure", "get", "region")
    $Region = "$configuredRegion".Trim()
}
if ([string]::IsNullOrWhiteSpace($Region)) {
    throw "Set -Region or configure a default AWS CLI region."
}

$databaseSecretArn = Get-RotationStackOutput "$StackPrefix-postgres" "DatabaseMasterSecretArn"
$injectedSecretArn = Get-RotationStackOutput "$StackPrefix-ecs-service" "DatabaseMasterSecretArn"
if ($databaseSecretArn -ne $injectedSecretArn) {
    throw "ECS must inject credentials from the current database secret before enabling rotation automation."
}
$clusterName = Get-RotationStackOutput "$StackPrefix-ecs-service" "ClusterName"
$serviceArn = Get-RotationStackOutput "$StackPrefix-ecs-service" "ServiceArn"
$alertTopicArn = Get-RotationStackOutput "$StackPrefix-monitoring" "AlertTopicArn"

Invoke-RotationAws @(
    "cloudformation", "deploy",
    "--stack-name", "$StackPrefix-database-rotation",
    "--template-file", (Join-Path $PSScriptRoot "database-rotation.yaml"),
    "--region", $Region,
    "--capabilities", "CAPABILITY_IAM",
    "--parameter-overrides",
    "DatabaseMasterSecretArn=$databaseSecretArn",
    "ClusterName=$clusterName",
    "ServiceArn=$serviceArn",
    "AlertTopicArn=$alertTopicArn",
    "AlarmNamePrefix=$StackPrefix-monitoring-database-rotation",
    "--no-fail-on-empty-changeset"
)

Write-Host "Database rotation automation deployed. Future current-secret changes will trigger an ECS deployment."
