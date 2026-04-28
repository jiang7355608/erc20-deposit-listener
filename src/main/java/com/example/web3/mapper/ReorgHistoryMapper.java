package com.example.web3.mapper;

import com.example.web3.entity.ReorgHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 链重组历史 Mapper
 * @author jiangyuxuan
 */
@Mapper
public interface ReorgHistoryMapper {
    
    /**
     * 插入重组记录
     */
    int insert(ReorgHistory history);
    
    /**
     * 查询最近的重组记录
     */
    List<ReorgHistory> findRecent(@Param("limit") int limit);
    
    /**
     * 更新解决时间
     */
    int updateResolvedAt(@Param("id") Long id);
}
