package com.example.web3.mapper;

import com.example.web3.entity.BlockCheckpoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 区块检查点 Mapper
 * @author jiangyuxuan
 */
@Mapper
public interface BlockCheckpointMapper {
    
    /**
     * 查询检查点
     */
    BlockCheckpoint findByContractAddress(@Param("contractAddress") String contractAddress);
    
    /**
     * 插入检查点
     */
    int insert(BlockCheckpoint checkpoint);
    
    /**
     * 更新检查点
     */
    int update(BlockCheckpoint checkpoint);
    
    /**
     * 插入或更新检查点
     */
    int upsert(BlockCheckpoint checkpoint);
}
