# 口袋大龙虾 Pocket Lobster

## 中文介绍

口袋大龙虾是一款运行在普通 Android 手机上的多智能体原生 AI 助手应用。v304 测试版将 OpenMinis 1.12 官方安卓运行时接入应用并替代可见的 OpenClaw 入口，提供官方模型提供商管理、真实聊天、可视浏览器和 Alpine Linux 终端；Codex 与 Claude Code 保持原有独立运行链路。

## English Overview

Pocket Lobster is a multi-agent native AI assistant for ordinary Android phones. The v304 beta embeds the official OpenMinis 1.12 Android runtime and replaces the visible OpenClaw entry points with official provider management, real chat, a visible browser, and an Alpine Linux terminal. Codex and Claude Code retain their existing independent runtime paths.

## 当前核心能力 Core Capabilities

- 多智能体协作：Codex、Claude Code 与 Minis 在同一测试版中提供独立入口和运行时。 English: Codex, Claude Code, and Minis expose independent entry points and runtimes in the beta application.
- 双终端环境：同时提供安卓原生终端与完整 Ubuntu Linux 开发环境。 English: Dual terminal environments, including an Android-native terminal and a full Ubuntu Linux development runtime.
- 系统级链路：通过 Shizuku 打通 system shell，并具备 UI 自动化基础能力。 English: A system-level execution path through a Shizuku-backed system shell, with a foundation for UI automation.
- 全局文件访问：支持共享存储全局文件处理与结果直接交付。 English: Global shared-storage file access so agents can read, process, and deliver results directly in user-visible locations.
- 网络工具链：支持多搜索源、网页访问、Web 自动化、MCP 与 skills 扩展。 English: Network tooling with multi-source search, web access, web automation, MCP connectivity, and skills extensibility.
- 手机友好交互：提供权限管理、提示词管理、对话管理、模型管理和附件上传入口。 English: Phone-friendly interaction with dedicated entry points for permission management, prompt profiles, conversation management, model management, and file attachments.
- 自进化能力：智能体可直接参与修改源码、触发云端构建、产出并安装新版本。 English: Self-evolution capability, allowing agents to modify source code, trigger cloud builds, produce new APKs, and install updated builds.

## 正式文档 Official Documents

- [项目正式介绍 Project Overview](docs/pocket-lobster/PROJECT_OVERVIEW_2026-03-25.md)
- [首版正式发布文案 First Release Copy](docs/pocket-lobster/FIRST_RELEASE_COPY_2026-03-25.md)
- [三通道包名与发布治理 Release Channels And Package IDs](docs/pocket-lobster/RELEASE_CHANNELS_AND_PACKAGE_IDS_2026-03-25.md)
- [上游血缘与许可证说明 Upstream And License](docs/pocket-lobster/UPSTREAM_AND_LICENSE_2026-03-25.md)
- [云端资产备份方案 Cloud Backup Policy](docs/pocket-lobster/CLOUD_BACKUP_POLICY_2026-03-25.md)
- [版本谱系与稳定基线 Version Lineage](docs/pocket-lobster/VERSION_LINEAGE_2026-03-25.md)
- [OpenMinis 第一阶段基线 OpenMinis Phase 1 Baseline](docs/minis/PHASE_1_FOUNDATION_2026-08-20.md)
- [OpenMinis 可交互运行时 OpenMinis Interactive Runtime](docs/minis/PHASE_1_INTERACTIVE_RUNTIME_2026-08-21.md)

## 项目血缘 Project Lineage

- 官方源头 Official source: `openclaw/openclaw`
- 直接安卓实现上游 Direct Android upstream: `friuns2/openclaw-android-assistant`
- 当前项目 Current project: `zhangzheyuanviolin-ship-it/pocket-lobster-android`
- Minis replacement upstream: `OpenMinis/OpenMinis` tag `1.12`, commit `09fc199928de0f26685e766c34e6d541c7a69e5a`

## 当前稳定基线 Current Stable Baseline

当前黄金测试基线是 `1.0.58-codex-cli-0.147.0-gpt-5.6-responses-v299-beta`，对应提交 `9bbaba40efe308373090ad3552d6126f9c568075` 与标签 `golden-beta-v299-20260820`。该版本已经完成 Codex 官方授权与第三方 Responses 提供商在同一会话内双向切换验证。English: The current golden beta baseline is v299 at commit `9bbaba40efe308373090ad3552d6126f9c568075`, protected by tag `golden-beta-v299-20260820`.

## License

Pocket Lobster is distributed under GNU GPL v3 starting with the OpenMinis integration line. Earlier MIT attribution is preserved in `LICENSES/Pocket-Lobster-MIT-history.txt`; OpenMinis and bundled dependency notices are preserved under `LICENSES/` and inside the APK assets.
