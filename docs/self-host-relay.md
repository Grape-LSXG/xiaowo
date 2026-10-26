# 异地中转：为什么老断，以及自建服务器怎么搭

> 结论先说：**云服务器有用，但不是万能药。**
> 这次在代码里挖出两个硬伤（`Mqtt.java` 从不发心跳、订单"发出即算送达"），
> 它们和服务器无关，自建后照样会翻车。v4.7 已经把这两处修了（见文末）。
> 服务器解决的是另外三件事：公共 broker 在国内不稳、没有鉴权、明文可被人偷看。

---

## 一、现在为什么会"不稳定"

### 1. 长连接其实只能活 60~90 秒（v4.7 已修）

`Mqtt.connectPacket()` 里声明了 `keepalive = 60`，意思是"我保证 60 秒内至少给你一个包"，
broker（mosquitto/emqx/hivemq 都一样）会在 **1.5 倍 keepalive、也就是 90 秒**收不到任何包时
主动断开这个连接。

而我们这边：

- `pingPacket()` 是写了的，但**从来没有被调用过**；
- 读循环里 `setSoTimeout(90000)`，也就是说"只有当 90 秒读不到东西"才反应过来断了；

于是每条"长连接"的真实生命周期是：

```
CONNECT → SUBACK（online=true） → …… 安静 90 秒 …… → broker 断开 → 我们读到 EOF/超时
→ 退避 2 秒重连 → 再安静 90 秒 → 再断……
```

`clean session = 1` + `QoS 0` 意味着**断开期间的任何消息都会被直接丢掉**，
broker 不会替我们留。那 2 秒（再加上 TCP 握手、DNS，实际 2~4 秒）的空窗里，
对方发来的订单/聊天/爱心就是"发了但没人收到"。

这就是"我明明在线，对方却说你不在线"最直白的技术解释：
**在线的不是人，是一条被 broker 掐断后正在重连的 socket。**

修法（已改）：空闲 35 秒主动发 `PINGREQ`，连续两个窗口没有 `PINGRESP` 才判定断线；
退避上限从 30 秒收到 8 秒；网络切换（Wi-Fi ↔ 流量）时立刻重连。

### 2. 走中转的订单"发出即成功"，没有回执（v4.7 已修）

`OrderScreen` 里走 MQTT 下完单，`publishOnce()` 只要 TCP 写成功就返回 `true`，
界面立刻说"已经送到张老师那儿啦 ❤"。但 QoS0 的语义是"最多一次"，
broker 收下了不代表对方收到了 —— 对方正好在重连空窗里，这单就没了。

修法（已改）：接单端收到中转订单后回一条 `orderAck`，点单端等 15 秒：
收到就记"已送达"，等不到就老实提示"可能没连上，再点一次"。
不再假装成功。

### 3. 公共 broker 本身的三个问题（服务器能解决）

| 问题 | 表现 |
|---|---|
| 国内连通性差 | `broker.hivemq.com` / `test.mosquitto.org` 经常连不上、丢包、被限速；`broker.emqx.io` 时好时坏 |
| 没有鉴权、topic 全公开 | 任何人 `mosquitto_sub -t 'xiaowo/#'` 就能看到**所有情侣的**订单和聊天（房间码只有 6 位） |
| 没有 QoS1 / 会话保持 | 断了就丢，没有"离线补发"这回事（协议层 v4.8 已修，见 3.5） |

### 3.5 QoS1 与会话保持：v4.8 把协议层换成了 QoS1

之前是"手写的、只到 QoS0"的客户端：订阅 QoS0、发布 QoS0、`clean session = 1`、
发布不等确认。这三点合起来就是"断线窗口里必丢消息"。

v4.8 全部改了（`src/com/couple/xiaowo/core/Mqtt.java`）：

| 项目 | 以前 | 现在 |
|---|---|---|
| 订阅 | `SUBSCRIBE` QoS0，`clean session=1` | `SUBSCRIBE` **QoS1**，`clean session=0` + **固定 clientId**（`xiaowo-s-<本机安装编号>`），broker 会替我们攒断线期间的 QoS1 消息 |
| 发布 | QoS0，写进 socket 就 `return true` | **QoS1**，必须收到 `PUBACK` 才返回成功；`publishOnce()` 的返回值现在是真的 |
| 收消息 | 只处理 QoS0 的 PUBLISH | 按首字节算 QoS，QoS1 先回 `PUBACK` 再交给业务层；订阅阶段也会边等 `SUBACK` 边处理补发过来的离线消息 |
| 重投 | —— | 订单按 `order.id` 去重（`RecvScreen.onOrderArrived`），聊天按 `ts+文本` 去重（两端 `onChatIncoming`） |

自建服务器上配合两行配置（第 4 节的 `xiaowo.conf` 里已写）：

```
persistent_client_expiration 2h   # 会话别无限堆在 broker 上
max_keepalive 120
```

> 仍然**没做**的：本地离线队列（手机完全没网时点单，只能靠"回执超时提示"兜底）。
> 要做的话得在客户端落盘待发队列，属于下一步。


自己一台 mosquitto：国内延迟个位数毫秒、可以开账号密码 + 按房间 ACL、可以上 TLS、
连接数不受别人影响。

### 4. 还有一类"看起来像连接问题"的：手机把 App 冻了

vivo/一加把后台 App 冻结后，MQTT 长连接必然死。这部分靠 `KeepAlive`/保活页解决，
服务器帮不上忙，也不该指望它。

---

## 二、买之前先算一笔账

| 方案 | 年成本 | 稳定性 | 隐私 | 运维 |
|---|---|---|---|---|
| 继续用公共 broker | 0 | 差（见上） | 无（明文公开） | 无 |
| **自建 mosquitto（阿里云 68 元/年）** | ~68 | 好 | 好（鉴权+ACL，可上 TLS） | 装一次，之后基本不碰 |
| 免费云主机（Oracle 等，见 docs/free-hosting.md） | 0 | 取决于线路 | 好 | 注册可能过不了 |
| 家里 IPv6 + DDNS | 0 | 一般（家宽会换 IP、会重启） | 好 | 要折腾 |

68 元/年 ≈ 每月 5.7 元，对"每天都要点单、被抱怨过"这件事来说很便宜。
**但注意促销价的续费价**：轻量应用服务器首年 68 的，第二年通常恢复原价（100+）。
买之前看清"续费价格"，或者干脆当一次性实验。

---

## 三、阿里云（Ubuntu 24.04 / 2核2G / 200Mbps）部署手册

### 0. 买机要点

- 地域：选离**你们俩都近**的地方（比如两人在上海/杭州 → 华东1 杭州；一南一北 → 华中或就近取舍）。
  MQTT 对延迟不敏感，但握手快一点体感更好。
- 镜像：Ubuntu 24.04 LTS。
- 200Mbps 是**峰值带宽**，实际按流量计费/有月流量包；我们的消息每月只有几 MB，完全够。
- 轻量应用服务器和 ECS 的区别只影响控制台位置，命令一样。

### 1. 放行端口（两处都要改，最常见的坑）

1. 阿里云控制台 → 服务器 → 防火墙/安全组：放行 `TCP 1883`（如果后面上 TLS 再放 `8883`）。
2. 进系统后 `ufw` 也要放行，否则控制台开了也没用：

```bash
sudo apt update && sudo apt install -y ufw
sudo ufw allow 22/tcp        # 先放 SSH，别把自己关在外面
sudo ufw allow 1883/tcp
sudo ufw enable
sudo ufw status
```

> 如果所在网络封 1883（少数公司/校园网会），可以把监听口换成 `443`：
> 客户端和服务器两边都填 443 即可，协议还是 MQTT，只是借用了 443 端口号。
> 这一步不用改代码，`listener 443` + 客户端端口填 443 就行。

### 2. 装 mosquitto

```bash
sudo apt install -y mosquitto mosquitto-clients
sudo systemctl enable --now mosquitto
systemctl status mosquitto --no-pager
```

Ubuntu 24.04 带的是 mosquitto 2.0.x。**2.0 起默认只监听本机**，
所以必须显式写 `listener`，否则外网永远连不上（这个坑很常见）。

### 3. 账号密码

用一个共用账号，名字就用**房间码**，这样后面 ACL 能一句话锁死房间：

```bash
# 假设房间码是 ABC123（App 里生成的 6 位码）
sudo mosquitto_passwd -c /etc/mosquitto/passwd ABC123
# 回车后输两遍密码（建议 16 位以上随机串，记在手机备忘录里）
sudo chown mosquitto:mosquitto /etc/mosquitto/passwd
sudo chmod 640 /etc/mosquitto/passwd
```

### 4. 主配置

```bash
sudo tee /etc/mosquitto/conf.d/xiaowo.conf >/dev/null <<'EOF'
# 监听所有网卡的 1883
listener 1883 0.0.0.0

# 必须登录
allow_anonymous false
password_file /etc/mosquitto/passwd

# 每个账号只能读写 "自己的房间"：%u 会被替换成登录用户名
# 用户名就是房间码，因此 ABC123 只能碰 xiaowo/ABC123/msg
acl_file /etc/mosquitto/acl

# 我们自己的包都很小，但公共网关上要防止有人发大包打内存
message_size_limit 1048576
max_keepalive 120
persistent_client_expiration 2h

log_dest file /var/log/mosquitto/mosquitto.log
log_type error
log_type warning
log_type notice
EOF

sudo tee /etc/mosquitto/acl >/dev/null <<'EOF'
# 只允许访问自己房间的主题
pattern readwrite xiaowo/%u/msg
EOF

sudo systemctl restart mosquitto
```

> 说明：App 的 topic 是 `xiaowo/<房间码>/msg`（见 `Mqtt.topic()`）。
> `pattern` 里的 `%u` 是登录用户名 —— 用户名设成房间码，这条规则就把每个账号
> 锁在自己房间里了。哪两台设备用同一个账号，它们就能互通。

### 5. 自检（务必先在本机跑通）

```bash
# 1) 看端口有没有起来
ss -lntp | grep -E '1883|mosquitto'

# 2) 本机收发（两个终端）
#    终端 A：
mosquitto_sub -h 127.0.0.1 -p 1883 -u ABC123 -P '你的密码' -t 'xiaowo/ABC123/msg' -v
#    终端 B：
mosquitto_pub -h 127.0.0.1 -p 1883 -u ABC123 -P '你的密码' -t 'xiaowo/ABC123/msg' -m 'hello'

# 3) 外网可达性（在这台服务器之外跑，比如手机开热点用笔记本）
mosquitto_sub -h 你的公网IP -p 1883 -u ABC123 -P '你的密码' -t 'xiaowo/ABC123/msg' -v

# 4) 没有密码应该被拒绝（安全自检）
mosquitto_pub -h 你的公网IP -p 1883 -t 'xiaowo/ABC123/msg' -m 'x'   # 期望 Connection Refused
```

第 2 步收到 `hello` 就说明 broker 本身没问题；第 3 步收不到，99% 是
「控制台安全组没放行」或「ufw 没放行」，不是 mosquitto 的问题。

### 6. App 侧怎么填

两端都进「我 → 连接设置 → 异地中转 → 自建服务器」（v4.7 新增的按钮）：

| 字段 | 填什么 |
|---|---|
| 服务器地址 | 你的公网 IP（如 `47.98.1.2`）；有域名就填域名 |
| 端口 | `1883`（或你改的 443） |
| 用户名 | **房间码**（如 `ABC123`，和 App 里配对的房间码一致） |
| 密码 | 第 3 步设的那个密码 |
| TLS | 先关；上证书后再开 |

保存后会自动重连，卡片上那行会变成「服务器：47.98.1.2:1883」。
两台都填完，进屋看「中转状态：在线 · 房间 ABC123」就成了。

> 房间码和用户名必须是**同一个值**，否则 ACL 会把你挡在自己的房间外面。

### 7. 可选：上 TLS（需要域名）

```bash
# 域名 A 记录指向服务器 IP，然后：
sudo apt install -y certbot
sudo certbot certonly --standalone -d mq.example.com

sudo tee -a /etc/mosquitto/conf.d/xiaowo.conf >/dev/null <<'EOF'

listener 8883
cafile   /etc/letsencrypt/live/mq.example.com/chain.pem
certfile /etc/letsencrypt/live/mq.example.com/fullchain.pem
keyfile  /etc/letsencrypt/live/mq.example.com/privkey.pem
tls_version tlsv1.2
EOF

sudo systemctl restart mosquitto
sudo ufw allow 8883/tcp
```

客户端把地址填域名、端口填 `8883`、TLS 开关打开。
证书是 Let's Encrypt 的，Android 系统信任库里有，**不需要在手机上装证书**。
（自签证书则必须先装到手机，否则 App 会拒绝 —— 这是有意的，跳过校验等于没加密。）

上完 TLS 后建议把明文 1883 关掉，只留 8883。

### 8. 日常维护

```bash
systemctl status mosquitto          # 活着没
tail -f /var/log/mosquitto/mosquitto.log
mosquitto_sub -h 127.0.0.1 -u ABC123 -P '密码' -t 'xiaowo/#' -v   # 在线偷看两条消息（调试用）
```

- 内存占用 < 30 MB，2 核 2G 属于大幅富余。
- 服务器重启后 mosquitto 自动起（`enable` 过了）。
- 公网 IP 固定（云服务器不像家宽会变），所以填一次就不用再改。

### 9. 安全底线

- 密码别用房间码（房间码只有 6 位，等于公开）。
- 别开 `allow_anonymous true`。
- 端口只开放用到的（1883 或 8883），SSH 建议改成密钥登录。
- 这套配置只给两个人用，不需要防 DDoS；但要防"有人拿你的 broker 当免费中转刷流量"——
  强密码 + ACL 就够。

---

## 四、v4.7 的代码改动（本次一起推上去）

| 文件 | 改动 |
|---|---|
| `core/Mqtt.java` | **发 PINGREQ**（空闲 35s），连续两次无响应判死；退避上限 30s→8s；`configure()` 支持自建 host/port/账号/密码/TLS；CONNECT 包带上 username/password；`open()` 走 TLS 时开 hostname 校验 |
| `core/Relay.java` | `publish()` 前 `Mqtt.configure(c)`，自建时不碰公共 broker |
| `ui/MainActivity.java` | `startRelay()` 前 configure；注册 `NetworkCallback`，**换网立刻重连**；`onDestroy` 反注册 |
| `core/Protocol.java` | 新增 `TYPE_ORDER_ACK` + `orderAck()` 回执 |
| `ui/OrderScreen.java` | 走中转下单改为"等回执"：15s 没回执就提示可能没送到；`handleOrderAck()` 更新历史为已送达；不再把 `relay` 写进 `peer_host` |
| `ui/RecvScreen.java` | 收到中转订单后，后台线程回 `orderAck` |
| `ui/ConnectPage.java` | 「自建服务器」设置面板（地址/端口/用户名/密码/TLS） |

多端在线人数那套（`docs/multi-order.md`）继续有效；修好心跳之后，
「📱 点单端：N 个在线」也不会再因为连接每 90 秒断一次而闪来闪去。
