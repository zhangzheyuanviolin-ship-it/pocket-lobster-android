# Pocket Lobster v341 Release Notes

发布日期：2026-09-05  
版本：1.0.100  
versionCode：341  
正式包名：com.codex.mobile.pocketlobster

## 本次发布

v341是口袋大龙虾从早期双智能体原型发展为四智能体Android工作平台后的首个完整正式基线。它包含三个可独立或协作运行的通用智能体、一套手动手机操作智能体、三套Linux终端、Shizuku系统链路、共享可见浏览器、模型提供商管理、会话管理、协作看板和无障碍交互。

## 四智能体

Codex负责软件工程、仓库、终端、测试和发布。Claude Code负责复杂分析、工具编排和文档任务。OpenMinis 1.12提供移动原生通用助手、真实浏览器和Alpine运行时。手机操作智能体通过主屏幕或虚拟屏幕执行Android界面任务。

## 三智能体协作

Codex、Claude Code和Minis中的任意一个都可以担任总调度。每轮由总调度判断是否委派，并选择需要的成员。成员输出回到总调度审核，用户在统一协作看板查看角色、任务、结果、最终回复和多轮历史。

共享工作区只在共同产出文件时启用。任务状态持久化，应用或成员进程重启后可以恢复历史记录。看板支持继续协作、上一轮与下一轮、收起与展开、重命名、导出、分享、删除和一键清空。

## 终端和浏览器

三个主智能体共享Android本地终端、Ubuntu 24.04和Alpine 3.21，并通过Shizuku使用system-shell。共享浏览器支持真实WebView页面、标签页、DOM、JavaScript、Cookie、截图和用户接管。

浏览器使用独立:minis进程和独立WebView数据目录，显式标签动作由宿主串行化，解决同目录多进程锁冲突。

## 手机操作智能体

手机操作支持AutoGLM Native、GUI Plus Native和Generic JSON协议，支持主屏幕与隔离虚拟屏幕。v341增强了Swipe坐标兼容、Type字段诊断、tool_calls和function_call解析、动作纠错重试、提供商错误详情、文本输入与键盘收起。

手机操作入口在本版本保持手动使用。三个主智能体不提供调用手机操作智能体的工具。

## Codex与模型

目标Codex CLI升级到0.153.4。用户在权限管理页点击安装或修复即可覆盖旧CLI。Codex支持OpenAI账户授权和Responses兼容第三方提供商，并可在聊天页切换提供商、模型和推理强度。

Claude Code和Minis保留各自的模型提供商管理。手机操作智能体拥有独立视觉模型配置，不与通用聊天模型混用。

## 安装要求

需要ARM64 Android 8.0或更高版本。核心功能不要求root。system-shell、虚拟屏幕和部分系统操作需要Shizuku运行并授权。Codex CLI需要在应用首次配置后从权限管理页安装。悬浮窗、相册、相机、麦克风、联系人、日历和位置按功能需要单独授权。

## 已验证范围

v341的beta和test渠道已经完成覆盖安装、Codex更新、GPT-6 Astra连通、Codex第三方Responses模型、四个智能体入口、三个终端、共享浏览器、三个主智能体独立模式、三智能体单轮与多轮协作、协作看板、手机操作多模型协议、共享存储和屏幕阅读器交互测试。

正式prod构建使用相同核心源码、versionCode和固定签名流程，并由GitHub Actions重复执行源码契约、前端、服务端、Android、进程隔离、运行时载荷、包身份和签名校验。

## 许可证

Pocket Lobster以GNU GPL v3分发。OpenMinis来源、历史MIT声明、Operit Shower组件的LGPL v3声明和第三方依赖通知保存在NOTICE、LICENSES及APK资产中。