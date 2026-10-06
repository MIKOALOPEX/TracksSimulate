# 验证记录（2026-09-30）

## 已完成

- Java 21.0.2，Gradle 9.2.1，ModDevGradle 2.0.148，NeoForge 21.1.248。
- `scripts/gradle.ps1 build` 成功，生成 `build/libs/trackssimulate-0.1.0-dev.jar`。当前没有单元测试；Gradle `test` 显示 NO-SOURCE。
- 五套配置的声明依赖审计通过，包括 required / 已安装 optional / incompatible 范围、重复 mod ID、Jar-in-Jar 模组和锁定文件 SHA-256。见 `dependency-audit.json`。这不是二进制 API 兼容性证明。
- 联合配置 `combined`：NeoForge 加载 Create、Sable 2.0.5、Synaxis、Photomancy、LDLib2、Simulated、Offroad、Gearwork、Tracks 及本项目；日志出现 `TracksSimulate foundation initialized`、声音引擎启动、图集创建与 `Finished uploading vanilla shaders`。
- 最低版本配置 `base-2.0.0`：官方 Sable 2.0.0 + Create 6.0.10 + 本项目完成上述加载、声音和渲染初始化。
- Sable 2.0.0 下载经过官方发布元数据 SHA-512 核对；来源见 `sable-baseline-source.json`。编译依赖使用该最低版本。
- 未改写原整合包、原存档或参考 JAR；没有添加最终轮子/履带功能。

## 已知参考资源问题

联合配置日志存在以下原参考包问题，未阻止上述初始化：

- Tracks 占位资源 `trackwork:models/block/wheels.json`：`JsonSyntaxException: Missing axis, expected to find a string`。
- Gearwork 的 `gearwork:item/oleo_wheel` 引用缺失的 `create:block/small_tire`，`gearwork:block/track_link` 引用缺失的 `create:block/belt`。
- Photomancy 蓝图炮引用缺失的 `minecraft:block/dispenser_front_horizontal` 纹理。
- 另有未安装可选 Iris / JEI 时的 mixin 警告、开发环境 refmap 警告和着色器参数警告。

以上作为参考包原始状态保留，没有修改原 JAR 或加入无关模组。

## 验证边界

- Windows computer-use 通道返回 `native pipe is unavailable ... os error 2`，无法截图及进行游戏内交互。加载结论来自实际客户端日志，不声称验证了主菜单截图、进入世界、轮子/履带行驶或服务端。
- `base`、`gearwork`、`tracks` 独立配置完成依赖审计；本轮实际启动的是 `combined` 与 `base-2.0.0`。
- 采样后已停止本轮启动的两个测试进程，释放资源；相应 runClient 非零退出是主动停止，不是加载崩溃。日志位于 `test-environment/<profile>/logs/`。
- 未验证所有 Sable 2.x，更不保证未来主版本 API。新项目按要求声明 `[2.0.0,)`，后续加入物理 API 后需增加真实场景兼容测试。
- 原版 Gearwork 的 Sable 下限为 2.0.3；Simulated/Offroad 1.3.0 的上限为 3.0.0（不含），联合参考包交集为 `[2.0.3,3.0.0)`。

## 后续人工检查

启动 combined，新建临时创造世界，分别检查参考模组物品、放置、装配、悬挂与驱动，并核对缺失模型影响。新项目加入功能后，在 base-2.0.0 与 base 重复验证。原整合包世界不要直接用于首次试验。
