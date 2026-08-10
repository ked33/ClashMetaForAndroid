# 签名与 GitHub Actions 构建说明（Fork 自用）

## 目标

- 使用**自有签名**云构建 APK
- 产物发布到 GitHub **Releases**
- 仅构建 **arm64-v8a**，缩短 CI 时间
- 固定包名，后续可覆盖更新并保留配置/数据

## 包名

| 项 | 值 |
|----|-----|
| applicationId | `com.ked33.clash` |
| remove.suffix | `true`（alpha / meta 不再追加后缀） |

与官方 `com.github.metacubex.clash.meta` **不是同一应用**，可并存；官方数据不会自动迁移。

## 已配置的 GitHub Secrets

在仓库 **Settings → Secrets and variables → Actions** 中应存在：

| Secret | 用途 |
|--------|------|
| `KEYSTORE_BASE64` | `release.keystore` 的 Base64 |
| `SIGNING_STORE_PASSWORD` | keystore 密码 |
| `SIGNING_KEY_ALIAS` | 密钥别名（`cmfa`） |
| `SIGNING_KEY_PASSWORD` | key 密码 |

**密钥文件不要提交到 Git。** 本地备份目录（本机已写入）：

```text
%USERPROFILE%\.cmfa-signing\
  release.keystore
  signing.properties
  keystore.b64.txt
```

请另行复制到 U 盘/密码管理器；**丢失后无法对同一包名做覆盖更新**。

## 本地构建

1. 从备份复制 `release.keystore`、`signing.properties` 到仓库根目录（已被 `.gitignore` 忽略）。
2. 可选 `local.properties`：

```properties
sdk.dir=D:\\path\\to\\Android\\Sdk
custom.application.id=com.ked33.clash
remove.suffix=true
abi.filters=arm64-v8a
```

3. 构建：

```bash
./gradlew app:assembleAlphaRelease
# 或正式 flavor
./gradlew app:assembleMetaRelease
```

## CI 工作流

| Workflow | 触发 | 产物 | 发布 |
|----------|------|------|------|
| **Build Pre-Release** | `main` 推送 / 手动 | alpha + arm64 签名 APK | Release 标签 `Prerelease-alpha`（prerelease） |
| **Build Release** | 手动，填写 `vX.Y.Z` | meta + arm64 签名 APK | 正式 Release |
| **Build Debug** | PR / 手动 | arm64 APK artifact | 不发布（可不签名） |

优化点：

- `abi.filters=arm64-v8a`，关闭多 ABI splits
- Gradle `--build-cache` + `setup-gradle` 缓存
- Go module / build 缓存
- Android NDK / CMake / build-tools / platforms 缓存
- Pre-Release `concurrency` 取消同分支旧构建

## 覆盖更新与数据保留

同时满足即可保留配置：

1. 包名始终为 `com.ked33.clash`
2. 始终用同一套 Secrets / keystore 签名
3. `versionCode` 递增（Release 流程会按 tag 自动 bump）

## 轮换 / 重建密钥

仅在密钥泄露时更换。更换后：

1. 重新生成 keystore，更新 4 个 Secrets 与本地备份
2. 用户需**卸载旧包再安装**（签名变更无法覆盖）
3. 或改 `custom.application.id` 当作新应用
