# 多台点单端同时在线（v4.6）

## 为什么要做

一个家里可能有两台手机装点单端（我 + 她），开发时也会在同一台手机上另装一个当调试端。
之前的问题有两个：

1. 接单端根本不知道"有谁连着" —— 只有下单那一刻能看见一台，不下单就永远是 0。
2. 接单端回消息只发给**最近一单**的回信地址，另一台点单端像被晾着。

## 每台点单端的身份：`core/Client.java`

```java
Client.id(ctx)     // 首次随机生成 4 位编号，写进 SharedPreferences(order_client_id)
Client.label(ctx)  // "猫猫(A3F2)"
```

字符集是 `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`（去掉了容易看错的 0/O/1/I）。
编号存在 SP 里，所以重装/清数据才会变。

## 心跳：`presence`

点单端每 **25 秒**发一次（启动后 6 秒先发一次）：

```
直连 peerHost/peerPort  POST /presence     2000ms，成功即止
否则局域网发现 Net.discover(1200) 逐个 POST /presence   1500ms
否则 Relay.publish(role=order, json)      6000ms（异地）
```

Payload 是 `Protocol.presence(id, name, host, port)`：

```json
{"type":"presence","role":"order","id":"A3F2","name":"猫猫(A3F2)",
 "host":"192.168.1.7","port":41234,"ts":1730000000000}
```

心跳失败**完全静默**：连不上就下一轮再说，不弹任何提示（不然异地没配对时每 25 秒骚扰一次）。

## 接单端：在线表

- `PATH_PRESENCE` 和经中转的 `type=presence` 都进 `touchClient(...)`；
  `onOrderArrived` 也会顺手 touch 一次（下单当然算在线）。
- `LinkedHashMap<String, PeerClient> orderClients`，`PeerClient{id,name,host,port,seen}`。
- **TTL 90 秒**：超过没心跳就 `pruneClients()` 摘掉（否则"在线"是假的）。
- 每 15 秒 `scheduleClientTick()` 刷一次人数行。
- 首页卡片显示：`📱 点单端：2 个在线　·　猫猫(A3F2)　·　猫猫(7KQP)`。
- 新设备接入时，若正停在厨房页，`Toast2.info` 轻提示「… 连上了」（调试时好确认）。

## 回复/推送：发给每一台

`RecvScreen.sendChatToCat()` 先遍历在线表，对每台 `host:port` 各 POST 一份
（失败再试 `127.0.0.1:port`，同机调试用）；全部失败才回到原来的
「保存的 peer 地址 → 最近一单 replyHost → 异地中转」。

注意这张表是**主线程**在增删、发消息在**网络线程**读，所以读写都套了
`synchronized (orderClients)`，发送前先拷一份快照，避免 `ConcurrentModificationException`。

## 为什么多台不会打架

| 冲突点 | 为什么没问题 |
|---|---|
| 监听端口 | 点单端的 HTTP 服务用 `server.start(0)`（系统分配随机端口），不是写死的 8765 |
| MQTT 客户端 | 每个 `Mqtt.Sub` / `publishOnce` 用随机 clientId，同 topic 多订阅者是 MQTT 的正常用法 |
| 中转消息回环 | `MainActivity.deliverRelay` 里 `w.from.equals(myRole)` 只过滤"自己这个角色发的"，不会把另一台点单端的心跳当自己的 |
| 订单 id | `"o" + System.currentTimeMillis()`，两台同一毫秒撞上的概率可忽略 |

## 调试建议

同机装两个点单端时，先看接单端首页那行人数是不是 2；
再在接单端「我」页发一句话，两台点单端应该都收到。
若只有一台收到，多半是那一台的心跳还没发过（等 25 秒），或者它被系统冻住了（看保活页）。

---

## 6. v4.11：真正"能选设备"了

之前的在线列表只是"看得见"，动作还是打在"最近下单的那台"上。现在每个动作都能点名：

| 场景 | 入口 | 只有一台时 | 多台时 |
| --- | --- | --- | --- |
| 聊天 | 「我」页聊天卡上方的「发给 全部 / A3F2 / B7C1」 | 不显示这一行 | 选中的那台才收到（消息带 `to`，其他设备直接忽略） |
| 看屏幕 | 厨房页「📸 看猫猫的屏幕」 | 直接发 | 弹出选单（含「🔊 全部设备」） |
| 震动 | 厨房页「轻震 / 中震 / 狠狠震」 | 直接发 | 弹出选单（含「🔊 全部设备」） |
| 设备菜单 | 首页设备行「操作」 | 直接弹出 | 同样可选 |

实现要点：

- 点名走 `Protocol.withTo(body, id)`（往 JSON 里插 `"to":<识别码>`），点单端在 `onRelayMessage` 开头比对
  `to` 与自己的 `Client.id()`，不是自己的直接丢掉 —— 这样中转是广播、但只有目标设备处理。
- 接单端 `pickTargetDevice(title, cb)`：0 台回调 `null`（维持原来的"没配对"提示），1 台不弹选单，多台才弹。
- 聊天目标存在字段 `chatTarget`（`RecvScreen`），发完气泡显示「我 → A3F2」。

## 7. 专业模式里的「运行日志」

`core/Logs.java` + `ui/LogPage.java`：环形缓冲 600 条 + `cacheDir/xiaowo.log`（超 256KB 自动截断）。
目前埋点的位置：

| tag | 什么时候写 |
| --- | --- |
| `app` | 启动（版本 + 角色） |
| `mqtt` | 订阅连上 / 断开（带 host 与房间） |
| `relay` | 每次 MQTT 发布的结果、耗时、模式 |
| `order` | 下单成功/失败（走哪条路、几项）、收到订单、收到回执 |
| `chat` | 收发消息 |
| `update` | 分块更新的进度结果与失败原因 |

设置页（两端的「我」/「管理」）在 `Ui.pro(act)` 为真时显示「🧾 运行日志」，点开可看最近 300 条、一键复制全文。
日志只留本机，不上传。

