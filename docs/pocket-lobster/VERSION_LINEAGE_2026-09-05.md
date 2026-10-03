# 版本谱系与稳定基线

更新日期：2026-10-03

## 当前主线

当前稳定业务源码为 v362，验证提交为 ee0610a06e2657c3371226acc4df8661a430817d，来自 feat/v362-safe-codex-cli-0160。用户于 2026-10-03 在真机覆盖安装并验证后授权提升为稳定版。稳定发布标签为 stable-v362-20261003；main 保留旧主线及后续恢复开发的完整祖先关系，新增稳定发布文档不改变任何运行代码。

v362 稳定 Release 分发的 APK 与 test 通道云构建 37106772702 和用户已安装版本逐字节一致，SHA-256 为 970ee57c83764f34b557f94265553b368b406c0ea1bcd8da5090697b17eb9bc8。本次稳定发布不重新构建 prod 包，也不把通讯包改名伪装为 prod。

2026-09-05的主分支整理使用双父合并提交保留旧main和v341开发线。最终文件树以已通过beta与test渠道验证的v341源码为准，并加入面向社区的正式文档。这样旧主分支的提交不会被强制改写，v341的完整迭代历史也保持可追溯。

## 关键阶段

早期阶段建立了Android宿主、Codex、OpenClaw、Android本地终端、Ubuntu和Shizuku system-shell。

OpenMinis阶段使用OpenMinis 1.12替换可见OpenClaw入口，加入独立:minis进程、Alpine运行时和真实WebView浏览器。项目许可证从该集成线起按GNU GPL v3分发。

协作阶段打通Codex、Claude Code和Minis的星形委派、成员回传、总调度审核、多轮任务恢复、历史任务、协作看板、导出和无障碍交互。

手机操作阶段引入Operit Shower虚拟屏幕组件和视觉模型执行循环，逐步修复受保护屏幕误判、主屏幕截图、文本输入、键盘收起、滚动死循环、动作格式差异和提供商诊断。

v341阶段将Codex CLI目标升级到0.153.4，准备GPT-6 Astra模型选择，并增强AutoGLM、GUI Plus和Generic JSON手机操作协议。Generic JSON支持更多Swipe变体、tool_calls与function_call，并把纠错尝试和原始响应诊断纳入运行循环。

v353 至 v360 阶段修复跨提供商本地工具类型、响应项目编号、历史物化和模型切换后的重启历史连续性。原始 JSONL 是持久化事实来源，派生历史投影与页面缓存不能替代原始记录。历史读取修复不得随意停止通讯宿主。

v361 阶段完善 Codex 与 Claude 自然语言参数配置和模型压缩，增加 Claude 手动安全更新。v362 阶段将 Codex CLI 安全更新目标提升到 0.160.0，加入隔离包哈希验证、真实消息与终端工具握手、状态快照、原子切换、两轮宿主复验及中断恢复。

## 黄金基线

golden-beta-v299-20260820：Codex Responses提供商与三智能体前的稳定测试基线。

golden-beta-v309-20260824：Codex、Claude Code和Minis共享三终端与浏览器的稳定基线。

v355-beta：v341隔离测试渠道构建，包名com.codex.mobile.pocketlobster.beta。

v356-test：v341长期通讯渠道构建，包名com.codex.mobile.pocketlobster.test，已完成覆盖更新和环境健康验证。

stable-v341-20260905：正式主分支与prod发布所对应的源码快照。该标签应指向包含正式文档的主分支合并提交。

stable-v362-20261003：当前用户验收的稳定通讯版。包名 com.codex.mobile.pocketlobster.test，versionCode 362；标签指向含发布文档但业务源码与 ee0610a 完全一致的快照。

rescue/v362-validated-r2000000002：从 ee0610a 独立建立的 v362 紧急回滚基线。仅提升版本身份与校验到 versionCode 2000000002，固定签名和包名不变，CLI 安全目标仍为 0.160.0。普通迭代号必须低于该预留救援号；下一份救援号只在新的稳定版获用户验收后递增。

## 发布渠道

prod使用com.codex.mobile.pocketlobster，面向正式发布。test使用com.codex.mobile.pocketlobster.test，作为长期通讯与操作环境。beta使用com.codex.mobile.pocketlobster.beta，与其他渠道并行安装，用于高风险功能验证。

三条渠道共享核心源码、固定签名策略和云端校验，但包名与应用标签按渠道隔离。任何影响运行时、会话、提供商或手机操作的高风险修改应先进入beta，通过实际测试后再提升到test和prod。

## 构建资产治理

源码提交、主分支、语义黄金标签和当前稳定标签属于长期资产。每次GitHub Actions自动生成的临时Artifact和历史预发布APK属于可再生成资产，不作为源码谱系依据。

稳定Release保留正式APK、提交SHA、版本号、包名、文件大小和SHA-256。测试与beta只保留当前已验证版本。旧APK可以从本地归档获取；旧源码通过主线提交和黄金标签回溯。

自动构建标签不承担长期版本语义。清理历史Release时可以保留标签或删除冗余自动标签，只要对应提交仍在主分支历史或黄金标签中。
