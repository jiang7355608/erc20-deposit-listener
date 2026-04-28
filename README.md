# ERC20 Token 充值监听系统

> 基于 Spring Boot + MyBatis + Web3j 实现的生产级链上充值事件监听和记录系统

[![GitHub](https://img.shields.io/badge/GitHub-jiang7355608-blue)](https://github.com/jiang7355608/erc20-deposit-demo)
[![Java](https://img.shields.io/badge/Java-11-orange)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen)](https://spring.io/projects/spring-boot)
[![Web3j](https://img.shields.io/badge/Web3j-4.9.8-blue)](https://docs.web3j.io/)

---

## 📋 目录

- [项目简介](#项目简介)
- [核心特性](#核心特性)
- [技术架构](#技术架构)
- [系统流程](#系统流程)
- [快速开始](#快速开始)
- [API 文档](#api-文档)
- [数据库设计](#数据库设计)
- [配置说明](#配置说明)
- [测试指南](#测试指南)
- [生产部署](#生产部署)
- [常见问题](#常见问题)

---

## 项目简介

这是一个企业级的 ERC20 代币充值监听系统，用于实时监听以太坊链上的 Transfer 事件，并将充值记录持久化到数据库。

### 适用场景

- 交易所充值监听
- DeFi 协议事件追踪
- 链上数据分析
- 钱包服务后端

### 项目亮点

✅ **断点续传**：系统宕机重启后自动从上次处理的区块继续，不丢失任何交易  
✅ **链重组检测**：主动检测并处理以太坊链重组，保证数据准确性  
✅ **状态管理**：完善的 PENDING → CONFIRMED → REORG 状态流转机制  
✅ **幂等性保证**：通过数据库唯一索引防止重复处理  
✅ **可观测性**：提供监控 API 实时查看系统状态和重组历史  
✅ **生产就绪**：支持多实例部署、异常重试、优雅关闭

---

## 核心特性

### 1. 断点续传机制

系统通过 `block_checkpoint` 表记录已处理的区块高度和哈希：

```
启动时：
  ├─ 有检查点 → 从 last_processed_block + 1 继续
  └─ 无检查点 → 从配置的 start-block 开始

处理事件：
  └─ 每处理一个确认的区块 → 更新检查点

宕机恢复：
  └─ 自动从检查点恢复 → 不丢失任何交易
```

### 2. 链重组检测

以太坊网络可能发生临时分叉导致链重组，系统通过以下机制保证数据准确性：

```
主动检测：
  └─ 定时任务每 30 秒检查待确认记录的区块哈希

被动验证：
  └─ 启动时验证检查点区块是否被重组

发现重组：
  ├─ 标记受影响的记录为 REORG 状态
  ├─ 记录重组历史到 reorg_history 表
  └─ 可触发告警通知运维人员
```

### 3. 状态管理


充值记录的状态流转：

```
新交易事件
    ↓
PENDING (确认数不足)
    ↓
    ├─ 正常情况 → CONFIRMED (达到要求的确认数)
    └─ 异常情况 → REORG (检测到链重组)
```

---

## 技术架构

### 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 11 | 编程语言 |
| Spring Boot | 2.7.18 | 应用框架 |
| MyBatis | 2.3.1 | ORM 框架 |
| Web3j | 4.9.8 | 以太坊 Java 客户端 |
| PostgreSQL | 14+ | 关系型数据库 |
| Lombok | - | 简化代码 |


### 核心组件

#### 1. ImprovedTokenTransferListener
- 监听 ERC20 Transfer 事件
- 实现断点续传机制
- 管理事件订阅生命周期

#### 2. ReorgDetectionService
- 检测链重组
- 处理重组事件
- 标记受影响的记录

#### 3. BlockCheckpointService
- 管理区块检查点
- 支持断点续传
- 保证处理连续性

#### 4. DepositService
- 保存充值记录
- 更新记录状态
- 保证幂等性

---

## 系统流程

### 完整工作流程

#### 阶段一：系统启动与初始化

1. **系统启动**
   - Spring Boot 应用启动，ImprovedTokenTransferListener 作为 CommandLineRunner 自动执行

2. **初始化检查**
   - 测试 RPC 连接：调用 `web3j.web3ClientVersion()` 验证节点连接
   - 获取当前区块高度：调用 `web3j.ethBlockNumber()` 确认链同步状态
   - 获取 Token decimals：调用合约的 `decimals()` 方法，用于后续金额计算

3. **断点恢复机制**
   - 查询 `block_checkpoint` 表，获取上次处理的区块信息
   - 如果存在检查点：
     - 从 `last_processed_block + 1` 开始监听
     - 验证检查点区块哈希是否匹配（检测是否被重组）
     - 如果检查点被重组，回退 10 个区块重新处理
   - 如果不存在检查点（首次启动）：
     - 从配置文件的 `start-block` 开始（可配置为 latest 或具体区块号）

4. **创建事件过滤器**
   - 起始区块：根据上一步确定的 startBlock
   - 结束区块：LATEST（持续监听最新区块）
   - 合约地址：配置的 ERC20 合约地址
   - 事件签名：Transfer(address indexed from, address indexed to, uint256 value)

5. **订阅事件流**
   - 使用 Web3j 的 `ethLogFlowable` 订阅事件
   - 设置错误处理和重连机制

#### 阶段二：事件接收与处理

6. **接收 Transfer 事件**
   - 当链上发生 Transfer 事件时，监听器收到事件日志
   - 事件日志包含：
     - topics[0]: 事件签名哈希
     - topics[1]: from 地址（indexed 参数）
     - topics[2]: to 地址（indexed 参数）
     - data: 转账金额（非 indexed 参数）

7. **解析事件数据**
   - 解码 from 地址：从 topics[1] 提取后 40 位十六进制字符
   - 解码 to 地址：从 topics[2] 提取后 40 位十六进制字符
   - 解析金额：将 data 从十六进制转为 BigInteger
   - 计算实际金额：`amount_decimal = value / 10^decimals`

8. **获取区块信息**
   - 调用 `ethGetBlockByNumber` 获取区块详情
   - 提取区块号、区块哈希、时间戳等信息

9. **检查区块确认数**
   - 获取当前最新区块高度：`latestBlock`
   - 计算确认数：`confirmations = latestBlock - eventBlock`
   - 判断状态：
     - 如果 `confirmations >= requiredConfirmations`（配置值，如 12）→ 状态为 CONFIRMED
     - 否则 → 状态为 PENDING

10. **保存充值记录**
    - 构建 DepositRecord 对象，包含所有解析的数据和状态
    - 插入 `deposit_record` 表
    - 幂等性保证：通过唯一索引 `(tx_hash, log_index)` 防止重复插入
    - 如果重复插入（DuplicateKeyException），记录日志并跳过

11. **更新检查点**
    - 仅当记录状态为 CONFIRMED 时更新检查点
    - 使用 PostgreSQL 的 UPSERT 语法更新 `block_checkpoint` 表
    - 记录 `last_processed_block` 和 `last_processed_block_hash`

#### 阶段三：后台定时任务

12. **定时任务执行**（每 30 秒触发一次）
    
    **任务 A：检查待确认记录**
    - 查询所有 `status = 'PENDING'` 的记录（限制 100 条）
    - 对每条记录：
      - 获取当前最新区块高度
      - 重新计算确认数
      - 如果确认数已达到要求，更新状态为 CONFIRMED，记录 `confirmed_at` 时间

    **任务 B：链重组检测**
    - 对每条 PENDING 记录：
      - 从链上获取该区块号当前的区块哈希
      - 与数据库中保存的 `block_hash` 比较
      - 如果哈希不匹配 → 检测到链重组：
        - 标记该区块的所有记录为 REORG 状态
        - 设置 `is_reorg = true`，记录 `reorg_detected_at` 时间
        - 插入重组历史到 `reorg_history` 表
        - 记录受影响的交易数量
        - 可选：触发告警通知运维人员

#### 阶段四：异常场景处理

13. **系统宕机恢复**
    - 系统重启后，自动从检查点恢复
    - 不会丢失宕机期间的任何交易
    - 示例：
      - 宕机前处理到区块 5234560
      - 宕机期间产生了区块 5234561-5234600
      - 重启后从区块 5234561 开始处理，补齐所有遗漏的交易

14. **链重组处理**
    - 检测到重组后，受影响的记录被标记为 REORG
    - 这些记录不会被业务系统使用（可通过 `status != 'REORG'` 过滤）
    - 重组后的新交易会被重新监听和保存
    - 保留重组历史便于审计和问题排查

15. **网络异常重连**
    - 如果 RPC 连接断开，监听器会捕获异常
    - 实现指数退避重试机制
    - 重连成功后继续从检查点恢复

### 关键流程详解

#### 流程 1: 系统启动与断点恢复

```java
// 伪代码
void startListeningWithResume() {
    // 1. 查询检查点
    BlockCheckpoint checkpoint = checkpointService.getCheckpoint(contractAddress);
    
    BigInteger startBlock;
    if (checkpoint != null) {
        // 2. 从检查点恢复
        startBlock = checkpoint.getLastProcessedBlock() + 1;
        
        // 3. 验证检查点区块是否被重组
        boolean isReorg = reorgDetectionService.detectReorg(
            checkpoint.getLastProcessedBlock(),
            checkpoint.getLastProcessedBlockHash()
        );
        
        if (isReorg) {
            // 4. 如果检查点被重组，回退几个区块重新处理
            startBlock = checkpoint.getLastProcessedBlock() - 10;
            log.warn("检查点区块被重组，从区块 {} 重新开始", startBlock);
        }
    } else {
        // 5. 首次启动，从配置读取
        startBlock = getConfiguredStartBlock();
    }
    
    // 6. 创建过滤器并订阅
    EthFilter filter = new EthFilter(startBlock, LATEST, contractAddress);
    subscription = web3j.ethLogFlowable(filter).subscribe(...);
}
```

#### 流程 2: 事件处理与状态判断

```java
// 伪代码
void handleTransferEvent(Log eventLog) {
    // 1. 解析事件
    String from = decodeAddress(eventLog.getTopics().get(1));
    String to = decodeAddress(eventLog.getTopics().get(2));
    BigInteger value = new BigInteger(eventLog.getData().substring(2), 16);
    
    // 2. 检查确认数
    BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();
    BigInteger confirmations = latestBlock.subtract(eventLog.getBlockNumber());
    
    // 3. 确定状态
    String status;
    if (confirmations.compareTo(BigInteger.valueOf(requiredConfirmations)) >= 0) {
        status = "CONFIRMED";
    } else {
        status = "PENDING";
    }
    
    // 4. 构建记录
    DepositRecord record = DepositRecord.builder()
        .txHash(eventLog.getTransactionHash())
        .blockNumber(eventLog.getBlockNumber().longValue())
        .blockHash(eventLog.getBlockHash())
        .fromAddress(from)
        .toAddress(to)
        .amount(value.toString())
        .status(status)
        .build();
    
    // 5. 保存记录（幂等性由数据库唯一索引保证）
    depositService.saveDepositRecord(record);
    
    // 6. 更新检查点（仅 CONFIRMED 状态）
    if ("CONFIRMED".equals(status)) {
        checkpointService.saveCheckpoint(
            eventLog.getAddress(),
            eventLog.getBlockNumber().longValue(),
            eventLog.getBlockHash()
        );
    }
}
```

#### 流程 3: 链重组检测

```java
// 伪代码
@Scheduled(fixedDelay = 30000)  // 每 30 秒执行
void checkPendingRecords() {
    // 1. 查询所有待确认记录
    List<DepositRecord> pendingRecords = depositMapper.findPendingRecords(100);
    
    for (DepositRecord record : pendingRecords) {
        // 2. 从链上获取当前该区块的哈希
        EthBlock.Block currentBlock = web3j.ethGetBlockByNumber(
            record.getBlockNumber()
        ).send().getBlock();
        
        String currentHash = currentBlock.getHash();
        String savedHash = record.getBlockHash();
        
        // 3. 比较哈希
        if (!currentHash.equals(savedHash)) {
            // 4. 检测到重组
            log.warn("检测到重组: block={}, oldHash={}, newHash={}",
                record.getBlockNumber(), savedHash, currentHash);
            
            // 5. 标记记录为 REORG
            depositMapper.markAsReorg(record.getBlockNumber());
            
            // 6. 记录重组历史
            ReorgHistory history = ReorgHistory.builder()
                .oldBlockNumber(record.getBlockNumber())
                .oldBlockHash(savedHash)
                .newBlockHash(currentHash)
                .affectedTxCount(1)
                .build();
            reorgHistoryMapper.insert(history);
            
            // 7. 可选：发送告警
            alertService.sendReorgAlert(record);
        } else {
            // 8. 未重组，检查确认数
            BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();
            BigInteger confirmations = latestBlock.subtract(
                BigInteger.valueOf(record.getBlockNumber())
            );
            
            // 9. 确认数足够，更新状态
            if (confirmations.compareTo(BigInteger.valueOf(3)) >= 0) {
                depositMapper.updateStatus(
                    record.getId(),
                    "CONFIRMED",
                    LocalDateTime.now()
                );
            }
        }
    }
}
```

---



#### 1. 断点续传实现原理

```java
// 启动时的逻辑
BlockCheckpoint checkpoint = checkpointService.getCheckpoint(contractAddress);

if (checkpoint != null) {
    // 场景：系统重启恢复
    startBlock = checkpoint.getLastProcessedBlock() + 1;
    
    // 验证检查点是否被重组
    boolean isReorg = reorgDetectionService.detectReorg(
        checkpoint.getLastProcessedBlock(),
        checkpoint.getLastProcessedBlockHash()
    );
    
    if (isReorg) {
        // 检查点被重组，回退处理
        startBlock = checkpoint.getLastProcessedBlock() - 10;
    }
} else {
    // 场景：首次启动
    startBlock = getConfiguredStartBlock();
}
```

**为什么需要验证检查点？**
- 系统宕机期间，检查点记录的区块可能被重组
- 如果不验证，会从一个已经无效的区块继续，导致数据不一致
- 验证后发现重组，回退几个区块重新处理，保证数据准确性

#### 2. 链重组检测原理

```java
// 定时任务中的检测逻辑
List<DepositRecord> pendingRecords = depositMapper.findPendingRecords(100);

for (DepositRecord record : pendingRecords) {
    // 从链上获取该区块当前的哈希
    EthBlock.Block currentBlock = web3j.ethGetBlockByNumber(
        record.getBlockNumber()
    ).send().getBlock();
    
    String currentHash = currentBlock.getHash();
    String savedHash = record.getBlockHash();
    
    // 比较哈希
    if (!currentHash.equals(savedHash)) {
        // 哈希不匹配 = 发生重组
        handleReorg(record);
    }
}
```

**为什么比较区块哈希？**
- 区块哈希是区块内容的唯一标识
- 如果区块被重组，新的区块会有不同的哈希
- 通过哈希比对可以准确检测重组

**为什么只检查 PENDING 记录？**
- PENDING 记录确认数不足，更容易被重组
- CONFIRMED 记录已经有足够确认数，重组概率极低
- 优化性能，避免检查所有历史记录

#### 3. 幂等性保证机制

```sql
-- 数据库唯一索引
CREATE UNIQUE INDEX uk_tx_hash_log_index ON deposit_record(tx_hash, log_index);
```

**为什么需要幂等性？**
- 系统重启可能重复处理相同的事件
- 多实例部署可能并发处理相同的事件
- 网络异常可能导致重试

**如何保证幂等性？**
- 使用 `(tx_hash, log_index)` 作为唯一标识
- 一笔交易可能产生多个 Transfer 事件（log_index 区分）
- 重复插入时数据库抛出 DuplicateKeyException，捕获后忽略

#### 4. 状态流转逻辑

```
新事件到达
    ↓
检查确认数
    ↓
    ├─ confirmations < 12 → 保存为 PENDING
    └─ confirmations >= 12 → 保存为 CONFIRMED
    
定时任务
    ↓
检查 PENDING 记录
    ↓
    ├─ 确认数足够 → 更新为 CONFIRMED
    ├─ 检测到重组 → 更新为 REORG
    └─ 确认数不足 → 保持 PENDING
```

**为什么需要 PENDING 状态？**
- 新事件可能确认数不足，不能立即确认
- 通过定时任务异步更新状态，不阻塞主流程
- 业务系统可以只查询 CONFIRMED 状态的记录

---

## 快速开始

### 前置要求

- JDK 11+
- PostgreSQL 14+
- Maven 3.6+
- MetaMask 钱包
- Infura 或 Alchemy 账号（获取 RPC URL）

### 1. 准备测试环境

#### 1.1 获取测试 ETH

访问 Sepolia 测试网水龙头领取测试 ETH：
- https://faucet.metana.io/
- https://sepoliafaucet.com/

#### 1.2 部署 ERC20 合约

使用 Remix IDE 部署测试合约：
1. 访问 https://remix.ethereum.org/
2. 创建新文件，复制项目根目录的 `MyToken.sol`
3. 编译合约
4. 连接 MetaMask（选择 Sepolia 网络）
5. 部署合约，记录合约地址

#### 1.3 导入代币到 MetaMask

1. 在 MetaMask 中切换到 Sepolia 网络
2. 点击"导入代币"
3. 输入合约地址
4. 确认导入

### 2. 配置数据库

```bash
# 创建数据库
psql -U postgres
CREATE DATABASE web3_demo;

# 执行 DDL
psql -U postgres -d web3_demo -f ddl.sql
psql -U postgres -d web3_demo -f ddl_upgrade.sql
```

### 3. 配置应用

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/web3_demo
    username: your_username
    password: your_password

web3j:
  rpc-url: https://sepolia.infura.io/v3/YOUR_INFURA_KEY
  chain-id: 11155111
  
  token:
    contract-address: "YOUR_CONTRACT_ADDRESS"
    contract-name: "MyToken"
    start-block: latest  # 或指定区块号
    confirmations: 3     # 测试网建议 3-6
```

### 4. 启动应用

```bash
# 编译
mvn clean package

# 运行
java -jar target/erc20-deposit-demo-1.0.0.jar

# 或使用 Maven
mvn spring-boot:run
```

### 5. 测试转账

1. 在 MetaMask 中创建第二个账户
2. 从账户 A 向账户 B 转账代币
3. 观察应用日志，应该能看到事件被监听到
4. 查询数据库验证记录已保存

```sql
-- 查询充值记录
SELECT * FROM deposit_record ORDER BY block_number DESC LIMIT 10;

-- 查询检查点
SELECT * FROM block_checkpoint;

-- 查询待确认记录
SELECT * FROM deposit_record WHERE status = 'PENDING';
```

---


## 常见问题

### Q1: 系统重启后会丢失数据吗？

**A:** 不会。系统通过 `block_checkpoint` 表记录已处理的区块，重启后会自动从检查点恢复，补齐宕机期间的所有交易。

### Q2: 如何处理链重组？

**A:** 系统会定时检测链重组，将受影响的记录标记为 REORG 状态。业务系统查询时过滤掉 REORG 记录即可。重组后的新交易会被重新监听和保存。

### Q3: 为什么有些记录是 PENDING 状态？

**A:** PENDING 表示确认数不足。系统会通过定时任务自动更新状态，当确认数达到要求时会变为 CONFIRMED。

### Q4: 可以多实例部署吗？

**A:** 可以。系统通过数据库唯一索引保证幂等性，多实例并发处理相同事件时不会产生重复记录。但建议使用分布式锁优化性能。

### Q5: RPC 节点断开怎么办？

**A:** 系统实现了自动重连机制，会进行指数退避重试。重连成功后继续从检查点恢复。

### Q6: 如何回溯历史数据？

**A:** 修改配置文件的 `start-block` 为目标起始区块号，删除 `block_checkpoint` 表的记录，重启系统即可。

### Q7: 确认数应该设置多少？

**A:** 
- 测试网：3-6 个确认（约 36-72 秒）
- 主网普通交易：12 个确认（约 2.4 分钟）
- 主网高价值交易：32-64 个确认（约 6-13 分钟）

---

## 项目亮点总结

### 技术亮点

1. **断点续传机制**
   - 基于数据库检查点实现
   - 系统重启不丢失任何交易
   - 支持从任意区块恢复

2. **链重组检测**
   - 主动检测 + 被动验证双重保障
   - 通过区块哈希比对准确识别重组
   - 完整的重组历史记录

3. **状态机设计**
   - PENDING → CONFIRMED → REORG 清晰的状态流转
   - 异步状态更新不阻塞主流程
   - 支持业务系统灵活查询

4. **幂等性保证**
   - 数据库唯一索引防止重复
   - 支持多实例部署
   - 系统重启和重试安全

5. **可观测性**
   - 完善的监控 API
   - 详细的日志记录
   - 重组历史可追溯

### 工程化能力

- 生产级代码质量
- 完善的异常处理
- 优雅的关闭机制
- 支持 Docker 部署
- 详细的文档说明

---

## 许可证

MIT License

---

## 作者

jiangyuxuan - [GitHub](https://github.com/jiang7355608)

---

## 参考资料

- [Web3j 官方文档](https://docs.web3j.io/)
- [以太坊开发文档](https://ethereum.org/en/developers/docs/)
- [ERC20 标准](https://eips.ethereum.org/EIPS/eip-20)
- [链重组原理](https://ethereum.org/en/developers/docs/consensus-mechanisms/pos/#fork-choice)
