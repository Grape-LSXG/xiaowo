# 用 EMQX Cloud Serverless 当异地中转服务器

> 目标：两台手机跨城市也能稳定收单，不再用公共 broker（明文、谁都能订阅、经常断）。
>
> 结论先说：**Serverless 免费额度对我们这种用量绰绰有余**，但有几个硬限制必须照做，
> 否则连不上（最常见的是"用了 1883 端口"和"没填用户名密码"）。

## 1. Serverless 的三条硬限制

| 限制 | 说明 | 我们要怎么做 |
|---|---|---|
| 只支持 TLS 端口 | MQTT over TLS **8883**、WebSocket over TLS **8084**；**1883 / 8083 不通** | 端口填 `8883`，App 里勾选 TLS |
| 不允许匿名 | 必须先在控制台创建用户名/密码 | 「访问控制 → 认证」建一个用户 |
| 必须带 SNI | TLS 握手的 SNI 与域名不符会被拒，客户端看到 `错误码 -5` | 用**部署域名**连（不要用 IP），端口 8883 |

服务端证书是 EMQX 维护的公共 CA 签发的，Android / 我们的 `Mqtt.open()` 用系统 CA 就能校验通过，不需要手动导入 CA。
（如果你的客户端提示证书校验失败，再按官方文档下载 CA 文件。）

## 2. 控制台操作（一次性，5 分钟）

1. **建部署**：EMQX Cloud 控制台 → Serverless → 新建部署，选一个离你们近的区域（如 `cn-hangzhou`）。
2. **拿接入地址**：部署概览 → 「连接地址」。形如
   `abcd1234.ala.cn-hangzhou.emqxsl.cn`，端口 **8883**。
3. **建认证用户**：`访问控制 → 认证 → 添加`，选 **Username/Password（内置数据库）**，
   新建一个账号，比如 `用户名=xiaowo`、`密码=`一段随机强密码（16 位以上，别用生日）。
   - 这个账号会写进 APK 的 `assets/cloud.properties`，所以**别用你在别处用过的密码**。
4. **（可选）加 ACL**：`访问控制 → 授权`，只允许这个用户读写自己的 topic：
   ```
   allow  publish   xiaowo/<你要用的房间码>/msg
   allow  subscribe xiaowo/<你要用的房间码>/msg
   ```
   不加 ACL 也能用（同一部署内其它用户互相隔离不了），加了更干净。
5. **（可选）看用量**：概览里的 session 分钟 / 流量，我们的量是 2 台 × 720 小时 ≈ 86,400 分钟/月，
   免费额度 100 万分钟，占 8.6%。

## 3. App 侧要做什么

**什么都不用做** —— 从 v4.9 起，云端配置直接打进 APK：

```
assets/cloud.properties     # 不进 git（.gitignore 已排除），只在打包时复制进 APK
  host=abcd1234.ala.cn-hangzhou.emqxsl.cn
  port=8883
  user=xiaowo
  pass=<密码>
  tls=true
  room=WX8888               # 两端共用的房间码
```

行为：

- 用户**没手动配过**服务器 → 自动用上面这份预置（连接设置里显示「服务器：云端 …（已预置，不用配）」）。
- 用户手动填过「自建服务器」→ 以用户填的为准（想临时切回公共 broker 也行）。
- 房间码用户没配过时用预置的 `room` → **两端装好即通，点单端不用扫码、不用填码**。
- 点单端下单：没有记过对方局域网地址时，**先走中转**（本地 5 秒直连等待就没必要了），
  中转失败再退回局域网直连；订单带同一个 id，接单端按 id 去重，多试一条通路不会记成两单。

## 4. 验证顺序（建议按这个顺序排查）

1. 电脑上先验证端口通不通（TLS 握手成功才算通）：
   ```bash
   openssl s_client -connect <部署域名>:8883 -servername <部署域名> -brief
   ```
   看到证书链和 `CONNECTION ESTABLISHED` 就说明 TLS 这一层没问题。
2. 再用 mosquitto 客户端验证账号密码：
   ```bash
   mosquitto_sub -h <部署域名> -p 8883 --cafile /etc/ssl/certs/ca-certificates.crt \
     -u xiaowo -P '<密码>' -t 'xiaowo/WX8888/msg' -v
   mosquitto_pub -h <部署域名> -p 8883 --cafile /etc/ssl/certs/ca-certificates.crt \
     -u xiaowo -P '<密码>' -t 'xiaowo/WX8888/msg' -m hello -q 1
   ```
   订阅端能收到 `hello` 就说明认证、ACL、端口全对。
3. 最后装 App：接单端安装 Release 里的 APK，点单端在「我 → 检查更新」升级；
   两边「连接方式」里都应显示云端地址，点单端直接点单即可（不需要配对）。

## 5. 常见错误

| 现象 | 原因 | 处理 |
|---|---|---|
| 连上就断 / `错误码 -5` | SNI 与域名不符，或用了 IP 连接 | 用部署域名，别用 IP；确认端口 8883 |
| `Connection refused` / 超时 | 用了 1883 / 8083 | Serverless 不支持明文端口 |
| CONNACK 返回非 0（`0x04/0x05`） | 用户名或密码错，或没建认证 | 控制台「访问控制 → 认证」确认，密码别带多余空格 |
| 订阅成功但收不到消息 | topic 不一致 / ACL 拦住 | 两端 topic 必须是 `xiaowo/<同一个房间码>/msg` |
| 白天好、夜里断 | 手机系统冻结后台（国内 ROM 常见） | 「设置 → 电池 → 允许后台活动」，见 `docs/keep-alive.md` |

## 6. 成本

- 免费额度：100 万 session 分钟/月 + 1 GB 流量/月 + 100 万 rule actions/月，不用信用卡。
- 我们的用量：≈ 8.6 万分钟/月（8.6%）、消息量每天几百条，**正常用不会产生费用**。
- 超出后按 $2.00/百万分钟、$0.15/GB 计费（真超了也是几毛钱级别）。

## 7. 和"自建 mosquitto"的关系

预置云端不等于锁死。想换成自己的服务器（阿里云 / Oracle / 家里 Tailscale）：

「连接方式 → 异地中转 → 自建服务器」填 host / port / 用户名 / 密码 / TLS 即可，
填过之后就不再使用预置值。自建手册见 `docs/self-host-relay.md`。
