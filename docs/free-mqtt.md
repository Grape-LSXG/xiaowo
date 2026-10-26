# 免费的 MQTT 托管服务（2026-10 核实）

> 口径：2 台手机，7×24 在线，每天几百条文本消息。
> 一个月 = **86,400 session 分钟** ≈ 0.012 百万条消息（心跳另算，30 秒一次的话约 17 万条/月/端）。
> 表格里 ✓ = 官方页面确认，? = 官方页没写清/未逐字核实。

## 一、能用的免费托管

| 服务 | 免费额度（✓ 官方） | 端口/协议 | 信用卡 | 国内体感 |
|---|---|---|---|---|
| **EMQX Cloud Serverless** | **永久免费**：每月 100 万 session 分钟 + 1 GB 流量 + 100 万 rule actions | MQTT/TLS（控制台给域名） | 不需要 | 跨境，可连，延迟一般 |
| **巴法云 bemfa** | 个人开发者**免费**（官方文档明示），主题数/消息额度未公开 | `bemfa.com`：9501 明文 / 9503 TLS / 9504 WSS | 不需要 | 国内，延迟低 |
| **Ably** | Free：200 并发连接、500 msg/s、600 万条/月、200 channel、64 KiB/条 | `main.mqtt.ably.net:8883` TLS | 不需要 | 跨境，可用但限制多 |
| **CloudAMQP** | LavinMQ「Loyal Lemming」40 连接/200 万条/月；RabbitMQ「Little Lemur」20 连接/100 万条/月 | LavinMQ 原生 MQTT 1883/8883 | 不需要 | 旁路 MQTT，不推荐 |
| **腾讯云 IoT Hub** | ✓ **没有免费额度**，纯按量：连接 1 元/百万分钟、消息 3.6 元/百万条 → 我们约 **¥0.17/月** | 需实名 + 三元组 + 物模型 | 不需要（要实名） | 国内最好，但接入手续重 |

### 已经不能用 / 别用的

- **HiveMQ Cloud 免费层**：pricing 页（2026-10 抓取）只剩 Starter（$0.34/小时起）和 Enterprise，
  免费只剩**自托管 Lab**（1 broker / 25 连接 / 30 天手动续期）。营销页还写着 free，
  与定价页矛盾，按"托管免费层已没有"处理。
- **阿里云 IoT**：官方公告 **2025-02-01 起不再支持新购**，新用户开不了。
- **公共 broker**（`broker.emqx.io`、`test.mosquitto.org`、`broker.hivemq.com`）：
  官方明示「所有消息对其他用户可见，不要发敏感数据」。小窝的订单/聊天全是明文，
  **不该再作为默认通道**（房间码 6 位，猜中就能看）。
- **OneNET**：官网已 AIoT 化，个人免费政策查不到官方页面，不作为首选。

## 二、结论（按"小窝"的实际用量）

1. **要免费又要稳**：选 **EMQX Cloud Serverless**。它用掉的是 100 万分钟里的 8.6%、
   1 GB 流量里的一点点；带 TLS 和用户名密码；不用信用卡；缺点是在境外，晚高峰延迟会抖。
2. **要国内快**：选 **巴法云**。延迟最好，但 QoS2 会被踢、`/set` 不回推给自己、
   免费额度没公开数字。用它之前先在免费账号上跑一天，确认没被限流。
3. **HiveMQ 免费层可以放弃了**，阿里云 IoT 也开不了。
4. **腾讯云 IoT Hub 明面上"近免费"（¥0.17/月）但手续最重**（实名 + 设备三元组 + 物模型），
   两台手机不值得。
5. 不管用哪家：**每台设备一个账号 + 服务端 ACL 锁到自己房间**，
   别两台设备共用一个弱密码 —— 一个主题 + 弱鉴权，等于谁都能进来看。

## 三、换成免费托管时 App 怎么填

v4.7 起「连接方式 → 异地中转 → 自建服务器」可以填任意 broker：

| 服务 | 地址 | 端口 | 用户名 | 密码 | TLS |
|---|---|---|---|---|---|
| EMQX Serverless | 控制台给的 `xxxx.ala.cn-hangzhou.emqxsl.cn` 之类 | 8883 | 控制台创建的账号 | 同名密码 | 开 |
| 巴法云 | `bemfa.com` | 9503 | 你的私钥（clientId 方式）或 appID | secretKey | 开 |
| Ably | `main.mqtt.ably.net` | 8883 | API key 前缀 | key 后半段 | 开 |
| 自建 mosquitto | 你的公网 IP / 域名 | 1883 或 8883 | 房间码 | 自己的密码 | 看是否配了证书 |

> 巴法云的鉴权是「clientID = 私钥」，和标准 MQTT 用户名/密码不完全一样；
> 真要用它，需要再改一次 `Mqtt.connectPacket()`（把私钥塞进 clientId）。
> EMQX Serverless 是标准用户名密码，现在的代码直接能用。

参考：EMQX Serverless https://www.emqx.com/en/cloud/serverless-mqtt ，
HiveMQ 定价 https://www.hivemq.com/pricing/ ，
Ably 定价 https://ably.com/pricing ，
CloudAMQP https://www.cloudamqp.com/plans/ ，
腾讯云 IoT 计费 https://cloud.tencent.com/document/product/634/15240 ，
阿里云 IoT 计费公告 https://help.aliyun.com/zh/iot/product-overview/billing-overview ，
巴法云 MQTT 文档 https://cloud.bemfa.com/docs/src/mqtt.html 。
