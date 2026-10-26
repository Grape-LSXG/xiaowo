# 不花钱的方案：免费云主机 / 内网穿透 / 免费 MQTT（2026-10 核实）

> 结论压在最后，先看事实。
> 标 ✓ 的是官方页面确认；标「经验」的是我按常见情况写的，自己验一下。

## 一、免费云主机

| 方案 | 是否真长期免费 | 门槛 | 大陆可用性 | 备注 |
|---|---|---|---|---|
| **Oracle Cloud Always Free** | ✓ 官方：Always Free 服务"unlimited time"，含 AMD Compute 与 Arm Ampere A1 Compute | 必须信用卡/借记卡（**不收预付费卡、虚拟卡**）；一人一号；**闲置 30 天可能被回收** | 可注册，但大陆用户常在账单校验被拒；区域选日本/新加坡/首尔延迟 ~40–90ms | 2 核 2G mosquitto 属于它的零头，够到离谱 |
| Google Cloud 免费层 | 有免费层（e2-micro 一台级），额度随时调整 | 要信用卡 | 需国际卡 | 额度细节以开通时控制台为准，我没逐字核实 |
| AWS 免费层 | 有过 12 个月免费 ec2 micro | 要信用卡 | 需国际卡 | 2025 年后规则调整过，开通前看控制台 |
| Fly.io / Render / Koyeb 之类 | 免费额度反复调整，且多数**不支持任意 TCP 端口**（只给 HTTP） | 注册 | 不稳 | 想跑 MQTT 1883 基本都不行，别指望 |
| 免费 shell 主机（Serv00 之类） | 不是给你跑常驻服务的 | 注册 | 可 | 常驻 mosquitto 违反条款，容易被封，不推荐 |

**为什么大多数"免费 PaaS"不能用**：它们只把 80/443 的 HTTP 暴露出来，MQTT 需要一条裸 TCP 长连接。
要么用能开任意端口的 VPS（Oracle 这种），要么用 MQTT 托管（见 `docs/free-mqtt.md`）。

## 二、不买服务器也能"端到端打通"的办法

### 1. Tailscale（推荐，免费额度大方）

✓ 官方 Personal 计划 **$0 / 永久免费**，最多 6 个用户、设备数量不限。

玩法：家里任一台常开的机器（旧笔记本、树莓派、路由器旁的小主机、甚至台式机）
装 Tailscale 跑 mosquitto，两台手机也装 Tailscale。手机通过 `100.x.y.z:1883`
直连它，**完全不需要公网 IP、不需要开端口、不经过任何第三方 broker**。

- 优点：0 元、加密、手机和服务器之间是 WireGuard 直连（打不通时走 DERP 中继）。
- 缺点：手机得常驻 Tailscale（费电、被系统杀）；小窝代码里已经预留了 Tailscale 通道
  （`docs/tailscale*.md`），但作为"主机常开 + 手机 VPN 一直挂着"的场景，不如 MQTT 省心。
- Funnel（把服务公开到 443）也在免费计划里，但它只适合 HTTP(S)/TCP-443，
  且要域名；没必要为此折腾。

### 2. Cloudflare Tunnel（免费版做不了 MQTT）

免费版只能把 **HTTP/HTTPS**（以及 SSH/RDP）公开出去；
要公开任意 TCP 端口（1883）需要 Spectrum（付费）。
所以"用 Cloudflare Tunnel 把家里的 mosquitto 公开给公网"这条路，**免费走不通**。

### 3. 家宽公网 IPv6 + DDNS（0 元，但要折腾）

- 「经验」国内三大运营商的家宽基本都下发公网 IPv6，但需要路由器开 IPv6 防火墙放行、
  手机侧也有 IPv6（4G/5G 一般有）。
- 问题：家宽重启/换 IP、运营商偶尔回收 IPv6 前缀、路由器防火墙默认全封。
- 适合爱折腾的人；不适合"明天就要稳定用"。

## 三、结论：三条路，按省心程度排序

1. **0 元 + 今天就能用 → EMQX Cloud Serverless 免费托管**
   不用服务器、不用信用卡，1M session 分钟/月（你们只用 8.6%）。
   v4.7 的「自建服务器」填 EMQX 给的域名:8883 + 账号密码就能跑。
   细节和账号注册见 `docs/free-mqtt.md`。
2. **0 元 + 自己掌握 → Oracle Always Free（日本区域）**
   注册要信用卡、可能被拒；一旦开出来就是白嫖一台机器。
   mosquitto 的安装/鉴权/ACL 命令和 `docs/self-host-relay.md` 一模一样，
   只是安全组在 Oracle 控制台（Security List / NSG）里加，不是阿里云。
3. **68 元/年 + 最稳最省事 → 阿里云**
   不用赌注册、国内延迟最低、续费价提前看清。
   完整命令见 `docs/self-host-relay.md`。

> 不管选哪条，**先把 v4.7 装上**：心跳修好之前，换任何服务器都还是会"每 90 秒断一次"。

参考：
Oracle 免费层 https://www.oracle.com/cloud/free/ ，
Tailscale 定价 https://tailscale.com/pricing 。
