# 工业设备遥测与点位读写网关

基于 Spring Boot 3 / Java 21 的本地遥测网关。用进程内模拟设备
（`SimulatedDeviceChannel`）替代真实 PLC / 现场总线，跑通
**点位读写 → 并发写入仲裁 → 周期采集 → 批量一致性快照 → 通道异常归一化**
的完整链路。

## 结构

```
channel/   DeviceChannel 接口、ChannelException 体系、SimulatedDeviceChannel（模拟设备）
core/      PointValue（值+版本+采样时刻）、PointStore（版本化存储与一致性快照）
service/   TelemetryGatewayService（读写/仲裁/归一化/快速失败）、AcquisitionScheduler（周期采集）
web/       TelemetryController（REST）、ApiErrorHandler（统一错误响应）
```

## 并发与一致性策略

### 写入仲裁（确定性最终状态）

- 每个点位携带单调递增的版本号；写入必须提供 `expectedVersion`。
- `PointStore.compareAndSet` 在原子块内校验版本：匹配则写入并把版本 +1，
  不匹配则拒绝并抛出 `STALE_VERSION`，**绝不静默覆盖**。
- 版本号在确认写入成功后才分配，因此每次成功写入版本恰好前进 1，
  最终状态由仲裁顺序唯一确定，可复现。
- 多个客户端以同一版本并发写同一点位时，恰好一个成功，其余全部被明确拒绝。

### 批量读取 / 周期采集的一致性快照

- 批量读取与写入、采集合并之间用**公平读写锁**互斥：快照在读锁保护下
  一次性读取，返回的点位组合必然在某一时刻真实共存过，
  不会出现"一半新值一半旧值"；公平策略保证持续写入下读取者不饿死。
- 一轮周期采集在**读取设备之前**分配采集版本与采样时刻，
  同轮所有点位共享同一 `sampledAt` 与版本；合并时每个点位保留较大版本，
  保证并发的客户端写入不会被旧采样覆盖。

## 错误分类

底层 `ChannelException`（Timeout / InvalidAddress / ValueOutOfRange / Closed）
一律归一化为携带错误码的 `GatewayException`，底层异常类型与原文不透出：

| 错误码 | 含义 | HTTP 状态 |
|---|---|---|
| `CHANNEL_TIMEOUT` | 通道读写超时 | 504 |
| `INVALID_ADDRESS` | 点位地址非法 | 400 |
| `VALUE_OUT_OF_RANGE` | 写入值越界 | 400 |
| `DEVICE_UNAVAILABLE` | 通道关闭 / 设备断连 | 503 |
| `STALE_VERSION` | 基于过期版本的写入被拒 | 409 |
| `POINT_NOT_FOUND` | 点位尚未采集 | 404 |

设备断连（通道关闭）时，读、写、批量读都在进入任何 IO 之前
立即抛出 `DEVICE_UNAVAILABLE`，快速失败、不重试不等待；
断连期间周期采集自动跳过，重连后恢复。

## REST 接口

```
GET  /api/points/{address}                      读单点
PUT  /api/points/{address}  {"value":1.0,"expectedVersion":3}  仲裁写入
GET  /api/points?addresses=1,2,3                批量一致性快照
```

错误响应体：`{"code":"STALE_VERSION","message":"..."}`。

## 验证方法

```bash
mvn test
```

| 测试类 | 覆盖场景 |
|---|---|
| `ConcurrentWriteArbitrationTest` | 同版本并发写恰好一人成功；read-modify-write 下无丢失更新、版本逐次 +1 |
| `StaleWriteRejectedTest` | 过期/未来版本写入被拒且不覆盖；过期、非法地址、越界三类错误码可区分 |
| `BatchSnapshotConsistencyTest` | 写入者扫掠 64 点位时 30 万+ 次批量快照零撕裂（阶梯形不变量）；单轮采集共享同一采样时刻 |
| `DisconnectAndErrorClassificationTest` | 超时/非法地址/越界/关闭的归一化分类；断连快速失败（毫秒级）；断连期间采集跳过、重连恢复 |

每个测试的日志都会打印**输入**与**判定依据**（期望值与实际值），
例如 `判定依据: 成功数=1(应=1), 过期拒绝数=15(应=15), ...`，便于核对结论。

运行服务：`mvn spring-boot:run`，采集间隔由
`gateway.acquisition.interval-ms`（默认 1000ms）控制。
