param(
    [string]$Region = "ap-south-1",
    [string]$InstanceType = "t3.small",
    [string]$KeyName = "drc-ec2-key",
    [string]$SecurityGroupName = "whatscart-ai-sg"
)

$ErrorActionPreference = "Stop"

$identity = aws sts get-caller-identity --output json | ConvertFrom-Json
Write-Host "Authenticated AWS Account: $($identity.Account) ($($identity.Arn))" -ForegroundColor Green

$vpcId = aws ec2 describe-vpcs --region $Region --filters "Name=isDefault,Values=true" --query "Vpcs[0].VpcId" --output text
if (-not $vpcId -or $vpcId -eq "None") { throw "No default VPC found in $Region" }

$sgId = aws ec2 describe-security-groups --region $Region --filters "Name=group-name,Values=$SecurityGroupName" "Name=vpc-id,Values=$vpcId" --query "SecurityGroups[0].GroupId" --output text
if (-not $sgId -or $sgId -eq "None") {
    $sgId = aws ec2 create-security-group --region $Region --group-name $SecurityGroupName --description "WhatsCart AI HTTP/HTTPS/SSH" --vpc-id $vpcId --query "GroupId" --output text
    foreach ($port in @(22, 80, 443)) {
        aws ec2 authorize-security-group-ingress --region $Region --group-id $sgId --protocol tcp --port $port --cidr 0.0.0.0/0 | Out-Null
    }
    Write-Host "Created security group: $sgId" -ForegroundColor Green
} else {
    Write-Host "Reusing security group: $sgId" -ForegroundColor Cyan
}

$amiId = aws ssm get-parameter --name "/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id" --region $Region --query "Parameter.Value" --output text
$instanceId = aws ec2 run-instances `
    --region $Region `
    --image-id $amiId `
    --instance-type $InstanceType `
    --key-name $KeyName `
    --security-group-ids $sgId `
    --block-device-mappings '[{"DeviceName":"/dev/sda1","Ebs":{"VolumeSize":20,"VolumeType":"gp3"}}]' `
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=whatscart-ai}]" `
    --query "Instances[0].InstanceId" `
    --output text

Write-Host "Launched Instance: $instanceId" -ForegroundColor Green
aws ec2 wait instance-running --region $Region --instance-ids $instanceId
$publicIp = aws ec2 describe-instances --region $Region --instance-ids $instanceId --query "Reservations[0].Instances[0].PublicIpAddress" --output text
$domain = ($publicIp -replace '\.', '-') + ".sslip.io"

Write-Host "Public IP : $publicIp" -ForegroundColor Cyan
Write-Host "HTTPS URL : https://$domain" -ForegroundColor Cyan
