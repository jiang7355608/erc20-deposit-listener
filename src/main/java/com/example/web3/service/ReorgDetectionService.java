package com.example.web3.service;

import com.example.web3.entity.DepositRecord;
import com.example.web3.entity.ReorgHistory;
import com.example.web3.mapper.DepositMapper;
import com.example.web3.mapper.ReorgHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.response.EthBlock;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 链重组检测服务
 * @author jiangyuxuan
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReorgDetectionService {

    private final Web3j web3j;
    private final DepositMapper depositMapper;
    private final ReorgHistoryMapper reorgHistoryMapper;

    /**
     * 检测指定区块是否发生重组
     * @param blockNumber 区块号
     * @param expectedBlockHash 期望的区块哈希
     * @return true=发生重组, false=未发生重组
     */
    public boolean detectReorg(Long blockNumber, String expectedBlockHash) {
        try {
            // 从链上获取当前该区块号的哈希
            EthBlock.Block currentBlock = web3j.ethGetBlockByNumber(
                    DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber)),
                    false
            ).send().getBlock();

            if (currentBlock == null) {
                log.warn("无法获取区块 {}, 可能链还未同步到该高度", blockNumber);
                return false;
            }

            String currentBlockHash = currentBlock.getHash();

            // 比较哈希
            if (!currentBlockHash.equalsIgnoreCase(expectedBlockHash)) {
                log.warn("检测到链重组! 区块号={}, 期望哈希={}, 当前哈希={}",
                        blockNumber, expectedBlockHash, currentBlockHash);
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("检测重组失败: blockNumber={}", blockNumber, e);
            return false;
        }
    }

    /**
     * 处理链重组
     * @param contractAddress 合约地址
     * @param blockNumber 发生重组的区块号
     * @param oldBlockHash 旧的区块哈希
     * @param newBlockHash 新的区块哈希
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleReorg(String contractAddress, Long blockNumber, 
                           String oldBlockHash, String newBlockHash) {
        try {
            log.warn("开始处理链重组: contract={}, block={}, oldHash={}, newHash={}",
                    contractAddress, blockNumber, oldBlockHash, newBlockHash);

            // 1. 查询该区块的所有交易记录
            List<DepositRecord> affectedRecords = depositMapper.findByBlockNumber(blockNumber);

            // 2. 标记这些记录为重组状态
            if (!affectedRecords.isEmpty()) {
                int count = depositMapper.markAsReorg(blockNumber, LocalDateTime.now());
                log.warn("已标记 {} 条记录为重组状态", count);
            }

            // 3. 记录重组历史
            ReorgHistory history = ReorgHistory.builder()
                    .contractAddress(contractAddress)
                    .oldBlockNumber(blockNumber)
                    .oldBlockHash(oldBlockHash)
                    .newBlockHash(newBlockHash)
                    .affectedTxCount(affectedRecords.size())
                    .detectedAt(LocalDateTime.now())
                    .build();

            reorgHistoryMapper.insert(history);

            log.warn("链重组处理完成: 受影响交易数={}", affectedRecords.size());

        } catch (Exception e) {
            log.error("处理链重组失败: blockNumber={}", blockNumber, e);
            throw e;
        }
    }

    /**
     * 检查待确认记录是否发生重组
     * 使用多线程并发检查，提高效率
     */
    public void checkPendingRecordsForReorg(int limit) {
        try {
            // 1. 查询所有待确认记录
            List<DepositRecord> pendingRecords = depositMapper.findPendingRecords(limit);
            
            if (pendingRecords.isEmpty()) {
                log.debug("没有待确认记录需要检查");
                return;
            }
            
            log.info("开始检查 {} 条待确认记录", pendingRecords.size());
            
            // 2. 按区块号分组（同一区块只检查一次）
            Map<Long, List<DepositRecord>> recordsByBlock = pendingRecords.stream()
                    .collect(Collectors.groupingBy(DepositRecord::getBlockNumber));
            
            // 3. 按区块号排序
            List<Long> sortedBlockNumbers = recordsByBlock.keySet().stream()
                    .sorted()
                    .collect(Collectors.toList());
            
            log.info("共 {} 个区块需要检查", sortedBlockNumbers.size());
            
            // 4. 使用线程池并发检查
            ExecutorService executor = Executors.newFixedThreadPool(
                    Math.min(10, sortedBlockNumbers.size())  // 最多10个线程
            );
            
            AtomicInteger reorgBlockCount = new AtomicInteger(0);
            AtomicInteger consecutiveReorgCount = new AtomicInteger(0);
            AtomicLong lastReorgBlock = new AtomicLong(-1);
            AtomicBoolean deepReorgDetected = new AtomicBoolean(false);
            
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            // 5. 提交所有检查任务（不提前停止）
            for (Long blockNumber : sortedBlockNumbers) {
                List<DepositRecord> blockRecords = recordsByBlock.get(blockNumber);
                
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        // 检查该区块
                        checkBlockForReorg(blockNumber, blockRecords, 
                                reorgBlockCount, consecutiveReorgCount, 
                                lastReorgBlock, deepReorgDetected);
                        
                    } catch (Exception e) {
                        log.error("检查区块 {} 失败", blockNumber, e);
                    }
                }, executor);
                
                futures.add(future);
            }
            
            // 6. 等待所有任务完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // 7. 关闭线程池
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            // 8. 输出结果
            int finalReorgCount = reorgBlockCount.get();
            if (deepReorgDetected.get()) {
                log.error("检测到深度重组！共 {} 个区块发生重组", finalReorgCount);
                log.error("建议人工介入检查，系统将在下次定时任务（30秒后）重新评估");
            } else if (finalReorgCount > 0) {
                log.warn("重组检查完成: 共检查 {} 个区块，发现 {} 个区块发生重组", 
                        sortedBlockNumbers.size(), finalReorgCount);
            } else {
                log.info("重组检查完成: 共检查 {} 个区块，未发现重组", 
                        sortedBlockNumbers.size());
            }

        } catch (Exception e) {
            log.error("检查待确认记录重组失败", e);
        }
    }
    
    /**
     * 检查单个区块是否重组
     */
    private void checkBlockForReorg(Long blockNumber, 
                                    List<DepositRecord> blockRecords,
                                    AtomicInteger reorgBlockCount,
                                    AtomicInteger consecutiveReorgCount,
                                    AtomicLong lastReorgBlock,
                                    AtomicBoolean deepReorgDetected) {
        try {
            // 取第一条记录的区块哈希（同一区块的所有记录哈希相同）
            String expectedBlockHash = blockRecords.get(0).getBlockHash();
            
            // 检测该区块是否重组
            boolean isReorg = detectReorg(blockNumber, expectedBlockHash);
            
            if (isReorg) {
                // 获取新的区块哈希
                EthBlock.Block newBlock = web3j.ethGetBlockByNumber(
                        DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber)),
                        false
                ).send().getBlock();
                
                String newBlockHash = newBlock != null ? newBlock.getHash() : "unknown";
                String contractAddress = blockRecords.get(0).getContractAddress();
                
                // 处理重组（会标记该区块的所有记录）
                handleReorg(contractAddress, blockNumber, expectedBlockHash, newBlockHash);
                
                reorgBlockCount.incrementAndGet();
                log.warn("区块 {} 发生重组，已标记 {} 条记录", blockNumber, blockRecords.size());
                
                // 检查是否连续重组（需要同步）
                synchronized (this) {
                    long last = lastReorgBlock.get();
                    if (last != -1 && blockNumber == last + 1) {
                        int consecutive = consecutiveReorgCount.incrementAndGet();
                        
                        // 检测到深度重组（连续3个或以上区块）
                        if (consecutive >= 3 && !deepReorgDetected.get()) {
                            deepReorgDetected.set(true);
                            log.error("⚠️ 检测到深度重组！连续 {} 个区块发生重组（区块 {} 到 {}）", 
                                    consecutive, 
                                    blockNumber - consecutive + 1, 
                                    blockNumber);
                            log.error("这可能是 51% 攻击或严重的网络分叉");
                            log.error("已提交的检查任务会继续执行，但建议人工介入检查");
                        }
                    } else {
                        consecutiveReorgCount.set(1);
                    }
                    lastReorgBlock.set(blockNumber);
                }
            } else {
                // 没有重组，重置连续计数
                synchronized (this) {
                    consecutiveReorgCount.set(0);
                }
            }
            
        } catch (Exception e) {
            log.error("检查区块 {} 重组失败", blockNumber, e);
        }
    }
}
