package com.example.web3.mapper;

import com.example.web3.entity.DepositRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author jiangyuxuan
 */
@Mapper
public interface DepositMapper {

    int insert(DepositRecord record);

    DepositRecord findByTxHash(@Param("txHash") String txHash);

    List<DepositRecord> findByToAddress(@Param("address") String address,
                                         @Param("offset") Integer offset,
                                         @Param("limit") Integer limit);

    List<DepositRecord> findAll(@Param("offset") Integer offset,
                                 @Param("limit") Integer limit);
    
    /**
     * 查询待确认的记录
     */
    List<DepositRecord> findPendingRecords(@Param("limit") Integer limit);
    
    /**
     * 根据区块号查询记录
     */
    List<DepositRecord> findByBlockNumber(@Param("blockNumber") Long blockNumber);
    
    /**
     * 更新记录状态
     */
    int updateStatus(@Param("id") Long id, 
                     @Param("status") String status,
                     @Param("confirmedAt") java.time.LocalDateTime confirmedAt);
    
    /**
     * 标记为重组
     */
    int markAsReorg(@Param("blockNumber") Long blockNumber,
                    @Param("reorgDetectedAt") java.time.LocalDateTime reorgDetectedAt);
    
    /**
     * 查询最新的 CONFIRMED 记录
     */
    DepositRecord findLatestConfirmed();
}

