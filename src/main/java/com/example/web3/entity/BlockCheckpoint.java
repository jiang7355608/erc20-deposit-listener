package com.example.web3.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 区块检查点实体（用于断点续传）
 * @author jiangyuxuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockCheckpoint {
    
    private Long id;
    
    /**
     * 合约地址
     */
    private String contractAddress;
    
    /**
     * 最后处理的区块号
     */
    private Long lastProcessedBlock;
    
    /**
     * 最后处理的区块哈希
     */
    private String lastProcessedBlockHash;
    
    /**
     * 最后处理时间
     */
    private LocalDateTime lastProcessedAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
