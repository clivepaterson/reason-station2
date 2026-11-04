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
    [string]$ImageTag,
    [string]$BucketName,
    [string]$DomainName = "reasonstation2.net",
    [string]$HostedZoneId,
    [string]$SesIdentity = "reasonstation2.net",
    [string]$SesFromAddress = "no-reply@reasonstation2.net",
    [ValidatePattern("^[^\s@]+@[^\s@]+\.[^\s@]+$")]
    [string]$AlertEmail = "alerts@reasonstation2.net"
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
$databaseStack = "$StackPrefix-postgres"
$registryStack = "$StackPrefix-container-registry"
$secretStack = "$StackPrefix-application-secret"
$loadBalancerStack = "$StackPrefix-load-balancer"
$serviceStack = "$StackPrefix-ecs-service"

Invoke-NativeCommand aws @("sts", "get-caller-identity", "--region", $Region, "--output", "json") | Out-Null
Invoke-NativeCommand docker @("version") | Out-Null
$accountId = (& aws sts get-caller-identity --query Account --output text).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Could not determine the AWS account ID."
}
if ([string]::IsNullOrWhiteSpace($BucketName)) {
    $BucketName = "reasonstation2-files-$accountId-$Region"
}
Invoke-NativeCommand aws @("s3api", "head-bucket", "--bucket", $BucketName)
if ([string]::IsNullOrWhiteSpace($HostedZoneId)) {
    $HostedZoneId = (& aws route53 list-hosted-zones-by-name `
        --dns-name $DomainName `
        --query "HostedZones[?Name=='${DomainName}.'].Id | [0]" `
        --output text).Trim()
}
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($HostedZoneId) -or $HostedZoneId -eq "None") {
    throw "Could not find a public Route 53 hosted zone for '$DomainName'."
}
$HostedZoneId = $HostedZoneId.Replace("/hostedzone/", "")
$ecsServiceLinkedRole = (& aws iam list-roles `
    --query "Roles[?RoleName=='AWSServiceRoleForECS'].RoleName | [0]" `
    --output text).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Could not inspect the ECS service-linked role."
}
if ($ecsServiceLinkedRole -eq "None") {
    Invoke-NativeCommand aws @(
        "iam", "create-service-linked-role",
        "--aws-service-name", "ecs.amazonaws.com"
    )
}

Invoke-NativeCommand aws @(
    "cloudformation", "deploy",
    "--stack-name", $networkStack,
    "--template-file", (Join-Path $PSScriptRoot "network.yaml"),
    "--region", $Region,
    "--no-fail-on-empty-changeset"
)

$vpcId = Get-StackOutput $networkStack "VpcId"
$publicSubnetIds = Get-StackOutput $networkStack "PublicSubnetIds"
$databaseSubnetIds = Get-StackOutput $networkStack "DatabaseSubnetIds"
$loadBalancerSecurityGroupId = Get-StackOutput $networkStack "LoadBalancerSecurityGroupId"
$ecsServiceSecurityGroupId = Get-StackOutput $networkStack "EcsServiceSecurityGroupId"

Invoke-NativeCommand aws @(
    "cloudformation", "deploy",
    "--stack-name", $databaseStack,
    "--template-file", (Join-Path $PSScriptRoot "postgres-database.yaml"),
    "--region", $Region,
    "--parameter-overrides",
    "VpcId=$vpcId",
    "DatabaseSubnetIds=$databaseSubnetIds",
    "EcsServiceSecurityGroupId=$ecsServiceSecurityGroupId",
    "--no-fail-on-empty-changeset"
)

Invoke-NativeCommand aws @(
    "cloudformation", "deploy",
    "--stack-name", $registryStack,
    "--template-file", (Join-Path $PSScriptRoot "container-registry.yaml"),
    "--region", $Region,
    "--no-fail-on-empty-changeset"
)

$repositoryUri = Get-StackOutput $registryStack "RepositoryUri"

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
    "DomainName=$DomainName",
    "HostedZoneId=$HostedZoneId",
    "--no-fail-on-empty-changeset"
)

$targetGroupArn = Get-StackOutput $loadBalancerStack "TargetGroupArn"
$alternateTargetGroupArn = Get-StackOutput $loadBalancerStack "AlternateTargetGroupArn"
$productionListenerRuleArn = Get-StackOutput $loadBalancerStack "ProductionListenerRuleArn"
$loadBalancerDnsName = Get-StackOutput $loadBalancerStack "LoadBalancerDnsName"

Invoke-NativeCommand aws @(
    "cloudformation", "deploy",
    "--stack-name", $secretStack,
    "--template-file", (Join-Path $PSScriptRoot "application-secret.yaml"),
    "--region", $Region,
    "--no-fail-on-empty-changeset"
)

$applicationSecretArn = Get-StackOutput $secretStack "ApplicationSecretArn"
$databaseEndpoint = Get-StackOutput $databaseStack "DatabaseEndpoint"
$databasePort = Get-StackOutput $databaseStack "DatabasePort"
$databaseName = Get-StackOutput $databaseStack "DatabaseName"
$databaseMasterSecretArn = Get-StackOutput $databaseStack "DatabaseMasterSecretArn"
$databaseCredentialsJson = & aws secretsmanager get-secret-value `
    --secret-id $databaseMasterSecretArn `
    --region $Region `
    --query SecretString `
    --output text
if ($LASTEXITCODE -ne 0) {
    throw "Could not read the RDS-managed database credentials."
}
$databaseCredentials = $databaseCredentialsJson | ConvertFrom-Json

$existingConfig = @{}
$existingVersionId = & aws secretsmanager list-secret-version-ids `
    --secret-id $applicationSecretArn `
    --region $Region `
    --query "Versions[0].VersionId" `
    --output text
if ($LASTEXITCODE -ne 0) {
    throw "Could not inspect the existing application secret."
}
if ($existingVersionId -ne "None") {
    $existingConfigJson = & aws secretsmanager get-secret-value `
        --secret-id $applicationSecretArn `
        --region $Region `
        --query SecretString `
        --output text
    if ($LASTEXITCODE -ne 0) {
        throw "Could not read the existing application secret."
    }
    ($existingConfigJson | ConvertFrom-Json).PSObject.Properties | ForEach-Object {
        $existingConfig[$_.Name] = $_.Value
    }
}

if ($existingConfig.ContainsKey("LOCAL_OAUTH_CLIENT_SECRET")) {
    $localOauthSecret = $existingConfig["LOCAL_OAUTH_CLIENT_SECRET"]
}
else {
    $randomBytes = New-Object byte[] 32
    $randomNumberGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $randomNumberGenerator.GetBytes($randomBytes)
    }
    finally {
        $randomNumberGenerator.Dispose()
    }
    $localOauthSecret = [Convert]::ToBase64String($randomBytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}
$googleClientId = if ($existingConfig.ContainsKey("google.client-id")) {
    $existingConfig["google.client-id"]
} else { "not-configured" }
$googleClientSecret = if ($existingConfig.ContainsKey("google.client-secret")) {
    $existingConfig["google.client-secret"]
} else { "not-configured" }
$appleClientId = if ($existingConfig.ContainsKey("APPLE_CLIENT_ID")) {
    $existingConfig["APPLE_CLIENT_ID"]
} else { "not-configured" }
$appleClientSecret = if ($existingConfig.ContainsKey("APPLE_CLIENT_SECRET")) {
    $existingConfig["APPLE_CLIENT_SECRET"]
} else { "not-configured" }

$applicationConfig = [ordered]@{
    DATABASE_URL = "jdbc:postgresql://${databaseEndpoint}:${databasePort}/${databaseName}?sslmode=require"
    DATABASE_USERNAME = $databaseCredentials.username
    DATABASE_PASSWORD = $databaseCredentials.password
    S3_ENDPOINT = "https://s3.$Region.amazonaws.com"
    S3_PUBLIC_ENDPOINT = "https://s3.$Region.amazonaws.com"
    S3_REGION = $Region
    S3_BUCKET = $BucketName
    APP_BASE_URL = "https://$DomainName"
    SES_FROM_ADDRESS = $SesFromAddress
    SES_REGION = $Region
    LOCAL_OAUTH_CLIENT_ID = "reasonstation-local"
    LOCAL_OAUTH_CLIENT_SECRET = $localOauthSecret
    "google.client-id" = $googleClientId
    "google.client-secret" = $googleClientSecret
    APPLE_CLIENT_ID = $appleClientId
    APPLE_CLIENT_SECRET = $appleClientSecret
}
$applicationConfigJson = $applicationConfig | ConvertTo-Json -Compress
$secretFile = [System.IO.Path]::GetTempFileName()
try {
    [System.IO.File]::WriteAllText(
        $secretFile,
        $applicationConfigJson,
        (New-Object System.Text.UTF8Encoding($false))
    )
    $applicationConfigVersion = (Invoke-NativeCommand aws @(
        "secretsmanager", "put-secret-value",
        "--secret-id", $applicationSecretArn,
        "--secret-string", "file://$secretFile",
        "--region", $Region,
        "--query", "VersionId",
        "--output", "text"
    )).Trim()
}
finally {
    Remove-Item -LiteralPath $secretFile -Force -ErrorAction SilentlyContinue
}

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
    "ApplicationConfigVersion=$applicationConfigVersion",
    "FileStorageBucketArn=arn:aws:s3:::$BucketName",
    "SesIdentityArn=arn:aws:ses:${Region}:${accountId}:identity/$SesIdentity",
    "DesiredCount=$DesiredCount",
    "CapacityProvider=$CapacityProvider",
    "BakeTimeInMinutes=$BakeTimeInMinutes",
    "--no-fail-on-empty-changeset"
)

& (Join-Path $PSScriptRoot "deploy-monitoring.ps1") `
    -Region $Region `
    -StackPrefix $StackPrefix `
    -AlertEmail $AlertEmail

Write-Host "Blue/green deployment completed: https://$DomainName"
