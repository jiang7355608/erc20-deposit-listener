package com.example.web3.service;

import com.example.web3.entity.BlockCheckpoint;
import com.example.web3.mapper.BlockCheckpointMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 区块检查点服务（用于断点续传）
 * @author jiangyuxuan
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlockCheckpointService {

    private final BlockCheckpointMapper checkpointMapper;

    /**
     * 获取检查点
     */
    public BlockCheckpoint getCheckpoint(String contractAddress) {
        return checkpointMapper.findByContractAddress(contractAddress);
    }

    /**
     * 保存或更新检查点
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveCheckpoint(String contractAddress, Long blockNumber, String blockHash) {
        BlockCheckpoint checkpoint = BlockCheckpoint.builder()
                .contractAddress(contractAddress)
                .lastProcessedBlock(blockNumber)
                .lastProcessedBlockHash(blockHash)
                .lastProcessedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        try {
            int rows = checkpointMapper.upsert(checkpoint);
            if (rows > 0) {
                log.debug("检查点已更新: contract={}, block={}", contractAddress, blockNumber);
            }
        } catch (Exception e) {
            log.error("保存检查点失败: contract={}, block={}", contractAddress, blockNumber, e);
            throw e;
        }
    }
}
