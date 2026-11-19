# 发布到 JetBrains Marketplace（免费插件）

> 上传动作必须由你本人在浏览器里完成（JetBrains 账号无法代持）。
> 其余准备项已全部完成，发行包已就绪：`build/distributions/dwallpaper-1.0.0.zip`

## 0. 发布前必做：卸载旧版插件

插件 id 已从 `com.local.dynamicwallpaper` 改为 `com.github.shifeng0110.dwallpaper`。

**同一个 IDE 里两个不同 id 的插件会同时加载** → 两个壁纸覆盖窗、双倍解码开销、
设置界面混乱。请先在 `设置 → 插件 → 已安装` 里卸载旧版，再安装/上传新版。

## 1. 上传（网页，首次推荐）

1. 打开 <https://plugins.jetbrains.com> → 右上角头像 → **Sign in**（用 GitHub 账号登录即可）
2. 进入 **Author Dashboard**（<https://plugins.jetbrains.com/author/me>）
3. 首次会要求签署 **Vendor Agreement**：填姓名/邮箱/国家。
   邮箱用 `shifeng0110@users.noreply.github.com` 或你的真实邮箱（会展示在插件页）
4. 点 **Upload plugin** → 选择 `build/distributions/dwallpaper-1.0.0.zip`
5. 填写 listing：

| 字段 | 建议值 |
|---|---|
| Name | Dynamic Wallpaper |
| Category | UI |
| Tags | wallpaper, background, video, theme, animations |
| License | MIT |
| Pricing | **Free** |
| Vendor | shifeng0110 |
| Source code URL | https://github.com/shifeng0110/idea-dynamic-wallpaper （发布仓库后填） |
| Description | 直接用 `plugin.xml` 里的 HTML（Marketplace 会读取包内描述） |
| Screenshots | 建议 3 张：①全窗壁纸 ②右侧设置面板 ③开/关对比 |

6. 提交 → 免费插件通常几分钟内自动上架（付费/混淆才需人工评审）

## 2. 之后更新（Gradle 自动化）

1. 在 <https://plugins.jetbrains.com/author/me/tokens> 生成 Publish Token
2. 在 `build.gradle.kts` 的 `intellijPlatform { }` 里加：

```kotlin
publishing {
    token = providers.environmentVariable("PUBLISH_TOKEN")
}
```

3. 上传：

```powershell
$env:GRADLE_USER_HOME='<你的 gradle 缓存目录>'
$env:PUBLISH_TOKEN='perm:xxxxx'
.\gradlew.bat publishPlugin
```

注意：**每次上传必须是新版本号**（同一版本不能重复上传）。

## 3. 已完成的准备项

- ✅ `plugin.xml`：id / name / vendor(email+url) / HTML 描述 / change-notes / 中英文案
- ✅ 版本号 `1.0.0`（不再是 SNAPSHOT）
- ✅ `pluginIcon.svg` + `pluginIcon_dark.svg`（40×40，插件列表显示）
- ✅ `LICENSE`（MIT）、`THIRD-PARTY-NOTICES.md`（JCodec BSD-2-Clause、ffmpeg 不分发说明）
- ✅ `.gitignore`（排除 build/.gradle-home/.cache-runtime 等）
- ✅ `README.md` 已改写为公开版（无本机路径；内部构建笔记移到 `docs/internal-build-notes.md`）
- ✅ **私密信息清理**：已移除源码里写死的本机路径（ffmpeg 探测只保留 PATH + 标准安装位置）
  并对发行 jar 做过字节级扫描，未发现用户名/本机路径等私密串 ✔
- ✅ 依赖声明：`com.intellij.modules.platform`（必需）+ `com.intellij.modules.jcef`（可选）

## 4. 未能自动完成的一项

`.\gradlew.bat verifyPlugin` 需要从 JetBrains 仓库下载验证器，本机外网被挡而失败。
不影响上传；Marketplace 在你上传后会自动做兼容性检查（也可以在有网环境再跑一次）。

## 5. 合规要点（已写进描述，务必保留）

1. **会启动外部进程 `ffmpeg`**：已在描述中披露，且 ffmpeg 不随插件分发（避免 LGPL 分发义务）。
2. **会创建置顶透明覆盖窗并修改 Win32 窗口样式**（`WS_EX_LAYERED|WS_EX_TRANSPARENT`）：
   已在描述中披露，这是插件能"零重绘、不卡 IDE"的核心机制。
3. **纯离线**：不联网、不上传任何数据，只读用户自己的壁纸目录。
