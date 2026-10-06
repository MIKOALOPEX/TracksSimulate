# 2026-10-04 启动窗口崩溃处理

报告：`test-environment/combined/crash-reports/crash-2026-10-04_02.41.39-client.txt`。

此次崩溃为 `Initializing game` 阶段的空指针：FML 4.0.43 的 `DisplayWindow.initRender:238` 调用 `FMLConfig.getBoolConfigValue`，读取 `EARLY_WINDOW_SQUIR` 返回 null。此时尚未进入世界。磁盘上的 `fml.toml` 已有 `earlyWindowSquir = false`，不能据此认定配置项在文件中缺失；内存配置为何为空尚未复现确认。

已仅将 combined 实例 `config/fml.toml` 的 `earlyWindowControl` 从 true 改为 false。核对本地 loader 字节码确认，此值为 false 时使用 `DummyProvider`，不进入报错的提前启动窗口初始化。属于绕过该报错路径的配置处理，没有修改模组依赖或游戏存档。

原文件备份在同目录的 `fml.toml.before-startup-window-fix-2026-10-04.bak`。TOML 解析及差异检查确认只有这一项改变。离线执行 `classes writeClientLegacyClasspath prepareClientRun -PtestProfile=combined` 成功，未执行 `runClient`；完整启动仍待用户复测。

继续使用 `D:\CreateAdd\Start_TracksSimulate_Client.bat`。启动期间不再显示 NeoForge 提前加载窗口，后续由 Minecraft 创建游戏窗口。
