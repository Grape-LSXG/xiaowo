# 不花钱的方案：免费云主机 / 内网穿透 / 家宽 IPv6（2026-10 核实）

> 标 ✓ = 官方页面确认；标「社区」= 社区共识、未拿到官方原文。
> 这三个前提我得先纠正，网上很多文章还是旧的：
> **① Oracle ARM 免费额度 2026-06-15 起砍半为 2 OCPU/12GB**（老实例会被要求改 shape），
> **② AWS 新账号已经没有 12 个月免费 t3.micro**（改成最多 $200 credits、6 个月后关号），
> **③ Tailscale Personal 是"设备不限"，不是"100 台"**。

## 一、结论先放前面（针对"2–5 台手机、24 小时在线、每月几十 MB"）

| 排序 | 方案 | 成本 | 真常驻？ | 大陆延迟 | 主要坑 |
|---|---|---|---|---|---|
| 1 | **家宽 IPv6 + DDNS + 家里旧手机/树莓派跑 mosquitto** | 0 | 是 | 10–40ms | 得拿到可入站的公网 IPv6、路由放行 1883；运营商理论上仍可能判"私设服务器" |
| 2 | **Oracle Always Free（东京/大阪）** | 0 | 是 | 40–90ms | 要国际信用卡、注册玄学、A1 常没容量、闲置 30 天可能回收 |
| 3 | **继续花 68 元/年（阿里云轻量，见 self-host-relay.md）** | 68 | 是 | 10–30ms | 没有——这才是最省心的那个 |
| 4 | Cloudflare Tunnel + 家里设备（只能 MQTT over WSS） | 0 但要有域名 | 是 | 一般 | 免费版**不能转发裸 TCP 1883**，只能 HTTP/HTTPS/WS |
| 5 | 不推荐 | —— | —— | —— | 见下表 |

一句话：这点负载**免费方案的风险在"随时被收回/被风控/要装客户端"，不在额度**。
要么控制在自己手里（家里的 IPv6），要么买已经验证稳定的（68 元/年）。

## 二、免费云主机逐个核实

| 方案 | 是否真长期免费 | 门槛 | 大陆可用性 | 备注 |
|---|---|---|---|---|
| **Oracle Always Free** ✓ | 是（"available for an unlimited time"） | 信用卡或可当信用卡用的**借记卡**（不收预付卡/虚拟卡/PIN 借记卡）；一人一号 | 无大陆区；东京/大阪 40–90ms 最好，其次首尔、新加坡 | 额度现在是 AMD `VM.Standard.E2.1.Micro` ×2 + **Arm A1 2 OCPU/12GB**（2026-06-15 起砍半）+ 200GB 块存储；"Accounts left idle for 30 days or more may be deemed abandoned"；A1 常见 out of host capacity |
| Google Cloud 免费层 ✓ | 每月 1 台非抢占 `e2-micro` | 绑卡 | **只限 us-west1 / us-central1 / us-east1（全在美国）**，延迟 150–250ms；免费出站 1GB/月且**明确排除中国和澳大利亚** | 含 30GB 标准盘；跑 MQTT 延迟太差，不推荐 |
| AWS 免费层 ✓ | **新账号已无 12 个月免费机**：Free plan 最多 $200 credits、**6 个月后自动关号、90 天后删数据**（FAQ 原文 "You cannot extend your Free plan beyond 6 months"） | 绑卡 | 需国际卡 | 老账号待遇不变 |
| Fly.io | **已无免费层** | 绑卡 | —— | 试用 2 machine hours 或 7 天；常驻约 $2.19/月起 |
| Render Free | 750 instance-hours/月 | 注册 | —— | 15 分钟无入站流量即休眠；**免费 web service 不能接收私有网络流量**，只有 HTTP/HTTPS，没有公网 TCP |
| Koyeb Free | 512MB/0.1vCPU | 可能需绑卡 | 仅法兰克福/华盛顿 | 1 小时无流量 scale-to-zero；有 TCP Proxy 但与休眠不兼容 |
| Railway / HF Spaces / Codespaces | 都是试用或非服务器 | —— | —— | HF 只暴露 7860 HTTP；Codespaces 端口只能 HTTPS 转发 |
| Serv00 之类免费 shell | 不是给你跑常驻服务的 | 注册 | 有 | 无 root、要自编 mosquitto、2025 有封号潮；国内三丰云/阿贝云是"发帖延期"套路 |

## 三、内网穿透

- **Cloudflare Tunnel 免费版** ✓：官方 Spectrum 文档写 "Available on Paid plans"、"Custom TCP/UDP applications require an Enterprise plan"。
  免费只能：① Public hostname 的 **HTTP/HTTPS/WebSocket**（→ 可跑 MQTT over WSS）；
  ② WARP-to-Tunnel 私网（手机得装 WARP）；③ `cloudflared access tcp`（要在手机上跑客户端，不现实）。
- **Tailscale** ✓：Personal $0 永久免费、**设备不限 + 最多 6 用户 + 50 tagged resources**。
  同 tailnet 直接连 `100.x.y.z:1883` 完全可行；Funnel 全计划可用但只能 443/8443/10000 且必须 TLS。
  坑：大陆无官方 DERP，打洞失败会走海外中继（慢/易断）；Android 后台仍会被省电杀。
- **ZeroTier**：免费 10 设备/1 网络，可自建 moon 改善线路。

## 四、家宽 IPv6（0 元、延迟最低，但要么能要么不能）

- 现状：CNNIC 2025-12 数据 IPv6 活跃用户 8.5–8.69 亿，手机与固网普遍双栈 ✓。
- 前提：光猫/路由器拿到**公网 /64**、路由器 IPv6 防火墙放行 1883/8883、加 DDNS（前缀会变，最好固定后缀）。
- 80/443/8080 被封，**1883 不封**；MQTT 不是网站，不需要 ICP 备案。
- 风险（社区）：运营商仍可能判定"私设服务器"；2025 有广东移动高端口 Web 服务被要求签保证书、北京移动 5G CPE 实测 IPv6 不可入站（电信多省可）。
- 自测：手机切到流量，`nc -vz [你的IPv6]:1883` 能通才算成。

## 五、官方面 vs 社区待验证

| 事项 | 状态 |
|---|---|
| Oracle 免费额度、要卡、闲置回收条款 | ✓ 官方 |
| Oracle ARM 砍到 2 OCPU/12GB | ✓ 官方文档额度表述已改（1500 OCPU 小时 + 9000 GB 小时/月） |
| AWS 新账号 6 个月 Free plan | ✓ 官方 FAQ |
| Tailscale Personal 设备不限/6 用户 | ✓ 官方定价页 |
| Cloudflare 免费版不能转发任意 TCP | ✓ Spectrum 文档 |
| GCP 免费 e2-micro 仅美国三区、出站不含中国 | ✓ 官方（本机网络访问不到 cloud.google.com，取的是官方页快照） |
| Koyeb 是否需绑卡+$29 预授权、Serv00 常驻 TCP 合规性、家宽 IPv6 各省入站策略 | 社区，未验证 |

参考：[Oracle 免费层](https://www.oracle.com/cloud/free/)、[Tailscale 定价](https://tailscale.com/pricing)、[Cloudflare Spectrum](https://developers.cloudflare.com/spectrum/)。
