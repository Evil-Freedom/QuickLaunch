$ErrorActionPreference = 'Stop'
$token = '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'
$owner = 'Evil-Freedom'
$repo = 'zeekr-orangefox'
$tag = 'v1.3.4'
$apkPath = 'C:\Users\Administrator\WorkBuddy\2026-08-07-17-57-48\QuickLaunch\app\build\outputs\apk\release\app-release.apk'

$releaseBody = @"
## v1.3.4 Release Notes

### Bug Fixes
- **Layout Fix**: Fixed constraint chain misalignment in `view_launch.xml` and `activity_create.xml` by introducing Barrier to prevent GONE views from disrupting the layout flow
- **WiFi List Fix**: Added runtime permission request for NEARBY_WIFI_DEVICES (Android 13+) and ACCESS_FINE_LOCATION (Android 10-12) before showing WiFi picker
- **Dynamic Sync Status**: Holiday sync status badges and dots now update dynamically after sync completes and when the sync tab is selected

### Improvements
- Added ACCESS_FINE_LOCATION and NEARBY_WIFI_DEVICES permissions to AndroidManifest.xml
- Enhanced `SyncTabController` with `updateSourceStatus()` method for dynamic UI updates
- Improved `SyncCallbacks` interface with `onSyncCompleted()` callback

---

## v1.3.4 更新日志

### 修复
- **布局修复**: 在 `view_launch.xml` 和 `activity_create.xml` 中引入 Barrier 修复约束链错位问题，防止 GONE 视图干扰布局
- **WiFi 列表修复**: 在显示 WiFi 选择器前请求 NEARBY_WIFI_DEVICES (Android 13+) 或 ACCESS_FINE_LOCATION (Android 10-12) 运行时权限
- **动态同步状态**: 同步状态圆点和徽章现在会在同步完成后和同步标签页选中时动态更新

### 改进
- 在 AndroidManifest.xml 中添加了 ACCESS_FINE_LOCATION 和 NEARBY_WIFI_DEVICES 权限
- 增强 `SyncTabController` 类，新增 `updateSourceStatus()` 方法实现动态 UI 更新
- 改进 `SyncCallbacks` 接口，新增 `onSyncCompleted()` 回调
"@

$body = @{
    tag_name = $tag
    name = "QuickLaunch v1.3.4"
    body = $releaseBody
}

$jsonBody = $body | ConvertTo-Json -Depth 10 -Compress
$jsonBytes = [System.Text.Encoding]::UTF8.GetBytes($jsonBody)

try {
    $response = Invoke-RestMethod -Uri "https://api.github.com/repos/$owner/$repo/releases" -Method POST -Headers @{ Authorization = "token $token"; Accept = "application/vnd.github.v3+json" } -Body $jsonBytes -ContentType 'application/json; charset=utf-8' -UseBasicParsing
    Write-Output "RELEASE_ID=$($response.id)"
    Write-Output "UPLOAD_URL=$($response.upload_url)"
} catch {
    Write-Output "ERROR: $($_.Exception.Message)"
    $streamReader = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
    $errBody = $streamReader.ReadToEnd()
    Write-Output $errBody
}
