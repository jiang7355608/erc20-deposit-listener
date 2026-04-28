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
import java.util.List;

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
     */
    public void checkPendingRecordsForReorg(int limit) {
        try {
            List<DepositRecord> pendingRecords = depositMapper.findPendingRecords(limit);

            for (DepositRecord record : pendingRecords) {
                boolean isReorg = detectReorg(record.getBlockNumber(), record.getBlockHash());

                if (isReorg) {
                    // 获取新的区块哈希
                    EthBlock.Block newBlock = web3j.ethGetBlockByNumber(
                            DefaultBlockParameter.valueOf(BigInteger.valueOf(record.getBlockNumber())),
                            false
                    ).send().getBlock();

                    String newBlockHash = newBlock != null ? newBlock.getHash() : "unknown";

                    handleReorg(
                            record.getContractAddress(),
                            record.getBlockNumber(),
                            record.getBlockHash(),
                            newBlockHash
                    );
                }
            }

        } catch (Exception e) {
            log.error("检查待确认记录重组失败", e);
        }
    }
}
