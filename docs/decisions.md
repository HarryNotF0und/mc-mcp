# 设计决策记录

## D1. 构建工具链：Fabric Loom 1.17.21 + Gradle 9.7.1（阶段 1）

**背景**：Fabric 官方 1.21.1 示例模组目前使用 Loom 1.18-SNAPSHOT，而 Loom 1.18 要求以 Java 25 运行 Gradle。PLAN.md 指定 Java 21，开发机也只装了 JDK 21。

**决定**：固定 `loom_version=1.17.21`（最后一个以 Java 21 运行的版本），插件 ID 使用经典的 `fabric-loom`（带重映射，支持 `loom.officialMojangMappings()`）。Gradle wrapper 沿用示例模组的 9.7.1。

**后果**：开发者只需 JDK 21。将来若升级到 Loom 1.18+，需要 JDK 25 运行 Gradle，模组本身仍可 `release = 21` 编译。

## D2. Java-WebSocket 以 `transitive = false` 引入（阶段 1）

Java-WebSocket 唯一的依赖是 `slf4j-api`，Minecraft 本身已经提供。只 `include` Java-WebSocket 本体，避免把第二份 slf4j 打进 jar。

## D3. `system.ping` 在网络线程响应，其余方法默认上主线程（阶段 1）

`RpcDispatcher` 注册处理器时显式区分 `onMainThread` / `onNetworkThread`。`ping` 不读取任何游戏状态，放在网络线程可以在服务器卡顿时仍然证明"连接是通的"；所有读写世界的方法一律走 `server.submit(...)`，满足"修改世界只在主线程"的约定，读取也一并上主线程以避免读到不一致的状态。

## D4. Bridge 采用按需连接 + 惰性重连（阶段 1）

Bridge 启动时不连接模组；每次工具调用发现没有可用连接时才连接并认证（连接超时默认 3 秒，`MCMCP_CONNECT_TIMEOUT_MS`）。连接断开时，所有在途请求立即以 `Disconnected` 失败，下一次调用自动重连。

**理由**：Claude Code 会在游戏启动前就拉起 bridge；单人游戏每次退出/进入世界都会关闭/重开 WebSocket。按需重连无需后台轮询，也让"游戏未启动"直接表现为一个清晰的工具错误而不是挂起。

**代价**：在没有工具调用时收不到推送。等阶段 2 引入任务进度推送时，若需要持续接收，再加后台重连循环。

## D5. 模组错误以 MCP 工具错误（`isError: true`）返回（阶段 1）

模组返回的 JSON-RPC 错误、连接失败、超时，都转换为文本形如 `error 1001: ...` 的工具结果并标记 `isError`，而不是 MCP 协议层错误。这样模型能看到错误码和信息并自行调整参数。
