# CLIProxyAPI-Android

> **非官方 Android 封装项目（Unofficial Port）**
>
> 本项目是 [luode0320 / router-for-me 的 **CLIProxyAPI**](https://github.com/router-for-me/CLIProxyAPI)
> 的**非官方 Android 移植/封装**，并非原作者发布。所有核心程序（`cli-proxy-api`
> 后端、管理面板）版权归原作者所有，本项目仅做 Android 打包与运行环境适配。

把 [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) 后端、Web 管理面板
与 **PRoot + Ubuntu glibc 运行环境**打包成一个 Android APP。打开 APP 即自动启动
本机服务（`127.0.0.1:8317`），并加载管理面板。

> **为什么需要 PRoot + glibc？**
> Android 原生 bionic 环境无法 `dlopen` 依赖 `libc.so.6` / `ld-linux-aarch64.so.1`
> 的 **原版 Linux glibc 插件**（如 workbuddy-provider）。在 glibc 用户空间中运行
> 官方 Linux arm64 版后端后，原版 `.so` 插件可原样加载使用，无需修改。

---

## 作者与来源

- **上游项目（核心程序）**：[router-for-me/CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI)
- **插件生态**：[luode0320/cpa-workbuddy-plugin](https://github.com/luode0320/cpa-workbuddy-plugin)
- **本封装**：Elas0415（非官方）

请优先支持上游作者，给上游仓库点 ⭐。

---

## 功能

- 打开 APP 自动启动本机 CLIProxyAPI 服务（127.0.0.1:8317），WebView 加载管理面板
- 内置 **PRoot + glibc** 环境，支持原版 Linux glibc arm64 插件
- 管理面板侧边栏「控制」分组注入 **导入 CPA 插件**（支持 `.so` / `.zip`，自动改写 config 并热重启）
- 内置小型浏览器：所有外部链接在 APP 内打开
- 检查更新：从 GitHub 拉取最新 `linux_aarch64` 后端并替换
- 局域网访问开关（供同网络设备访问管理面板）
- 后台保活：引导申请电池优化豁免
- 全屏沉浸式（状态栏下拉临时显示）

---

## 下载与安装

1. 到本仓库 [Releases](../../releases) 下载最新 `CLI Proxy_x.x.apk`
2. 侧载安装（需 **arm64** 设备，Android 8.0+ / API 26+）
3. 首次启动会解压内置 glibc 运行环境（几秒）
4. 默认管理密钥：`admin`（请在管理面板中尽快修改）

> ⚠️ 仅供个人学习使用。请遵守上游项目许可证与相关服务条款。

---

## 架构

```
┌──────────────── Android APP (arm64) ────────────────┐
│  MainActivity / BrowserActivity                       │
│    └─ ServerService (前台服务)                        │
│         └─ PRoot -0 -r rootfs ... /opt/cpa/cli-proxy-api
│              └─ 加载 rootfs/opt/cpa/plugins/*.so       │
│  WebView ← http://127.0.0.1:8317/management.html      │
└───────────────────────────────────────────────────────┘
```

- rootfs：精简 Ubuntu glibc（仅保留 glibc 核心库 + CA 证书）
- 后端：官方 `linux_aarch64`（带插件支持）release 二进制
- 插件目录：`rootfs/opt/cpa/plugins/`

---

## 许可证

- 本项目仅包含打包脚本与适配代码，**核心程序版权归原作者**。
- 请遵循上游 [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) 的许可证。
