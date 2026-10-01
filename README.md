# 工业设备遥测与点位读写网关（本地模拟版）

一个基于 Java 21 + Spring Boot 3.4 的**进程内**工业设备遥测网关。用模拟通道替代真实
PLC / 现场总线，把以下链路完整跑通并做到**并发与异常下结果可解释、可复现**：

点位读写（含并发仲裁） → 周期采集 / 批量一致性快照 → 底层通道异常归一化 → 设备断连快速失败。

---

## 1. 模块结构

| 层 | 类 | 职责 |
|---|---|---|
| 模型 | `model/*` | `PointDefinition`、`Sample`、`Snapshot`、`WriteRequest/Outcome`、`BatchWriteResult` |
| 错误 | `error/*` | `ErrorCategory`（归一化分类）、`GatewayException` |
| 通道 | `channel/SimulatedChannel` 及 `*Exception` | 寄存器/线圈原始读写、地址校验、延迟/超时、关闭态、故障注入 |
| 设备 | `device/SimulatedDevice` | 点位类型编解码、乐观锁版本、读写锁、一致性快照、异常归一化 |
| 网关 | `gateway/TelemetryGateway`、`PeriodicCollector` | 多设备门面、定时一致性快照采集 |
| 装配 | `config/TelemetryGatewayConfig` | 两台默认设备 `plc-line-1/2` 及点位 |
| Web | `web/*` | REST 接口与全局异常映射 |

通道层的原始异常（`SimulatedChannelTimeoutException`、`InvalidChannelAddressException`、
`ValueOutOfBoundsException`、`ChannelClosedException`）**只允许出现在通道层内部**，
设备层捕获后统一归一化为 `GatewayException(category, message, cause)`，原始异常仅作为
`cause` 保留用于排查，不会作为顶层异常或类名透出给客户端。

---

## 2. 并发与一致性策略

### 2.1 单点写入：乐观锁版本（CAS）+ 写锁串行化

- 每个点位维护单调 `version`，初始为 0；设备维护单调全局序号 `commitSeq`。
- 写请求携带 `expectedVersion`：
  - 与当前版本一致 → 接受，写入后该点 `version+1`、`commitSeq+1`；
  - 不一致 → **明确拒绝**（返回 `accepted=false` 及当前实际版本/值），**绝不静默覆盖**；
  - `expectedVersion` 缺省 → 无条件写（仍串行提交）。
- 并发结论（确定且可复现）：
  - 多个客户端基于**同一旧版本**并发写同一点位：在设备写锁下串行 CAS，
    **恰好一个成功，其余全部过期拒绝**，最终值就是唯一获胜者写入的值，最终版本为 1。
  - 无条件并发写：全部成功，版本连续 `1..N`，无重复、无丢失。
- 校验顺序固定：点位存在 → 设备在线（快速失败）→ 版本 CAS（过期直接拒绝，不触通道）
  → 值编码/越界 → 通道写 → 提交版本。

### 2.2 一致性快照：设备级读写锁

- 设备持有一把 `ReentrantReadWriteLock`：所有读（单点/批量）共享读锁，所有写独占写锁。
- **批量读取 `readSnapshot()` 在同一次持有读锁期间逐个采样全部点位**，与任何写互斥，
  因而一帧里所有点位必然来自同一个采样时刻、同一个 `commitSeq`，不会“一半新一半旧”。
- 对业务上必须一致的多个点位，提供**原子批量写 `writeBatch`**：先统一做版本预检
  （任一点位过期则整批拒绝、不触碰通道、状态不变），全部通过后写入并一次性提交，
  各点版本同时推进、`commitSeq` 只 +1。快照永远观察不到这批点位的中间态。
- 设备提供锁内 `CommitListener`（提交后、释放锁前回调），供观察者与设备状态严格同步，
  避免“锁外事后登记”造成的观测竞态。

> 注：对**相互独立**的点位分别写，快照在两次独立提交之间采到“A 新 B 旧”是**合法且一致**
> 的状态（它精确等于 `commitSeq=k` 时刻）。要保证多点同变，请使用原子批量写。

### 2.3 周期采集

`PeriodicCollector` 用调度线程按固定间隔调用 `readSnapshot()`，产出轮次单调
（从 1 起）的一致性帧；设备断连时读取快速失败，异常走错误回调，调度线程不被阻塞，
重连后自动恢复出帧。

### 2.4 快速失败

设备断连（`disconnect()` 或检测到底层通道关闭）后，任何读/写/快照在触碰通道**之前**
立即抛 `DEVICE_DISCONNECTED`（实测微秒级返回），不会等待通道超时。

---

## 3. 错误分类

对外只暴露 `ErrorCategory`，HTTP 状态码映射如下：

| ErrorCategory | 含义 | HTTP |
|---|---|---|
| `DEVICE_NOT_FOUND` | 设备不存在 | 404 |
| `INVALID_ADDRESS` | 点位/寄存器地址非法 | 404 |
| `STALE_VERSION` | 基于过期版本的写入被乐观锁拒绝（REST 单点写返回 409，正文含仲裁结果） | 409 |
| `VALUE_OUT_OF_RANGE` | 值越界 / 类型不可表示（UINT16、INT32、FLOAT32、BOOL 各自校验） | 400 |
| `IO_TIMEOUT` | 底层读/写超时 | 503 |
| `DEVICE_DISCONNECTED` | 设备断连 / 通道关闭 | 503 |
| `INTERNAL_ERROR` | 未预期错误（统一收敛，不回传堆栈/原始类名） | 500 |

错误响应体形如：

```json
{ "category": "DEVICE_DISCONNECTED", "message": "device plc-line-1 is disconnected", "details": [] }
```

---

## 4. REST 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET  | `/api/devices` | 设备列表 |
| GET  | `/api/devices/{id}/points` | 一致性批量快照 |
| GET  | `/api/devices/{id}/points/{name}` | 单点读取 |
| POST | `/api/devices/{id}/points/{name}/writes` | 带版本仲裁的写入 |
| POST | `/api/devices/{id}/points/batch-writes` | 原子批量写 |
| POST | `/api/devices/{id}/connect` / `disconnect` | 设备生命周期 |
| POST | `/api/collector/start?deviceId=&intervalMillis=` | 启动周期采集 |
| POST | `/api/collector/stop?deviceId=` | 停止采集 |
| GET  | `/api/collector/frames?deviceId=` | 最近 64 帧快照 |

写入示例：

```bash
curl -s localhost:8080/api/devices/plc-line-1/points/motor-speed-rpm/writes \
  -H 'Content-Type: application/json' \
  -d '{"value":1800,"expectedVersion":0}'
# 接受：200 {"accepted":true,"committedVersion":1,...}
# 过期：409 {"accepted":false,"actualVersion":1,"currentValue":1800,
#           "reason":"stale version: expected 0 but actual 1"}
```

---

## 5. 验证方法

```bash
mvn test
```

共 21 个测试，每个用例都在日志中打印**输入**与**判定依据**，覆盖：

| 测试类 | 场景 |
|---|---|
| `ConcurrentWriteArbitrationTest` | 20 线程同版本 CAS 恰好一胜、32 线程无条件写版本连续 1..N |
| `StaleWriteRejectionTest` | 过期版本被拒且不改状态；版本链；非法地址 vs 越界值分类区分 |
| `BatchSnapshotConsistencyTest` | 多点快照自洽；2 秒内 1.5 万+ 帧、6 万+ 原子批次并发，配对点位零撕裂 |
| `DisconnectAndExceptionClassificationTest` | 断连微秒级快速失败、重连恢复、超时/非法地址/越界/通道关闭归一化、原始异常不外泄 |
| `PeriodicCollectorTest` | 周期帧轮次单调、断连走错误回调不阻塞、重连恢复 |
| `HttpApiIntegrationTest` | REST 层 409/404/400/503 分类与原子批量写端点 |

查看某一类场景的详细输入与判定：

```bash
mvn test -Dtest=BatchSnapshotConsistencyTest
# 或查看 target/surefire-reports/*.txt
```

### 手动演示断连快速失败

```bash
mvn spring-boot:run
curl -s -X POST localhost:8080/api/devices/plc-line-1/disconnect
curl -i localhost:8080/api/devices/plc-line-1/points     # 503 DEVICE_DISCONNECTED
curl -s -X POST localhost:8080/api/devices/plc-line-1/connect
curl -s localhost:8080/api/devices/plc-line-1/points     # 恢复
```
