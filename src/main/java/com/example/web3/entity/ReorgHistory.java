package com.example.web3.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 链重组历史记录
 * @author jiangyuxuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReorgHistory {
    
    private Long id;
    
    /**
     * 合约地址
     */
    private String contractAddress;
    
    /**
     * 发生重组的区块号
     */
    private Long oldBlockNumber;
    
    /**
     * 旧的区块哈希
     */
    private String oldBlockHash;
    
    /**
     * 新的区块哈希
     */
    private String newBlockHash;
    
    /**
     * 受影响的交易数量
     */
    private Integer affectedTxCount;
    
    /**
     * 检测时间
     */
    private LocalDateTime detectedAt;
    
    /**
     * 解决时间
     */
    private LocalDateTime resolvedAt;
}
