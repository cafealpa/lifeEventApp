param([Parameter(Mandatory=$true)][string]$Tag)
$ErrorActionPreference = 'Stop'
if ($Tag -notmatch '^v\d+\.\d+\.\d+$') { throw 'Expected vX.Y.Z tag.' }
$repo = 'cafealpa/lifeEventApp'
$headers = @{ 'User-Agent'='LifeDashboard-release-verification' }
$latest = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/latest" -Headers $headers
if ($latest.tag_name -ne $Tag -or $latest.draft -or $latest.prerelease) { throw 'Latest public release does not match the intended tag.' }
$metadataAsset = @($latest.assets | Where-Object name -eq 'update.json')
$apkAsset = @($latest.assets | Where-Object name -eq 'LifeDashboard.apk')
if ($metadataAsset.Count -ne 1 -or $apkAsset.Count -ne 1) { throw 'Required release assets are missing or duplicated.' }
$metadata = Invoke-RestMethod -Uri $metadataAsset[0].browser_download_url -Headers $headers
if ($metadata.applicationId -ne 'com.lifedashboard' -or "v$($metadata.versionName)" -ne $Tag -or $metadata.apkSize -ne $apkAsset[0].size) { throw 'Public release metadata mismatch.' }
$output = Join-Path (Split-Path $PSScriptRoot -Parent) "release-output/$Tag"
New-Item -ItemType Directory -Force $output | Out-Null
$download = Join-Path $output 'public-verification.apk'
Invoke-WebRequest -Uri $apkAsset[0].browser_download_url -Headers $headers -OutFile $download
$hash = (Get-FileHash $download -Algorithm SHA256).Hash.ToLowerInvariant()
if ($hash -ne $metadata.sha256 -or (Get-Item $download).Length -ne $metadata.apkSize) { throw 'Public APK hash or length mismatch.' }
if ($apkAsset[0].digest -and $apkAsset[0].digest -ne "sha256:$hash") { throw 'GitHub digest mismatch.' }
Write-Output "Verified public release $Tag; versionCode=$($metadata.versionCode); bytes=$($metadata.apkSize); SHA-256=$hash"
