# 口袋大龙虾 Pocket Lobster

口袋大龙虾是一套运行在非 root ARM64 Android 手机上的多智能体工作平台。它把 Codex、Claude Code、OpenMinis 和手机操作智能体放进同一个原生应用，让模型不仅能对话，还能使用三套终端、真实可见浏览器、Android 共享存储和 Shizuku 系统链路完成实际任务。

当前稳定版本：1.0.100，versionCode 341  
Codex CLI：0.153.4  
OpenMinis：1.12  
Android：8.0 及以上，ARM64  
许可证：GNU GPL v3，第三方组件许可证见 NOTICE 和 LICENSES

## 当前形态

应用提供四个独立入口。Codex、Claude Code 和 Minis 既能独立工作，也能进入三智能体协作模式；手机操作智能体是独立的第四个入口，负责在主屏幕或隔离虚拟屏幕中操作 Android 应用。v341 将手机操作智能体保持为用户手动启动，避免其他模型未经确认接管手机界面。

这不是把网页聊天框封装成 APK。应用内包含真实的命令执行环境、后台服务、会话管理、模型管理、权限管理、共享浏览器、协作看板和可回溯任务记录。

## 四个智能体

### Codex

Codex 面向代码、仓库、终端和长周期工程任务。它可以读取和修改项目、执行测试、调用网页和插件、维护 GitHub 工作流，并通过权限管理页安装或更新 Codex CLI。当前版本支持 OpenAI 账户授权，也支持 Responses 兼容的第三方提供商；提供商、模型和推理强度可在聊天页按会话切换。

### Claude Code

Claude Code 适合代码分析、复杂工具编排、文档处理和通用任务。应用为它提供独立聊天与历史记录、Anthropic 官方接口、阿里云 Coding Plan 和自定义 Anthropic 兼容提供商。Claude 通过 AnyClaw 工具层访问本地终端、Ubuntu、Alpine、系统链路和共享浏览器。

### OpenMinis

Minis 基于 OpenMinis 1.12 官方 Android 运行时，替代了旧版可见 OpenClaw 入口。它拥有自己的聊天、会话、模型提供商和权限页面，并提供真实可见、可由用户随时接管的 WebView 浏览器。Minis 原生使用轻量 Alpine 环境，同时可以访问口袋大龙虾提供的另外两套终端。

### 手机操作智能体

手机操作智能体读取实时屏幕画面并输出 Launch、Tap、Type、Swipe、Back、Home、Wait、Double Tap、Long Press、Take over 或 Finish 动作。用户可以选择主屏幕或隔离虚拟屏幕、设置最大步骤数，并配置 AutoGLM、GUI Plus 或通用 JSON 协议的视觉语言模型。文本输入、键盘收起、页面滚动、动作纠错和多种模型输出变体由宿主统一处理。

## 三智能体协作

协作范围是 Codex、Claude Code 和 Minis。用户可以从任意一个智能体页面开启协作，并让当前智能体担任总调度。总调度根据任务自行决定直接回复、向用户澄清，或向一个或两个成员委派边界明确的子任务。

协作采用总调度与成员之间的星形消息链路。成员结果回到总调度，由总调度审核、处理冲突并向用户给出最终回复。共享工作区默认关闭，只有共同产出文件或确实需要交换文件时才启用。协作看板显示当前任务、历史任务、每轮状态、角色、委派内容、成员回复和总调度结论，并支持继续协作、重命名、导出、分享、删除和清空历史记录。

## 三套终端与系统链路

三个主智能体共享以下执行环境：

- Android 本地终端：Termux 风格的应用私有环境，适合直接处理应用文件、Node.js、Python、Git、网络工具和共享存储。
- Ubuntu 24.04：通过 PRoot 提供完整发行版环境，拥有 apt、Git、Python 和常见 Linux 工具。
- Alpine Linux：来自 OpenMinis 的轻量运行时，适合快速命令和隔离任务。
- system-shell：通过 Shizuku 获得 Android shell 身份，用于系统查询、设备状态和授权范围内的系统命令。它是系统执行链路，不计入三套 Linux 终端。

三个主智能体还共享同一个真实浏览器。浏览器标签由宿主统一管理，显式标签操作会串行化，用户可以打开浏览器页面接管当前状态。

## 模型管理

Codex 支持 OpenAI 登录授权和 Responses 兼容提供商，模型选择与推理强度在聊天输入区即时生效。Claude Code 支持 Anthropic 协议与兼容网关。Minis 使用 OpenMinis 自带的提供商管理。手机操作智能体单独管理视觉模型，并支持 AutoGLM Native、GUI Plus Native 和通用 JSON 三类协议。

API 密钥保存在应用私有配置中。仓库、日志和发布产物不应包含用户凭据。

## 无障碍与移动端交互

项目从真实屏幕阅读器使用出发设计。聊天消息、智能体状态和协作结果使用可独立聚焦的区块；长内容可以收起和展开；协作输入区固定在页面底部；上一轮和下一轮按钮避免依赖长距离滚动；清空、删除、导出等操作提供明确标签和二次确认。运行过程不会持续强制播报每一次页面变化，用户可以按需移动焦点读取状态。

## 安装与首次配置

1. 安装对应渠道的 APK。
2. 安装并启动 Shizuku，向口袋大龙虾授权。
3. 在权限管理页检查 system-shell 状态。
4. 在权限管理页点击安装或修复 Codex，应用会下载并安装当前锁定的 Codex CLI 版本。
5. 分别为 Codex、Claude Code、Minis 和手机操作智能体配置所需账户或模型提供商。
6. 按需授予悬浮窗、相册、相机、麦克风、联系人、位置等可选权限。

核心运行不要求 root。执行中的长任务依赖前台服务；部分厂商的一键清理会强制结束应用进程，应避免在任务运行时使用。

## 发布渠道

- prod：正式发布，包名 com.codex.mobile.pocketlobster，APK 名 pocket-lobster.apk。
- test：长期通讯与操作通道，包名 com.codex.mobile.pocketlobster.test，APK 名 pocket-lobster-test.apk。
- beta：隔离测试通道，包名 com.codex.mobile.pocketlobster.beta，应用名口袋大龙虾测试版。

三个渠道使用固定签名，可各自覆盖更新；不同包名可以并行安装。GitHub Actions 会完成源码契约测试、前端和服务端构建、OpenMinis 与虚拟屏幕运行时构建、APK 身份校验、进程隔离校验、运行时载荷校验和签名校验。

## 文档

- docs/pocket-lobster/PROJECT_OVERVIEW_2026-09-05.md：完整产品介绍与使用场景。
- docs/pocket-lobster/AGENTS_AND_RUNTIMES_2026-09-05.md：四智能体、工具权限和运行时边界。
- docs/pocket-lobster/VERSION_LINEAGE_2026-09-05.md：主线、黄金基线和发布渠道。
- docs/pocket-lobster/RELEASE_NOTES_V341_2026-09-05.md：v341 正式发布说明。
- NOTICE：上游来源与第三方许可证。
- LICENSES：OpenMinis、Operit 等第三方组件的许可证与历史声明。

## 构建

仓库使用 GitHub Actions 作为标准构建路径。Build APK 工作流接受 prod、test 和 beta 三种 release_channel。构建依赖 Node.js 22、JDK 17、Android SDK 36、NDK 28 和 CMake 3.22.1，并从仓库 Secrets 读取固定签名材料。

## 项目血缘与许可证

口袋大龙虾整合并扩展了 OpenClaw Android 路线、OpenMinis 1.12 和 Operit Shower 虚拟屏幕组件。项目自 OpenMinis 集成线起以 GNU GPL v3 分发；历史 MIT 声明、OpenMinis 第三方声明和 Operit LGPL v3 组件声明均保留在仓库及 APK 内。分发、修改或再发布前请阅读 LICENSE、NOTICE 和 LICENSES。