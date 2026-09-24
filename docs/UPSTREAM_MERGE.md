# 长期合并上游

本地仓库是 [MetaCubeX/ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) 的 fork。以后每次升级都按本文做：**先只读预判，等每个冲突点有明确选择后再改文件**。

本地需要长期保住的改动：

- 自有签名、只打 **arm64-v8a**、包名保持官方 `com.github.metacubex.clash.meta`，`main` push 走 GitHub Actions 正式发布。见 [SIGNING_AND_CI.md](SIGNING_AND_CI.md)。
- 外部订阅选择持久化、配置恢复完成前不开放代理页、选择调试日志。
- 策略组超过 4 个时的搜索导航，以及策略组列表的单列 / 双列 / 三列（偏好键 `proxy_group_columns`）。

原则：冲突时保留本地行为，除非上游或融合后的写法更正确。不要为了“干净”丢掉签名、ABI、包名或选择持久化。

## 硬性约束

1. 预判阶段只做读操作：`merge-base`、`diff`、`merge-tree`、`ls-remote`。不 `merge`、不 `checkout`、不改源码、不提交、不 push。
2. 每个冲突点都要有选择之后，才允许改文件。
3. 本机不当作构建环境。改完后做静态检查，再 push，由 Actions 云构建。

`git fetch` 只更新远程引用，不算改工作区。预判用 `git merge-tree`，它不碰工作区。

## 下次合并的步骤

把 `<SHA>` 换成目标提交（标签或完整哈希）。

```bash
git fetch https://github.com/MetaCubeX/ClashMetaForAndroid.git <SHA>
MB=$(git merge-base HEAD <SHA>)
git log --oneline $MB..HEAD
git log --oneline $MB..<SHA>
git diff --name-status -M $MB <SHA>
git diff --name-status -M $MB HEAD
git merge-tree --write-tree --name-only HEAD <SHA>
```

看三件事：

1. 上游有没有重命名或大搬家（`--name-status -M` 里的 `R`）。
2. `merge-tree` 列出的文本冲突。退出码 1 表示有冲突。modify/delete 也算。
3. 两边都改过、但 Git 能自动合并的文件。这种最容易编过、跑起来错，尤其是调用方和被调函数分在两个文件里。

工作区必须干净，或至少不能脏在上游也改过的文件上。否则 `git merge` 会直接拒绝开始。

选择用语：

- `保留本地`
- `接受上游`
- `按推荐融合`

收到全部编号后再合并。合并后执行 `git submodule update --init --recursive`，因为核心在 `core/src/foss/golang/clash`。

## 本次节点

| 项 | 值 |
| --- | --- |
| 上游 | `https://github.com/MetaCubeX/ClashMetaForAndroid` |
| 目标 | `513b67fc35c68af1b0e7377681f95b33c074ecf7`，标签 `v2.11.34` |
| 本地 HEAD | `7bb1dc4f140ee7c00a628c911f85c7794d0a86c1`（`main`） |
| 共同基点 | `82b73a4bca24f1606e4b443bc9574cf1758c9693`（Bump version to 2.11.32） |
| 预判方式 | `git merge-tree --write-tree`，未合并 |

标签名是 `v2.11.34`，但该提交里 `build.gradle.kts` 的 `versionName` 仍是 `2.11.33`、`versionCode` 仍是 `211033`。版本字符串要单独决定，见冲突 4。

从共同基点到目标，上游没有文件重命名，应用层也没有大型架构重构。核心差异是子模块指针从 `e26714a181ac0e2fa803453c0a8e9a9ce94e31cb`（mihomo v1.19.29）换到 `ab405bad5beeeac8b003bb01f60f134f6df54471`。子模块内部约 148 个文件，主要是新增协议和 TUN 栈，不是把 Android 工程换了一套结构。

上游提交：

- `12d4a5f` Fix external Clash control shortcuts (#805)
- `bc6c2b1` update actions version
- `da8db40` Update Dependencies (#804)
- `c454d0e` Bump version to 2.11.33 (211033)
- `0b214da` support mips stack in tun stack mode
- `513b67f` Update Dependencies (#820)

本地在共同基点之后的提交：

- `c984f20` ci: signed arm64-only builds with Releases publish
- `0c0fd69` ci: publish formal meta release on main push
- `44460e6` ci: use official package id for backup/restore migration
- `caac2d4` fix: persist external selector choices
- `93555d6` feat: add selector persistence diagnostics
- `95a0079` fix: refresh selector state after profile load
- `efc1100` fix: gate proxy UI until profile restoration
- `7bb1dc4` feat: add searchable proxy group navigation

本次决策是「全部按推荐方案合并」，已执行。策略组列数改动先单独提交，再合并上游，避免脏的 `strings.xml` 挡住合并。

## 冲突 1：预发布工作流被删后又被上游改过

文件：`.github/workflows/build-pre-release.yaml`（整文件）。

本地意图：删掉 Alpha 预发布工作流。正式发布只留 `build-release.yaml`，调试构建只留 `build-debug.yaml`。

上游演进：文件还在。`bc6c2b1` 把末尾几个 action 升级了：`richardsimko/update-tag` v1 → v3，`softprops/action-gh-release` v2 → v3，`mikepenz/release-changelog-builder-action` v4 → v6。没有新的业务步骤。

直接合并时，Git 判定为 modify/delete，并且会把上游版本留在结果树里。预发布工作流会回来，再次往 `Prerelease-alpha` 发 Alpha 包，和现在的正式版发布方式撞车。

推荐：保留删除。不把这个文件加回来。上游的 action 升级不值得单独恢复整条 Alpha 流水线。

## 冲突 2：正式发布工作流后半段对不上

文件：`.github/workflows/build-release.yaml`，冲突集中在本地约 179–210 行（收集 APK、清理旧的 `Prerelease-alpha`、上传正式 Release）。前半段（checkout、Java、Go、签名、只打 arm64、官方包名）Git 已按本地保留，没有冲突标记。

本地意图：`main` push 打签过名的 meta / arm64 APK，包名 `com.github.metacubex.clash.meta`，用 run number 生成可覆盖安装的 versionCode，并上传为 Latest 正式版。旧的 `Prerelease-alpha` 只做清理。

上游演进：同一段仍是上游原来的发布尾部，只把 `update-tag` 升到 v3、`action-gh-release` 升到 v3、changelog action 升到 v6。

`merge-tree` 把两边步骤拧在一起了。冲突标记结束后的 `with:` 仍写着 `tag_name: Prerelease-alpha`。如果接受上游的 `Tag Repo` 步骤，这个 `with` 会挂到错误的 action 上，把本次正式版打成 `Prerelease-alpha`。上传步骤同理：本地的 `files`、`make_latest`、说明正文，和上游的 changelog 步骤会接错。

推荐：骨架留本地。只把本地仍在使用的 `softprops/action-gh-release@v2` 升到 `@v3`，`with:` 继续用本地的 tag、APK 路径和正式版说明。不要接回 `update-tag`，也不要接回 changelog builder。本地已经在上传步骤里开了 `generate_release_notes`。

## 冲突 3：外部控制快捷方式会编译失败

文件：

- 上游单方面改了 `app/src/main/java/com/github/kr328/clash/ExternalControlActivity.kt`（约 59–78 行的启停分支，并新增 `isClashRunning()`）。
- 本地单方面改了 `app/src/main/java/com/github/kr328/clash/remote/StatusClient.kt`（约 26–44 行，`currentProfile()` 已改成 `serviceStatus()`）。

Git 不会在这两个文件上打冲突标记，因为每个文件只有一边改过。合完才能看见编译错误。

本地意图：快捷方式所在进程可能还没注册广播，不能依赖 `Remote.broadcasts.clashRunning`。本地把状态查询收成 `StatusClient.serviceStatus()`，用 ContentProvider 是否在服务运行时返回 Bundle 来判断 `running`。

上游演进：`12d4a5f` 修的是同一类问题。外部快捷方式改为自己问 `StatusClient`，不再读广播缓存。上游写的是已经不存在的 `StatusClient(this).currentProfile() != null`。停止动作在上游变成无条件 `stopClash()`。

直接合并后，`currentProfile()` 找不到，Kotlin 编译失败，Actions 会在这一处停下。快捷方式修复本身进不了包。

推荐：留下上游的启停结构，把判断改成本地 API：

```kotlin
private fun isClashRunning(): Boolean {
    return StatusClient(this).serviceStatus().running
}
```

`running` 表示服务进程在跑，和本地主界面、磁贴用的状态一致。不要恢复已删除的 `currentProfile()`。

## 冲突 4：版本号标签和源码不一致

文件：`build.gradle.kts` 约 61–62 行。Git 能自动合并：本地的 ABI 过滤和签名读取保留，版本号变成上游的 `2.11.33` / `211033`。

本地意图：这两行本地没改过。真正的版本在 Actions 里再加工：未手填 tag 时，`versionName` 变成 `基础版本.RUN_NUMBER`，`versionCode` 变成 `基础 versionCode * 1000 + RUN_NUMBER`。

上游演进：`c454d0e` 把版本从 2.11.32 升到 2.11.33。打 `v2.11.34` 标签的 `513b67f` 没有再改这两行。

自动合并可以编译。发布出去的基础版本会显示成 2.11.33 这一支，而这次对齐的节点名字是 v2.11.34。

推荐：保留本地 ABI、签名改动；把 `versionName` 写成 `2.11.34`，`versionCode` 写成 `211034`，与这次要对齐的标签一致。若要和该提交的文件字节完全一致，就接受自动合并留下的 `2.11.33` / `211033`。

## 冲突 5：核心子模块指针

文件：`core/src/foss/golang/clash`。只有上游改了指针，Git 不会冲突。

本地意图：选择持久化依赖 `hub/route.SwitchProxiesCallback` 和 `tunnel.QuerySelectorNow`。本地没有改这个子模块指针。

上游演进：指针从 `e26714a` 换到 `ab405ba`。核对过的调用点：

- `SwitchProxiesCallback` 仍是 `func(sGroup string, sProxy string)`。
- `listener/config.Tun` 只多了可选字段 `ProcessorsPerChannel`。本地 `tun.go` 用的是具名字段，零值可编译。
- `constant/tun.go` 增加 `TunMips`，显示名 `Mips`，小写键 `mips`。

子模块里还有 EasyTier、ZeroTier、OpenVPN、嗅探器等大量新增实现。这些不是 Android 层的重命名，但行为会变。

推荐：接受上游指针。合并后必须 `git submodule update --init --recursive`，否则本地和 CI 会用旧核心，和 `go.mod` / `go.sum` 不一致。

## 冲突 6：TUN 增加 Mips 栈

文件：

- `design/src/main/java/com/github/kr328/clash/design/NetworkSettingsDesign.kt`（栈列表加上 `"mips"` 和 `R.string.tun_stack_mips`）
- `design/src/main/res/values/strings.xml`（`Mips Stack`）
- `design/src/main/res/values-vi/strings.xml`（越南语 `Mips`）

本地没有改这几个设置项。Git 能把英文字符串自动并进去。中文没有单独的 `tun_stack_*` 翻译，界面会回落到英文 “Mips Stack”，和现有 System / Gvisor / Mixed 一样。

设置值 `"mips"` 经 `ServiceStore.tunStackMode` 传到 `native/tun/tun.go`，再按小写去查 `StackTypeMapping`。新核心里这个键就是 `mips`。旧核心没有这个键，未知值会落到 System 栈，所以必须和冲突 5 一起接受新核心。

推荐：接受上游这三处。不要删字符串，否则 `NetworkSettingsDesign` 引用 `R.string.tun_stack_mips` 会编译失败。

## 不会冲突、合并时应原样留下的本地文件

上游这次没碰这些路径。文本合并不会覆盖它们：

- 选择持久化：`core/src/main/golang/native/selector.go`、`tunnel/proxies.go`、`service/.../ConfigurationModule.kt`
- 代理页：`ProxyDesign.kt`、`design_proxy.xml`、策略组弹层（提交列数改动之后也算在内）
- `docs/SIGNING_AND_CI.md`
- `.github/workflows/build-debug.yaml`

`go.mod` / `go.sum`（`core/src/foss/golang` 与 `core/src/main/golang`）只有上游改过，跟随上游即可。

## 冲突决策汇总表

| 编号 | 冲突文件及位置 | 本地功能意图 | 上游演进/改动简述 | 推荐方案 (重构/适配最佳实践) | 您的选择 |
| --- | --- | --- | --- | --- | --- |
| 1 | `.github/workflows/build-pre-release.yaml` 整文件 | 删除 Alpha 预发布，只保留正式版和调试构建 | action 版本升级，工作流仍会发布 `Prerelease-alpha` | 保留删除，不要恢复该文件 | 保留删除 |
| 2 | `.github/workflows/build-release.yaml` 约 179–210 行 | arm64 正式签名发布，清理旧 Alpha 标签 | 发布尾部 action 升到 v3 / v6 | 留本地步骤；仅把 `action-gh-release` 升到 `@v3` | 按推荐融合 |
| 3 | `ExternalControlActivity.kt` 启停分支 + `StatusClient.kt` `serviceStatus()` | 用 ContentProvider 判断服务是否在跑 | 快捷方式改为查询 `currentProfile()`，该方法已被本地删除 | 用上游结构，判断改为 `serviceStatus().running` | 按推荐融合 |
| 4 | `build.gradle.kts` 约 61–62 行 | 版本由 CI 在基础号上加 run number；ABI 与签名逻辑要留 | 源码版本停在 2.11.33，标签却是 v2.11.34 | 保留 ABI/签名；版本写成 `2.11.34` / `211034` | 按推荐融合 |
| 5 | `core/src/foss/golang/clash` 子模块指针 | 选择持久化依赖未改签名的回调 | mihomo 升到 `ab405ba`，新增 Mips 与若干协议 | 接受上游指针，并更新子模块 | 接受上游 |
| 6 | `NetworkSettingsDesign.kt` 与 `values/strings.xml`、`values-vi/strings.xml` 的 Mips 文案 | 本地未改栈列表 | 设置项增加 `mips` | 接受上游。字符串必须和设置项一起留下 | 接受上游 |

> **请对我回复处理决策**：
> 请针对上述编号回复，例如：
> - `1. 保留本地`
> - `2. 接受上游`
> - `3. 分析后按照最佳实践进行融合`
> （支持整体回复，如“全部按推荐方案合并”）

## 假设合并后需验证的功能清单

本机没有完整 Android 构建环境。合并并完成静态检查后 push `main`，用 Actions 验证编译。装上产物后再看这些场景：

1. **发布**：push `main` 只产出签过名的 meta / arm64 APK，包名 `com.github.metacubex.clash.meta`，不新建 `Prerelease-alpha`。手填 `release-tag` 时版本号按输入生成。
2. **外部快捷方式**：进程未启动、服务已停止、服务正在运行，分别试切换、启动、停止。停止不应因为广播缓存为空而无效，也不应编译失败。
3. **选择持久化**：在策略组里改节点，重载配置或重启应用后选择还在。打开调试日志时能看到保存与恢复，且默认关闭时不刷屏。
4. **代理页**：配置还在恢复时不进入可选代理页。策略组多于 4 个时，搜索、上一个、下一个可用。列数在单列、双列、三列之间切换，杀掉应用后仍是上次的列数。
5. **TUN 栈**：System、gVisor、Mixed、Mips 都能保存。Mips 在新核心里应真正选中 Mips，而不是悄悄退回 System。
6. **核心启动**：使用现有订阅启动一次。若订阅里有 EasyTier、ZeroTier 或新的 OpenVPN 参数，确认进程能加载配置，而不是在启动时崩溃。

## 本次执行记录

2026-09-24 按上表执行，没有恢复 `build-pre-release.yaml`。

- 发布工作流保持本地的 arm64 正式版步骤，`softprops/action-gh-release` 为 `@v3`。
- `ExternalControlActivity.isClashRunning()` 使用 `StatusClient.serviceStatus().running`。
- `versionName` 为 `2.11.34`，`versionCode` 为 `211034`。ABI 过滤和签名读取仍是本地逻辑。
- 子模块检出 `ab405bad5beeeac8b003bb01f60f134f6df54471`（mihomo v1.19.31）。
- Mips 栈选项和 `tun_stack_mips` 字符串已留下。

静态检查不能代替 Actions 编译。push `main` 之后以 Build Release 的结果为准。

## 收到决策之后才做的事

1. 先提交或暂存工作区里的策略组列数改动。
2. `git merge 513b67fc35c68af1b0e7377681f95b33c074ecf7`。
3. 按上表改冲突。冲突 3 没有标记，要亲手改调用。
4. `git submodule update --init --recursive`。
5. 静态对照：`currentProfile()` 无残留引用；`tun_stack_mips` 有定义；发布 YAML 没有冲突标记，也没有把正式版 tag 写成 `Prerelease-alpha`。
6. 提交后 push `main`，看 Build Release。失败则按日志修，不在本机假装已经编译通过。
