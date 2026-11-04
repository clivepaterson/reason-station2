<#
.SYNOPSIS
Deploys email alerts independently, without rebuilding or restarting the application.
.EXAMPLE
.\infrastructure\deploy-monitoring.ps1 -Region ap-southeast-1
.NOTES
Confirm the SNS subscription email before notifications can be delivered.
The connection threshold is an absolute count, not a measured percentage of max_connections.
#>
[CmdletBinding()]
param(
    [string]$Region,
    [string]$StackPrefix = "reasonstation",
    [ValidatePattern("^[^\s@]+@[^\s@]+\.[^\s@]+$")]
    [string]$AlertEmail = "alerts@reasonstation2.net",
    [ValidateRange(1, 100000)]
    [int]$DatabaseConnectionsThreshold = 60
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Invoke-MonitoringAws {
    param(
        [Parameter(Mandatory)]
        [string[]]$Arguments
    )

    & aws @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "AWS monitoring command failed with exit code $LASTEXITCODE."
    }
}

if ([string]::IsNullOrWhiteSpace($Region)) {
    $configuredRegion = Invoke-MonitoringAws @("configure", "get", "region")
    $Region = "$configuredRegion".Trim()
}
if ([string]::IsNullOrWhiteSpace($Region)) {
    throw "Set -Region or configure a default AWS CLI region."
}

$serviceStackJson = Invoke-MonitoringAws @(
    "cloudformation", "describe-stacks",
    "--stack-name", "$StackPrefix-ecs-service",
    "--region", $Region,
    "--output", "json"
)
$serviceStack = ($serviceStackJson | ConvertFrom-Json).Stacks[0]
$clusterName = @($serviceStack.Outputs | Where-Object OutputKey -eq "ClusterName" | Select-Object -ExpandProperty OutputValue)
$serviceArn = @($serviceStack.Outputs | Where-Object OutputKey -eq "ServiceArn" | Select-Object -ExpandProperty OutputValue)
if ($clusterName.Count -ne 1 -or $serviceArn.Count -ne 1) {
    throw "ECS stack must expose ClusterName and ServiceArn outputs. Deploy the ECS stack first."
}
$serviceName = ($serviceArn[0] -split "/")[-1]

$databaseId = Invoke-MonitoringAws @(
    "cloudformation", "describe-stack-resource",
    "--stack-name", "$StackPrefix-postgres",
    "--logical-resource-id", "Database",
    "--region", $Region,
    "--query", "StackResourceDetail.PhysicalResourceId",
    "--output", "text"
)
$databaseId = "$databaseId".Trim()
if ([string]::IsNullOrWhiteSpace($databaseId) -or $databaseId -eq "None") {
    throw "Could not resolve the existing database instance from the PostgreSQL stack."
}

Invoke-MonitoringAws @(
    "cloudformation", "deploy",
    "--stack-name", "$StackPrefix-monitoring",
    "--template-file", (Join-Path $PSScriptRoot "monitoring.yaml"),
    "--region", $Region,
    "--parameter-overrides",
    "AlertEmail=$AlertEmail",
    "ClusterName=$($clusterName[0])",
    "ServiceName=$serviceName",
    "DatabaseInstanceIdentifier=$databaseId",
    "DatabaseConnectionsThreshold=$DatabaseConnectionsThreshold",
    "--no-fail-on-empty-changeset"
)

Write-Host "Monitoring deployed. Confirm the SNS subscription email sent to $AlertEmail before alerts can be delivered."
