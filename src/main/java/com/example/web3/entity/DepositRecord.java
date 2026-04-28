package com.example.web3.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充值记录实体
 * @author jiangyuxuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepositRecord {
    
    /**
     * 记录状态枚举
     */
    public enum Status {
        PENDING,    // 待确认
        CONFIRMED,  // 已确认
        REORG       // 已重组
    }

    private Long id;
    
    private String txHash;
    
    private Long blockNumber;
    
    private String blockHash;
    
    private String contractAddress;
    
    private String fromAddress;
    
    private String toAddress;
    
    // 原始值
    private String amount;
    
    // 转换后的实际值
    private BigDecimal amountDecimal;
    
    private Integer decimals;
    
    private Integer logIndex;
    
    private Integer transactionIndex;
    
    private Long timestamp;
    
    private LocalDateTime createdAt;
    
    private LocalDateTime updatedAt;
    
    /**
     * 记录状态
     */
    private String status;
    
    /**
     * 确认时间
     */
    private LocalDateTime confirmedAt;
    
    /**
     * 是否发生过重组
     */
    private Boolean isReorg;
    
    /**
     * 重组检测时间
     */
    private LocalDateTime reorgDetectedAt;
}

