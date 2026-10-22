# 要不要把 Tailscale 内置进 App？

**结论：不建议。** 一句话理由 ——
内置它要同时背下 **24 MB 体积**、**一个 VpnService 槽位竞争**、以及
**用户仍然需要 Tailscale 账号** 三件事，却换不到现有 HTTP/UDP 方案做不到的能力。

---

## 一、`libgojni.so` 是什么（实测）

从 `/sdcard/Download/Tailscale.apk` 里读到的硬数字：

| 项 | 值 |
|---|---|
| `lib/arm64-v8a/libgojni.so` | **24.12 MiB**，APK 内**未压缩**（compress_size == file_size） |
| zlib -6 压缩后 | **8.42 MiB**（34.9%） |
| 其他架构 | armeabi-v7a 23.7 / x86 23.3 / x86_64 26.5 MiB |
| 版本 | `tailscale.com@v1.104.2`（内嵌字符串） |
| ELF 符号 | 105 个 `Java_libtailscale_*` + 448 个 gomobile 辅助符号 |

它是 **gomobile bind 产物**，包名 `libtailscale`，不是能随便调用的 SDK。
符号形如 `Java_libtailscale_Libtailscale_start`、
`Java_libtailscale_VPNFacade__1_1New`（`_1`=`_`、`_00024`=`$`）。

分段：`.text` 8.86 / `.gopclntab` 8.15 / `.data.rel.ro` 3.02 / `.rela.dyn` 2.88 MiB。
其中 `.gopclntab` 是 Go 栈回溯必需的，`-s -w` 去不掉，符号表本来也剥过了。

## 二、为什么不能只加载库

Go 侧会**反向回调 Java**，宿主必须提供：

- `libtailscale.AppContext` 约 30 个方法
- `VPNServiceBuilder` / `VpnService` / `NotificationManager` /
  `ParcelFileDescriptor` / `ShareFileHelper` 等接口

也就是说得把 `libtailscale` 那套 Java 层整份搬进来。

**而且必须自己实现 VpnService**：`VPNServiceBuilder.establish()` 直接返回
`VpnService.Builder.establish()` 的 fd，tun 设备完全来自它。
已核实官方 APK 的 `AndroidManifest.xml`（Binary XML 是 UTF-16 编码的，
用 ASCII 搜会漏）里确实有 `VpnService` 和 `BIND_VPN_SERVICE`。

## 三、三个绕不过去的代价

**1. 体积**：+24.12 MiB（`extractNativeLibs=false` 可压到 ~8.4 MiB）。

裁剪标签确实存在（`featuretags.go` 有 87 个可 omit，含 `netstack`/`ssh`/`drive`/
`serve`/`acme` 等），但官方 Android 构建只用了 `ts_omit_cachenetmap` ——
24 MB 里塞满了用不到的功能。裁剪后估 raw 12–16 MiB，**这是估计，必须真机构建实测**。

**2. VpnService 槽位竞争**：Android 同一时刻只允许一个 VPN。
用户开了别的加速器（这台手机上就装了 Proton VPN、绿茶 VPN、蚂蚁 VPN、老王 VPN），
你的 `establish()` 就会失败。**内置并没有消除冲突，只是把冲突从"两个 App"
变成"一个 App + 用户其它 VPN"。**

**3. 仍然需要账号**：`backend.go` 里 `os.Setenv("HOME", dataDir)` 说明要持久化目录；
登录靠 `StartLoginInteractive` 产出 `AuthURL`，得自己渲染给用户跳浏览器，
或预置 AuthKey/OAuth。**没有 Tailscale 账号（或自建 headscale），内置库毫无用处。**

> 补充：还有一个 `tsnet` 路子 —— 它用 gVisor 用户态栈，
> 官方注释明确 "No root privileges required"，不需要 VpnService。
> 但只能走自己进程的 socket（`Dial`/`Listen`），不能接管系统路由，
> 照样要写 Go→JNI 薄封装 + 运维 headscale。

## 四、替代方案

| 方案 | 体积增量 | 工作量 | 可靠性 | 关键代价 |
|---|---|---|---|---|
| a) 用户自装 Tailscale（现状） | 0 | 0 | 高 | 用户要账号 + 装 App |
| b) wireguard-go + 自建 WG | 5–8 MiB/架构 | 15–25 人天 | 中 | NAT 穿透/密钥分发全自写，最易翻车 |
| c) Cloudflare Tunnel / ngrok / frp | 0 | 2–4 人天 | 高 | 依赖第三方、有配额 |
| d) 自建 MQTT/WebSocket 中继 | 0 | 3–5 人天 | 高 | 需服务器带宽，无 P2P |
| **e) 现有 UDP 打洞 + 中继兜底** | **0** | **2–3 人天** | **高** | 复用现成发现逻辑 |
| f) 自封装 tsnet + headscale | 12–18 MiB（估） | 8–12 人天 | 中高 | 免 VpnService，但要写 JNI + 运维 headscale |

## 五、建议

**选 e**：保留现有 UDP 打洞（`Net.discover`），加"打洞失败 → 自建中继转发"兜底。

理由很简单：现在两台手机在同一个 Wi-Fi 下已经**完全不需要 Tailscale**
（UDP 8766 自动发现就通了）。Tailscale 只在**异地**时才需要，
而异地场景下"让用户装一个 Tailscale"本来就是可接受的 ——
它是个成熟、免费、跨平台的产品，比自己造一个可靠得多。

真要减少摩擦，优先做这两件成本更低的事：

1. **把打洞失败的中继兜底做出来**（2–3 人天，零体积）。
2. **让连接过程更傻瓜**：现在需要手动粘贴 `100.x:8765`，
   可以做成"扫码配对"或"分享一个配对链接"，UX 收益远大于内置 Tailscale。

## 六、许可

Tailscale 是 BSD-3-Clause，代码内嵌合法。
但调用官方 coordination server 属服务条款范畴，第三方大规模内置通常需自建 headscale。
