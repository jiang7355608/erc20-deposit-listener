package com.example.web3.service;

import com.example.web3.entity.DepositRecord;
import com.example.web3.mapper.DepositMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 充值服务
 * @author jiangyuxuan
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepositService {

    private final DepositMapper depositMapper;
    private final Web3j web3j;

    /**
     * 保存充值记录（利用数据库唯一索引保证幂等性）
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveDepositRecord(DepositRecord record) {
        try {
            int rows = depositMapper.insert(record);
            if (rows > 0) {
                log.info("保存成功 [{}] - txHash: {}, logIndex: {}, from: {}, to: {}, amount: {}",
                        record.getStatus(),
                        record.getTxHash(), 
                        record.getLogIndex(),
                        record.getFromAddress(), 
                        record.getToAddress(), 
                        record.getAmountDecimal());
            }
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 唯一索引冲突，说明已经存在（多实例并发插入或重启重复处理）
            log.info("记录已存在（幂等性保护），跳过: tx={}, logIndex={}", 
                    record.getTxHash(), record.getLogIndex());
        } catch (Exception e) {
            log.error("保存失败: tx={}, logIndex={}", 
                    record.getTxHash(), record.getLogIndex(), e);
            throw e;
        }
    }

    /**
     * 更新待确认记录的状态
     * 检查确认数是否足够，如果足够则更新为 CONFIRMED
     */
    @Transactional(rollbackFor = Exception.class)
    public void updatePendingRecordsStatus() {
        try {
            List<DepositRecord> pendingRecords = depositMapper.findPendingRecords(100);
            
            if (pendingRecords.isEmpty()) {
                return;
            }

            BigInteger latestBlockNumber = web3j.ethBlockNumber().send().getBlockNumber();
            int updatedCount = 0;

            for (DepositRecord record : pendingRecords) {
                BigInteger confirmations = latestBlockNumber.subtract(
                        BigInteger.valueOf(record.getBlockNumber())
                );

                // 假设需要 3 个确认（可以从配置读取）
                if (confirmations.compareTo(BigInteger.valueOf(3)) >= 0) {
                    depositMapper.updateStatus(
                            record.getId(),
                            DepositRecord.Status.CONFIRMED.name(),
                            LocalDateTime.now()
                    );
                    updatedCount++;
                    
                    log.info("记录已确认: tx={}, confirmations={}", 
                            record.getTxHash(), confirmations);
                }
            }

            if (updatedCount > 0) {
                log.info("已更新 {} 条记录为 CONFIRMED 状态", updatedCount);
            }

        } catch (Exception e) {
            log.error("更新待确认记录状态失败", e);
        }
    }

    /**
     * 根据地址查询充值记录
     */
    public List<DepositRecord> getDepositsByAddress(String address, int limit) {
        return depositMapper.findByToAddress(address, 0, limit);
    }

    /**
     * 查询最近的充值记录
     */
    public List<DepositRecord> getRecentDeposits(int limit) {
        return depositMapper.findAll(0, limit);
    }

    /**
     * 根据交易hash查询
     */
    public DepositRecord getDepositByTxHash(String txHash) {
        return depositMapper.findByTxHash(txHash);
    }
}

