package com.example.web3.controller;

import com.example.web3.entity.BlockCheckpoint;
import com.example.web3.entity.DepositRecord;
import com.example.web3.entity.ReorgHistory;
import com.example.web3.mapper.DepositMapper;
import com.example.web3.mapper.ReorgHistoryMapper;
import com.example.web3.service.BlockCheckpointService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;
import java.util.List;

/**
 * 监控和管理接口
 * @author jiangyuxuan
 */
@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
public class MonitorController {

    private final BlockCheckpointService checkpointService;
    private final ReorgHistoryMapper reorgHistoryMapper;
    private final DepositMapper depositMapper;
    private final Web3j web3j;

    /**
     * 获取系统状态
     */
    @GetMapping("/status")
    public ApiResponse<SystemStatus> getSystemStatus(@RequestParam String contractAddress) {
        try {
            // 获取检查点
            BlockCheckpoint checkpoint = checkpointService.getCheckpoint(contractAddress);
            
            // 获取当前区块高度
            BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();
            
            // 统计待确认记录数
            List<DepositRecord> pendingRecords = depositMapper.findPendingRecords(1000);
            
            SystemStatus status = new SystemStatus();
            status.setLatestBlockOnChain(latestBlock.longValue());
            status.setLastProcessedBlock(checkpoint != null ? checkpoint.getLastProcessedBlock() : null);
            status.setLastProcessedBlockHash(checkpoint != null ? checkpoint.getLastProcessedBlockHash() : null);
            status.setPendingRecordsCount(pendingRecords.size());
            
            if (checkpoint != null) {
                long lag = latestBlock.longValue() - checkpoint.getLastProcessedBlock();
                status.setBlockLag(lag);
            }
            
            return ApiResponse.success(status);
            
        } catch (Exception e) {
            return ApiResponse.error("获取系统状态失败: " + e.getMessage());
        }
    }

    /**
     * 获取重组历史
     */
    @GetMapping("/reorg-history")
    public ApiResponse<List<ReorgHistory>> getReorgHistory(
            @RequestParam(defaultValue = "10") int limit) {
        
        List<ReorgHistory> history = reorgHistoryMapper.findRecent(limit);
        return ApiResponse.success(history);
    }

    /**
     * 获取待确认记录
     */
    @GetMapping("/pending-records")
    public ApiResponse<List<DepositRecord>> getPendingRecords(
            @RequestParam(defaultValue = "50") int limit) {
        
        List<DepositRecord> records = depositMapper.findPendingRecords(limit);
        return ApiResponse.success(records);
    }

    /**
     * 系统状态数据
     */
    @Data
    public static class SystemStatus {
        private Long latestBlockOnChain;
        private Long lastProcessedBlock;
        private String lastProcessedBlockHash;
        private Long blockLag;
        private Integer pendingRecordsCount;
    }

    /**
     * 统一响应格式
     */
    @Data
    @AllArgsConstructor
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            return new ApiResponse<>(200, "success", data);
        }

        public static <T> ApiResponse<T> error(String message) {
            return new ApiResponse<>(500, message, null);
        }
    }
}
