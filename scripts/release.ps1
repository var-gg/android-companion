param(
    [Parameter(Mandatory = $true)]
    [string]$Version
)

$tag = if ($Version.StartsWith('v')) { $Version } else { "v$Version" }

git add .
git commit -m "release: $tag"
git tag $tag
git push origin HEAD
git push origin $tag

Write-Host "Pushed tag $tag. GitHub Actions should publish the APK release asset."
