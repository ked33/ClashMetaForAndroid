# 签名与 GitHub Actions 构建说明（Fork 自用）

## 目标

- 使用**自有签名**云构建 APK
- **push `main` 自动**构建 **meta 正式版**并发布到 GitHub Releases（非 Pre-release、非 alpha）
- 仅构建 **arm64-v8a**
- 固定包名，后续可覆盖更新并保留配置/数据

## 包名

| 项 | 值 |
|----|-----|
| applicationId | `com.ked33.clash` |
| remove.suffix | `true` |
| flavor | **meta**（`assembleMetaRelease`） |

## GitHub Secrets

| Secret | 用途 |
|--------|------|
| `KEYSTORE_BASE64` | `release.keystore` 的 Base64 |
| `SIGNING_STORE_PASSWORD` | keystore 密码 |
| `SIGNING_KEY_ALIAS` | 密钥别名（`cmfa`） |
| `SIGNING_KEY_PASSWORD` | key 密码 |

本地备份（勿提交 Git）：

```text
%USERPROFILE%\.cmfa-signing\
  release.keystore
  signing.properties
  keystore.b64.txt
```

## CI 工作流

| Workflow | 触发 | 产物 | 发布 |
|----------|------|------|------|
| **Build Release** | **`main` push 自动** / 手动 | **meta** + arm64 签名 APK | **正式 Release**（`prerelease: false`） |
| **Build Debug** | PR / 手动 | arm64 APK artifact | 不发布 |

### 自动版本（push / 手动且未填 tag）

- `versionName` = `build.gradle` 中的基础版本 + `.` + `GITHUB_RUN_NUMBER`（如 `2.11.32.42`）
- `versionCode` = `基础 versionCode * 1000 + RUN_NUMBER`（保证递增，可覆盖安装）
- Release 标签：`v{versionName}`，并标记为 Latest

### 手动指定正式版本

Actions → **Build Release** → 填写 `release-tag`（如 `v2.12.0`）：

- 按 tag 写入 `versionName` / `versionCode`
- 提交 bump 到仓库并打 tag
- 发布同名正式 Release

## 本地构建

```properties
# local.properties
custom.application.id=com.ked33.clash
remove.suffix=true
abi.filters=arm64-v8a
```

```bash
./gradlew app:assembleMetaRelease
```

## 覆盖更新

1. 包名始终 `com.ked33.clash`
2. 同一 keystore 签名
3. `versionCode` 递增（自动发布已处理）
