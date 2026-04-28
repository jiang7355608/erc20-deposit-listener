# Web3 项目改进文档

## 改进概述

本次改进主要解决了两个关键问题：
1. **系统宕机重启后的数据丢失问题**
2. **链重组（Reorg）的检测和处理**

---

## 一、系统宕机重启问题

### 原有问题

```yaml
# 原配置
web3j:
  token:
    start-block: latest  # ❌ 问题：每次重启都从最新区块开始
```

**后果**：
- 系统宕机期间的所有交易会丢失
- 无法追溯历史数据
- 不适合生产环境

### 解决方案：断点续传机制

#### 1. 新增检查点表

```sql
CREATE TABLE block_checkpoint (
    id SERIAL PRIMARY KEY,
    contract_address VARCHAR(42) NOT NULL,
    last_processed_block BIGINT NOT NULL,
    last_processed_block_hash VARCHAR(66) NOT NULL,
    last_processed_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_contract_address UNIQUE (contract_address)
);
```

#### 2. 工作原理

```
启动流程：
1. 查询 block_checkpoint 表
2. 如果有检查点 → 从 last_processed_block + 1 开始
3. 如果无检查点 → 从配置的 start-block 开始
4. 每处理一个区块 → 更新检查点

宕机恢复：
系统重启 → 自动从上次处理的区块继续 → 不丢失任何交易
```

#### 3. 核心代码

```java
// ImprovedTokenTransferListener.java
private void startListeningWithResume() {
    BlockCheckpoint checkpoint = checkpointService.getCheckpoint(contractAddress);
    
    BigInteger startBlock;
    if (checkpoint != null) {
        // 从检查点恢复
        startBlock = BigInteger.valueOf(checkpoint.getLastProcessedBlock() + 1);
        log.info("从检查点恢复: 上次处理区块={}", checkpoint.getLastProcessedBlock());
    } else {
        // 首次启动
        startBlock = getConfiguredStartBlock();
    }
    
    // 创建过滤器并开始监听
    EthFilter filter = new EthFilter(
        DefaultBlockParameter.valueOf(startBlock),
        DefaultBlockParameterName.LATEST,
        contractAddress
    );
    // ...
}
```

---

## 二、链重组（Reorg）问题

### 什么是链重组？

以太坊是去中心化网络，可能出现临时分叉：

```
正常情况：
Block 100 → Block 101 → Block 102 → Block 103

发生重组：
Block 100 → Block 101 → Block 102 (被废弃)
          ↘ Block 101' → Block 102' (新的有效链)
```

**影响**：
- Block 102 中的交易可能被回滚
- 已保存的充值记录可能无效
- 用户余额可能不准确

### 原有问题

```java
// 原代码只是简单等待
Thread.sleep(confirmations * 20 * 1000L);  // ❌ 不检测重组
```

**问题**：
- 只是等待时间，不验证区块哈希
- 无法检测已保存记录是否被重组
- 没有回滚机制

### 解决方案：重组检测和处理

#### 1. 状态管理

新增记录状态：
```sql
ALTER TABLE deposit_record 
ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED';

-- 状态流转：
-- PENDING → CONFIRMED (正常确认)
-- PENDING/CONFIRMED → REORG (检测到重组)
```

#### 2. 重组检测逻辑

```java
// ReorgDetectionService.java
public boolean detectReorg(Long blockNumber, String expectedBlockHash) {
    // 从链上获取当前该区块号的哈希
    EthBlock.Block currentBlock = web3j.ethGetBlockByNumber(
        DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber)),
        false
    ).send().getBlock();
    
    String currentBlockHash = currentBlock.getHash();
    
    // 比较哈希
    if (!currentBlockHash.equalsIgnoreCase(expectedBlockHash)) {
        log.warn("检测到链重组! 区块号={}, 期望哈希={}, 当前哈希={}",
                blockNumber, expectedBlockHash, currentBlockHash);
        return true;
    }
    
    return false;
}
```

#### 3. 重组处理流程

```
定时任务（每30秒）：
1. 查询所有 PENDING 状态的记录
2. 对每条记录检查区块哈希是否匹配
3. 如果不匹配 → 标记为 REORG
4. 记录重组历史到 reorg_history 表
5. 可以触发告警通知运维人员
```

#### 4. 重组历史表

```sql
CREATE TABLE reorg_history (
    id BIGSERIAL PRIMARY KEY,
    contract_address VARCHAR(42) NOT NULL,
    old_block_number BIGINT NOT NULL,
    old_block_hash VARCHAR(66) NOT NULL,
    new_block_hash VARCHAR(66) NOT NULL,
    affected_tx_count INTEGER NOT NULL,
    detected_at TIMESTAMP NOT NULL
);
```

---

## 三、改进后的工作流程

### 完整流程图

```
系统启动
    ↓
查询检查点 (block_checkpoint)
    ↓
从断点恢复监听
    ↓
接收到 Transfer 事件
    ↓
检查确认数
    ├─ 不足 → 保存为 PENDING
    └─ 足够 → 保存为 CONFIRMED + 更新检查点
    ↓
定时任务（每30秒）
    ├─ 检查 PENDING 记录的确认数
    ├─ 检测是否发生重组
    ├─ 更新状态：PENDING → CONFIRMED
    └─ 标记重组：* → REORG
```

### 状态转换

```
新交易
  ↓
PENDING (确认数不足)
  ↓
  ├─ 正常情况 → CONFIRMED (达到确认数)
  └─ 异常情况 → REORG (检测到重组)
```

---

## 四、新增 API 接口

### 1. 系统状态监控

```bash
GET /api/monitor/status?contractAddress=0x...

响应：
{
  "code": 200,
  "message": "success",
  "data": {
    "latestBlockOnChain": 5234567,      # 链上最新区块
    "lastProcessedBlock": 5234560,      # 已处理到的区块
    "lastProcessedBlockHash": "0x...",  # 已处理区块的哈希
    "blockLag": 7,                      # 落后区块数
    "pendingRecordsCount": 3            # 待确认记录数
  }
}
```

### 2. 重组历史查询

```bash
GET /api/monitor/reorg-history?limit=10

响应：
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "contractAddress": "0x...",
      "oldBlockNumber": 5234500,
      "oldBlockHash": "0xabc...",
      "newBlockHash": "0xdef...",
      "affectedTxCount": 2,
      "detectedAt": "2024-11-05T10:30:00"
    }
  ]
}
```

### 3. 待确认记录查询

```bash
GET /api/monitor/pending-records?limit=50

响应：
{
  "code": 200,
  "data": [
    {
      "txHash": "0x...",
      "blockNumber": 5234565,
      "status": "PENDING",
      "amount": "100.5",
      "createdAt": "2024-11-05T10:35:00"
    }
  ]
}
```

---

## 五、配置建议

### 确认数设置

```yaml
web3j:
  token:
    confirmations: 12  # 建议值
```

**推荐配置**：
- 测试网（Sepolia/Goerli）：3-6 个确认
- 主网（Mainnet）：12-32 个确认
- 高价值交易：64+ 个确认

**原理**：
- 1 个确认 ≈ 12 秒（以太坊平均出块时间）
- 12 个确认 ≈ 2.4 分钟
- 重组通常发生在前几个区块

---

## 六、测试场景

### 1. 断点续传测试

```bash
# 步骤：
1. 启动系统，监听几个区块
2. 查看数据库：SELECT * FROM block_checkpoint;
3. 强制停止系统（模拟宕机）
4. 等待几分钟（链上产生新区块）
5. 重启系统
6. 观察日志：应该从检查点恢复，不丢失交易

# 预期日志：
✓ 从检查点恢复: 上次处理区块=5234560, 从区块 5234561 开始监听
```

### 2. 重组检测测试

```bash
# 步骤：
1. 监听测试网交易
2. 查看 PENDING 状态的记录
3. 等待定时任务执行（30秒）
4. 观察状态变化：PENDING → CONFIRMED

# 模拟重组（需要测试环境）：
1. 在私有链上制造分叉
2. 观察系统是否检测到重组
3. 查看 reorg_history 表
```

---

## 七、生产环境建议

### 1. 监控告警

```java
// 建议添加告警
if (isReorg) {
    // 发送告警：钉钉/邮件/短信
    alertService.sendReorgAlert(blockNumber, oldHash, newHash);
}
```

### 2. 数据库索引

```sql
-- 已添加的索引
CREATE INDEX idx_deposit_status ON deposit_record(status);
CREATE INDEX idx_deposit_block_number ON deposit_record(block_number);
CREATE INDEX idx_deposit_confirmed_at ON deposit_record(confirmed_at);
```

### 3. 定时任务优化

```yaml
# application.yml
monitoring:
  pending-check-interval: 30000  # 30秒
  reorg-check-depth: 100         # 检查最近100个区块
```

### 4. 日志级别

```yaml
logging:
  level:
    com.example.web3.listener: INFO
    com.example.web3.service.ReorgDetectionService: WARN
```

---

## 八、面试亮点

### 可以强调的技术点

1. **断点续传机制**
   - "实现了基于数据库检查点的断点续传，确保系统重启后不丢失任何交易"
   - "使用 PostgreSQL 的 UPSERT 语法保证检查点更新的原子性"

2. **链重组处理**
   - "深入理解以太坊的共识机制和链重组原理"
   - "实现了主动检测 + 被动验证的双重保障机制"
   - "通过区块哈希比对检测重组，而不是简单等待时间"

3. **状态机设计**
   - "设计了 PENDING → CONFIRMED → REORG 的状态流转"
   - "使用定时任务异步处理状态更新，降低主流程延迟"

4. **幂等性保证**
   - "通过数据库唯一索引 (tx_hash, log_index) 保证幂等性"
   - "支持多实例部署，避免重复处理"

5. **可观测性**
   - "提供监控 API 实时查看系统状态和重组历史"
   - "记录详细的重组历史，便于问题排查"

---

## 九、进一步优化方向

1. **性能优化**
   - 批量处理历史区块
   - 使用 WebSocket 替代轮询
   - Redis 缓存热点数据

2. **高可用**
   - 多实例部署 + 分布式锁
   - 消息队列解耦
   - 熔断降级机制

3. **安全性**
   - API 认证授权
   - 限流防刷
   - 敏感信息加密

4. **可扩展性**
   - 支持多合约监听
   - 插件化事件处理
   - 动态配置更新

---

## 总结

本次改进解决了 Web3 项目中两个最核心的问题：

✅ **断点续传**：系统重启不丢数据  
✅ **重组检测**：保证数据准确性  
✅ **状态管理**：清晰的状态流转  
✅ **可观测性**：完善的监控接口  

这些改进让项目从"玩具项目"升级为"生产级项目"，非常适合写进简历！
