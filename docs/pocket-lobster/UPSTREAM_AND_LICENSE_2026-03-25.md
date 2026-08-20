# 口袋大龙虾 上游血缘与许可证说明
生成日期：2026-03-25

## 中文版本

口袋大龙虾的正式说明必须把真实项目血缘写清楚。

官方源头仓库是 `openclaw/openclaw`，项目主页为 `https://openclaw.ai`，许可证为 MIT License。

直接安卓实现上游是 `friuns2/openclaw-android-assistant`。该安卓实现本身继承自官方 OpenClaw 项目，并提供了在 Android 上打包 OpenClaw 与 Codex 的基础实现路径。

当前项目仓库是 `zhangzheyuanviolin-ship-it/pocket-lobster-android`。它是在上述安卓实现基础上继续进行双智能体整合、双终端扩展、Shizuku system shell 打通、共享存储访问、项目恢复归档、发布治理与无障碍协作演进后的独立安卓项目。

自 v300 起，项目增加 `OpenMinis/OpenMinis` 作为 Minis 替换路线的直接上游，并固定到 1.12 标签与提交 `09fc199928de0f26685e766c34e6d541c7a69e5a`。由于后续组合发行物将包含 GPLv3 的 OpenMinis 代码，口袋大龙虾从该集成线开始按 GNU GPL v3 分发；原有 MIT 上游与历史许可证仍完整保留在 `LICENSES/`，不抹除原作者归属。

## English Version

Pocket Lobster documents its upstream lineage explicitly.

The original upstream source is `openclaw/openclaw`, with the project site at `https://openclaw.ai`, under the MIT License.

The direct Android implementation upstream is `friuns2/openclaw-android-assistant`. That repository extends the official OpenClaw project into an Android packaging path for OpenClaw and Codex.

The current project repository is `zhangzheyuanviolin-ship-it/pocket-lobster-android`. It evolves beyond that Android implementation through dual-agent integration, dual-terminal runtime support, Shizuku-backed system-shell access, shared-storage access, recovery-oriented asset archiving, release governance, and accessibility-focused collaboration.

Starting with v300, `OpenMinis/OpenMinis` is added as the direct upstream for the Minis replacement path, pinned to tag 1.12 and commit `09fc199928de0f26685e766c34e6d541c7a69e5a`. Because later combined distributions will include GPLv3 OpenMinis code, Pocket Lobster is distributed under GNU GPL v3 from this integration line onward. Historical MIT licenses and attribution remain preserved under `LICENSES/`.
