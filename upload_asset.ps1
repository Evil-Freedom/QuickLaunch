$ErrorActionPreference = 'Stop'
$token = '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'
$owner = 'Evil-Freedom'
$repo = 'zeekr-orangefox'
$releaseId = '367513679'
$apkPath = 'C:\Users\Administrator\WorkBuddy\2026-08-07-17-57-48\QuickLaunch\app\build\outputs\apk\release\app-release.apk'
$apkName = 'QuickLaunch-v1.3.4.apk'

$uploadUrl = "https://uploads.github.com/repos/$owner/$repo/releases/$releaseId/assets?name=$apkName"

try {
    $fileBytes = [System.IO.File]::ReadAllBytes($apkPath)
    $response = Invoke-RestMethod -Uri $uploadUrl -Method POST -Headers @{ Authorization = "token $token"; Accept = "application/vnd.github.v3+json" } -Body $fileBytes -ContentType 'application/vnd.android.package-archive' -UseBasicParsing
    Write-Output "ASSET_ID=$($response.id)"
    Write-Output "ASSET_NAME=$($response.name)"
    Write-Output "ASSET_URL=$($response.browser_download_url)"
} catch {
    Write-Output "ERROR: $($_.Exception.Message)"
    $streamReader = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
    $errBody = $streamReader.ReadToEnd()
    Write-Output $errBody
}
