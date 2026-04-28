package com.example.web3.listener;

import com.example.web3.config.Web3jConfig;
import com.example.web3.entity.BlockCheckpoint;
import com.example.web3.entity.DepositRecord;
import com.example.web3.service.BlockCheckpointService;
import com.example.web3.service.DepositService;
import com.example.web3.service.ReorgDetectionService;
import io.reactivex.disposables.Disposable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.Log;

import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/**
 * 改进版 Transfer 事件监听器
 * 
 * 核心改进：
 * 1. 断点续传：系统重启后从上次处理的区块继续
 * 2. 链重组检测：定期检查待确认记录是否发生重组
 * 3. 状态管理：PENDING -> CONFIRMED 的状态流转
 * 4. 批量处理：支持历史区块批量扫描
 * 
 * @author jiangyuxuan
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImprovedTokenTransferListener implements CommandLineRunner {

    private final Web3j web3j;
    private final Web3jConfig web3jConfig;
    private final DepositService depositService;
    private final BlockCheckpointService checkpointService;
    private final ReorgDetectionService reorgDetectionService;

    private Disposable subscription;
    private int tokenDecimals = 18;

    private static final Event TRANSFER_EVENT = new Event("Transfer",
            Arrays.asList(
                    new TypeReference<Address>(true) {},
                    new TypeReference<Address>(true) {},
                    new TypeReference<Uint256>(false) {}
            ));

    private static final String TRANSFER_EVENT_SIGNATURE = EventEncoder.encode(TRANSFER_EVENT);

    @Override
    public void run(String... args) {
        log.info("=== 启动改进版监听器 ===");
        
        if (!testConnection()) {
            log.error("无法连接到 RPC 节点，请检查配置");
            return;
        }

        fetchTokenDecimals();
        startListeningWithResume();
    }

    /**
     * 测试 RPC 连接
     */
    private boolean testConnection() {
        try {
            log.info("测试 RPC 连接: {}", web3jConfig.getRpcUrl());
            String clientVersion = web3j.web3ClientVersion().send().getWeb3ClientVersion();
            BigInteger blockNumber = web3j.ethBlockNumber().send().getBlockNumber();
            
            log.info("✓ 连接成功 - 节点: {}, 当前区块: {}", clientVersion, blockNumber);
            return true;
        } catch (Exception e) {
            log.error("✗ 连接失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 从合约获取 decimals
     */
    private void fetchTokenDecimals() {
        try {
            String contractAddress = web3jConfig.getToken().getContractAddress();
            org.web3j.protocol.core.methods.request.Transaction transaction = 
                org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                    null, contractAddress, "0x313ce567"
                );
            
            String result = web3j.ethCall(transaction, DefaultBlockParameterName.LATEST)
                    .send().getValue();
            
            if (result != null && !result.equals("0x")) {
                tokenDecimals = new BigInteger(result.substring(2), 16).intValue();
                log.info("✓ Token decimals: {}", tokenDecimals);
            }
        } catch (Exception e) {
            log.warn("获取 decimals 失败，使用默认值 {}", tokenDecimals);
        }
    }

    /**
     * 启动监听（支持断点续传）
     */
    private void startListeningWithResume() {
        try {
            String contractAddress = web3jConfig.getToken().getContractAddress();
            
            // 1. 查询检查点，确定起始区块
            BlockCheckpoint checkpoint = checkpointService.getCheckpoint(contractAddress);
            BigInteger startBlock;
            
            if (checkpoint != null) {
                // 从检查点恢复（从下一个区块开始）
                startBlock = BigInteger.valueOf(checkpoint.getLastProcessedBlock() + 1);
                log.info("✓ 从检查点恢复: 上次处理区块={}, 从区块 {} 开始监听",
                        checkpoint.getLastProcessedBlock(), startBlock);
                
                // 检查检查点区块是否发生重组
                boolean isReorg = reorgDetectionService.detectReorg(
                        checkpoint.getLastProcessedBlock(),
                        checkpoint.getLastProcessedBlockHash()
                );
                
                if (isReorg) {
                    log.warn("⚠ 检测到检查点区块发生重组，需要回退处理");
                    // 可以选择回退几个区块重新处理
                    startBlock = BigInteger.valueOf(checkpoint.getLastProcessedBlock() - 10);
                }
                
            } else {
                // 首次启动，从配置的起始区块开始
                String configStartBlock = web3jConfig.getToken().getStartBlock();
                if ("latest".equalsIgnoreCase(configStartBlock)) {
                    startBlock = web3j.ethBlockNumber().send().getBlockNumber();
                    log.info("✓ 首次启动: 从最新区块 {} 开始监听", startBlock);
                } else {
                    startBlock = new BigInteger(configStartBlock);
                    log.info("✓ 首次启动: 从配置区块 {} 开始监听", startBlock);
                }
            }

            // 2. 创建过滤器
            EthFilter filter = new EthFilter(
                    DefaultBlockParameter.valueOf(startBlock),
                    DefaultBlockParameterName.LATEST,
                    contractAddress
            );
            filter.addSingleTopic(TRANSFER_EVENT_SIGNATURE);

            // 3. 订阅事件
            subscription = web3j.ethLogFlowable(filter).subscribe(
                    this::handleTransferEvent,
                    error -> log.error("监听出错: {}", error.getMessage(), error),
                    () -> log.info("监听流完成")
            );

            log.info("✓ 监听启动成功");

        } catch (Exception e) {
            log.error("✗ 启动失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理 Transfer 事件
     */
    private void handleTransferEvent(Log eventLog) {
        try {
            List<String> topics = eventLog.getTopics();
            if (topics.size() < 3) {
                log.error("topics 数量不对: {}", topics.size());
                return;
            }

            // 解析事件数据
            String fromAddress = decodeAddress(topics.get(1));
            String toAddress = decodeAddress(topics.get(2));
            
            String data = eventLog.getData();
            BigInteger value = new BigInteger(data.substring(2), 16);
            BigDecimal amountDecimal = new BigDecimal(value)
                    .divide(BigDecimal.TEN.pow(tokenDecimals));

            // 获取区块信息
            EthBlock.Block block = web3j.ethGetBlockByNumber(
                    DefaultBlockParameter.valueOf(eventLog.getBlockNumber()),
                    false
            ).send().getBlock();

            Long timestamp = block != null ? block.getTimestamp().longValue() 
                                           : System.currentTimeMillis() / 1000;

            // 检查确认数
            Integer requiredConfirmations = web3jConfig.getToken().getConfirmations();
            boolean isConfirmed = isConfirmed(eventLog, requiredConfirmations);
            
            // 根据确认状态设置记录状态
            String status = isConfirmed ? DepositRecord.Status.CONFIRMED.name() 
                                        : DepositRecord.Status.PENDING.name();

            // 构建记录
            DepositRecord record = DepositRecord.builder()
                    .txHash(eventLog.getTransactionHash())
                    .blockNumber(eventLog.getBlockNumber().longValue())
                    .blockHash(eventLog.getBlockHash())
                    .contractAddress(eventLog.getAddress())
                    .fromAddress(fromAddress)
                    .toAddress(toAddress)
                    .amount(value.toString())
                    .amountDecimal(amountDecimal)
                    .decimals(tokenDecimals)
                    .logIndex(eventLog.getLogIndex().intValue())
                    .transactionIndex(eventLog.getTransactionIndex().intValue())
                    .timestamp(timestamp)
                    .status(status)
                    .build();

            log.info("Transfer 事件 [{}] - tx: {}, from: {}, to: {}, amount: {}",
                    status, record.getTxHash(), record.getFromAddress(), 
                    record.getToAddress(), record.getAmountDecimal());

            // 保存记录
            depositService.saveDepositRecord(record);

            // 更新检查点（只在确认后更新）
            if (isConfirmed) {
                checkpointService.saveCheckpoint(
                        eventLog.getAddress(),
                        eventLog.getBlockNumber().longValue(),
                        eventLog.getBlockHash()
                );
            }

        } catch (Exception e) {
            log.error("处理事件失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 检查区块确认数
     */
    private boolean isConfirmed(Log eventLog, Integer requiredConfirmations) {
        try {
            if (requiredConfirmations == null || requiredConfirmations == 0) {
                return true;
            }

            BigInteger latestBlockNumber = web3j.ethBlockNumber().send().getBlockNumber();
            BigInteger eventBlockNumber = eventLog.getBlockNumber();
            BigInteger confirmations = latestBlockNumber.subtract(eventBlockNumber);

            return confirmations.compareTo(BigInteger.valueOf(requiredConfirmations)) >= 0;

        } catch (Exception e) {
            log.error("检查确认数失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 定时任务：检查待确认记录并更新状态
     * 每30秒执行一次
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 60000)
    public void checkPendingRecords() {
        try {
            log.debug("开始检查待确认记录...");
            
            // 1. 检查重组
            reorgDetectionService.checkPendingRecordsForReorg(100);
            
            // 2. 检查确认数并更新状态
            depositService.updatePendingRecordsStatus();
            
        } catch (Exception e) {
            log.error("检查待确认记录失败", e);
        }
    }

    /**
     * 解码地址
     */
    private String decodeAddress(String topic) {
        if (topic.length() < 66) {
            return topic;
        }
        return "0x" + topic.substring(26);
    }

    @PreDestroy
    public void stopListening() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
            log.info("✓ 监听器已停止");
        }
    }
}
