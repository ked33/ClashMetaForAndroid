# 签名与 GitHub Actions 构建说明（Fork 自用）

## 目标

- 使用**自有签名**云构建 APK
- **push `main` 自动**构建 **meta 正式版**并发布到 GitHub Releases
- 仅构建 **arm64-v8a**
- **包名与上游官方一致**，便于备份官方数据 → 卸官方 → 装本版 → 还原（**不与官方共存**）

## 包名

| 项 | 值 |
|----|-----|
| applicationId | `com.github.metacubex.clash.meta` |
| 来源 | 默认 base `com.github.metacubex.clash` + meta flavor 后缀 `.meta` |
| custom.application.id | **不设置** |
| remove.suffix | **不设置**（保持官方后缀） |

与上游签名不同：不能覆盖安装官方包，须先卸载再装本版。

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
| **Build Release** | **`main` push 自动** / 手动 | **meta** + arm64 签名 APK | **正式 Release** |
| **Build Debug** | PR / 手动 | arm64 APK artifact | 不发布 |

### 自动版本（push / 手动且未填 tag）

- `versionName` = 基础版本 + `.` + `GITHUB_RUN_NUMBER`
- `versionCode` = `基础 versionCode * 1000 + RUN_NUMBER`（保证递增）
- Release 标签：`v{versionName}`，并标记为 Latest

### 手动指定正式版本

Actions → **Build Release** → 填写 `release-tag`（如 `v2.12.0`）。

## 本地构建

```properties
# local.properties — 仅限制 ABI 即可，包名保持官方
abi.filters=arm64-v8a
```

```bash
./gradlew app:assembleMetaRelease
```

## 覆盖更新（本版之间）

1. 包名始终 `com.github.metacubex.clash.meta`
2. 同一 keystore 签名
3. `versionCode` 递增
