[CmdletBinding()]
param(
    [string]$Region,
    [string]$StackPrefix = "reasonstation",
    [ValidateSet("FARGATE_SPOT", "FARGATE")]
    [string]$CapacityProvider = "FARGATE_SPOT",
    [ValidateRange(1, 10)]
    [int]$DesiredCount = 1,
    [ValidateRange(0, 60)]
    [int]$BakeTimeInMinutes = 1,
    [string]$ImageTag
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory)]
        [string]$Command,
        [Parameter(Mandatory)]
        [string[]]$Arguments
    )

    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Command failed with exit code $LASTEXITCODE."
    }
}

function Get-StackOutput {
    param(
        [Parameter(Mandatory)]
        [string]$StackName,
        [Parameter(Mandatory)]
        [string]$OutputKey
    )

    $query = "Stacks[0].Outputs[?OutputKey=='$OutputKey'].OutputValue | [0]"
    $value = & aws cloudformation describe-stacks `
        --stack-name $StackName `
        --region $Region `
        --query $query `
        --output text
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($value) -or $value -eq "None") {
        throw "Could not read output '$OutputKey' from stack '$StackName'. Deploy the prerequisite stack first."
    }
    return $value.Trim()
}

if ([string]::IsNullOrWhiteSpace($Region)) {
    $Region = (& aws configure get region).Trim()
}
if ([string]::IsNullOrWhiteSpace($Region)) {
    throw "Set -Region or configure a default AWS CLI region."
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$networkStack = "$StackPrefix-network"
$registryStack = "$StackPrefix-container-registry"
$secretStack = "$StackPrefix-application-secret"
$loadBalancerStack = "$StackPrefix-load-balancer"
$serviceStack = "$StackPrefix-ecs-service"

Invoke-NativeCommand aws @("sts", "get-caller-identity", "--region", $Region, "--output", "json") | Out-Null
Invoke-NativeCommand docker @("version") | Out-Null

Invoke-NativeCommand aws @(
    "cloudformation", "deploy",
    "--stack-name", $registryStack,
    "--template-file", (Join-Path $PSScriptRoot "container-registry.yaml"),
    "--region", $Region,
    "--no-fail-on-empty-changeset"
)

$vpcId = Get-StackOutput $networkStack "VpcId"
$publicSubnetIds = Get-StackOutput $networkStack "PublicSubnetIds"
$loadBalancerSecurityGroupId = Get-StackOutput $networkStack "LoadBalancerSecurityGroupId"
$ecsServiceSecurityGroupId = Get-StackOutput $networkStack "EcsServiceSecurityGroupId"
$repositoryUri = Get-StackOutput $registryStack "RepositoryUri"
$applicationSecretArn = Get-StackOutput $secretStack "ApplicationSecretArn"

if ([string]::IsNullOrWhiteSpace($ImageTag)) {
    $commit = (& git -C $repositoryRoot rev-parse --short=12 HEAD).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Could not determine the Git commit for the image tag."
    }
    $timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddHHmmss")
    $ImageTag = "$commit-$timestamp"
}

$registryHost = $repositoryUri.Split("/")[0]
$imageUri = "${repositoryUri}:$ImageTag"

$loginPassword = & aws ecr get-login-password --region $Region
if ($LASTEXITCODE -ne 0) {
    throw "Could not obtain an ECR login token."
}
$loginPassword | docker login --username AWS --password-stdin $registryHost
if ($LASTEXITCODE -ne 0) {
    throw "Docker could not authenticate to ECR."
}

Push-Location $repositoryRoot
try {
    Invoke-NativeCommand docker @(
        "buildx", "build",
        "--platform", "linux/arm64",
        "--tag", $imageUri,
        "--push",
        "."
    )
}
finally {
    Pop-Location
}

Invoke-NativeCommand aws @(
    "cloudformation", "deploy",
    "--stack-name", $loadBalancerStack,
    "--template-file", (Join-Path $PSScriptRoot "ecs-target-group.yaml"),
    "--region", $Region,
    "--parameter-overrides",
    "VpcId=$vpcId",
    "PublicSubnetIds=$publicSubnetIds",
    "LoadBalancerSecurityGroupId=$loadBalancerSecurityGroupId",
    "--no-fail-on-empty-changeset"
)

$targetGroupArn = Get-StackOutput $loadBalancerStack "TargetGroupArn"
$alternateTargetGroupArn = Get-StackOutput $loadBalancerStack "AlternateTargetGroupArn"
$productionListenerRuleArn = Get-StackOutput $loadBalancerStack "ProductionListenerRuleArn"

Invoke-NativeCommand aws @(
    "cloudformation", "deploy",
    "--stack-name", $serviceStack,
    "--template-file", (Join-Path $PSScriptRoot "ecs-service.yaml"),
    "--region", $Region,
    "--capabilities", "CAPABILITY_IAM",
    "--parameter-overrides",
    "ImageUri=$imageUri",
    "PublicSubnetIds=$publicSubnetIds",
    "EcsServiceSecurityGroupId=$ecsServiceSecurityGroupId",
    "TargetGroupArn=$targetGroupArn",
    "AlternateTargetGroupArn=$alternateTargetGroupArn",
    "ProductionListenerRuleArn=$productionListenerRuleArn",
    "ApplicationSecretArn=$applicationSecretArn",
    "DesiredCount=$DesiredCount",
    "CapacityProvider=$CapacityProvider",
    "BakeTimeInMinutes=$BakeTimeInMinutes",
    "--no-fail-on-empty-changeset"
)

$loadBalancerDnsName = Get-StackOutput $loadBalancerStack "LoadBalancerDnsName"
Write-Host "Blue/green deployment completed: http://$loadBalancerDnsName"
