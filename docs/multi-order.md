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
