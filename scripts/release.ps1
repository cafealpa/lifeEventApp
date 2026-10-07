param(
    [switch]$Publish
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot
$repo = 'cafealpa/lifeEventApp'
$gradle = Get-Content -LiteralPath 'app/build.gradle.kts' -Raw
$versionName = [regex]::Match($gradle, 'versionName = "([^"]+)"').Groups[1].Value
$versionCode = [int][regex]::Match($gradle, 'versionCode = (\d+)').Groups[1].Value
$minSdk = [int][regex]::Match($gradle, 'minSdk = (\d+)').Groups[1].Value
if (-not $versionName -or $versionCode -lt 1) { throw 'Version information is missing.' }
$tag = "v$versionName"
if ($Publish) {
    $dirty = git status --porcelain
    if ($LASTEXITCODE -ne 0 -or $dirty) { throw 'Commit all source changes before publishing.' }
    $existing = gh release view $tag --repo $repo --json tagName 2>$null
    if ($LASTEXITCODE -eq 0) { throw 'This release already exists. Increment the version; do not replace published assets.' }
}
& ./gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build or validation failed.' }
$sdkLine = Get-Content local.properties | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
$sdk = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
$toolsDir = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
$apk = Join-Path $projectRoot 'app/build/outputs/apk/release/app-release.apk'
if (-not (Test-Path $apk)) { throw 'A signed release APK is required. Configure the approved signing key first.' }
$certOutput = & (Join-Path $toolsDir.FullName 'apksigner.bat') verify --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature validation failed.' }
$actualSigner = [regex]::Match(($certOutput -join "`n"), 'certificate SHA-256 digest: ([a-fA-F0-9]{64})').Groups[1].Value.ToLowerInvariant()
$expectedSigner = (Get-Content 'release-signing-certificate.sha256' -Raw).Trim().ToLowerInvariant()
if (-not $actualSigner -or $actualSigner -ne $expectedSigner) { throw 'Signing certificate changed. Stop to avoid breaking installed-app updates.' }
$badging = & (Join-Path $toolsDir.FullName 'aapt2.exe') dump badging $apk
if ($LASTEXITCODE -ne 0) { throw 'Cannot read APK metadata.' }
if (-not (($badging -join "`n") -match "package: name='com.lifedashboard' versionCode='$versionCode' versionName='$([regex]::Escape($versionName))'")) { throw 'APK package/version differs from release metadata.' }
if (($badging -join "`n") -match 'application-debuggable') { throw 'Release APK must not be debuggable.' }
$output = Join-Path $projectRoot "release-output/$tag"
New-Item -ItemType Directory -Force $output | Out-Null
$asset = Join-Path $output 'LifeDashboard.apk'
Copy-Item -LiteralPath $apk -Destination $asset
$hash = (Get-FileHash $asset -Algorithm SHA256).Hash.ToLowerInvariant()
$metadata = [ordered]@{ schemaVersion=1; applicationId='com.lifedashboard'; versionCode=$versionCode; versionName=$versionName; minSdk=$minSdk; apkName='LifeDashboard.apk'; apkSize=(Get-Item $asset).Length; sha256=$hash }
[System.IO.File]::WriteAllText((Join-Path $output 'update.json'), ($metadata | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
"$hash  LifeDashboard.apk" | Set-Content (Join-Path $output 'SHA256SUMS.txt') -Encoding ascii
Write-Output "Prepared $tag at $output"
if ($Publish) {
    $notes = Join-Path $projectRoot "docs/releases/$tag.md"
    if (-not (Test-Path $notes)) { throw 'Write the release notes first.' }
    git push -u origin main
    if ($LASTEXITCODE -ne 0) { throw 'Source push failed.' }
    git tag $tag
    if ($LASTEXITCODE -ne 0) { throw 'Tag creation failed.' }
    git push origin $tag
    if ($LASTEXITCODE -ne 0) { throw 'Tag push failed.' }
    gh release create $tag $asset (Join-Path $output 'update.json') (Join-Path $output 'SHA256SUMS.txt') --repo $repo --verify-tag --draft --title "Life Dashboard $tag" --notes-file $notes
    if ($LASTEXITCODE -ne 0) { throw 'Draft release creation failed.' }
    gh release edit $tag --repo $repo --draft=false --latest
    if ($LASTEXITCODE -ne 0) { throw 'Release publication failed. Check the existing draft before retrying.' }
    & (Join-Path $PSScriptRoot 'verify-release.ps1') -Tag $tag
}
