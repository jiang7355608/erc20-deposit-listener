-- 升级脚本：添加链重组检测和断点续传支持

-- 1. 为 deposit_record 表添加状态字段
ALTER TABLE deposit_record 
ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
ADD COLUMN confirmed_at TIMESTAMP,
ADD COLUMN is_reorg BOOLEAN NOT NULL DEFAULT FALSE,
ADD COLUMN reorg_detected_at TIMESTAMP;

-- 状态说明：
-- PENDING: 待确认（刚监听到，确认数不足）
-- CONFIRMED: 已确认（达到要求的确认数）
-- REORG: 已重组（检测到该交易所在区块被重组）

COMMENT ON COLUMN deposit_record.status IS '记录状态: PENDING-待确认, CONFIRMED-已确认, REORG-已重组';
COMMENT ON COLUMN deposit_record.confirmed_at IS '确认时间';
COMMENT ON COLUMN deposit_record.is_reorg IS '是否发生过重组';
COMMENT ON COLUMN deposit_record.reorg_detected_at IS '重组检测时间';

-- 2. 创建区块检查点表（用于断点续传）
CREATE TABLE IF NOT EXISTS block_checkpoint (
    id SERIAL PRIMARY KEY,
    contract_address VARCHAR(42) NOT NULL,
    last_processed_block BIGINT NOT NULL,
    last_processed_block_hash VARCHAR(66) NOT NULL,
    last_processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_contract_address UNIQUE (contract_address)
);

COMMENT ON TABLE block_checkpoint IS '区块处理检查点（用于断点续传）';
COMMENT ON COLUMN block_checkpoint.contract_address IS '合约地址';
COMMENT ON COLUMN block_checkpoint.last_processed_block IS '最后处理的区块号';
COMMENT ON COLUMN block_checkpoint.last_processed_block_hash IS '最后处理的区块哈希';
COMMENT ON COLUMN block_checkpoint.last_processed_at IS '最后处理时间';

-- 3. 创建索引优化查询
CREATE INDEX idx_deposit_status ON deposit_record(status);
CREATE INDEX idx_deposit_block_number ON deposit_record(block_number);
CREATE INDEX idx_deposit_confirmed_at ON deposit_record(confirmed_at);

-- 4. 创建链重组历史记录表
CREATE TABLE IF NOT EXISTS reorg_history (
    id BIGSERIAL PRIMARY KEY,
    contract_address VARCHAR(42) NOT NULL,
    old_block_number BIGINT NOT NULL,
    old_block_hash VARCHAR(66) NOT NULL,
    new_block_hash VARCHAR(66) NOT NULL,
    affected_tx_count INTEGER NOT NULL DEFAULT 0,
    detected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP
);

COMMENT ON TABLE reorg_history IS '链重组历史记录';
COMMENT ON COLUMN reorg_history.old_block_number IS '发生重组的区块号';
COMMENT ON COLUMN reorg_history.old_block_hash IS '旧的区块哈希';
COMMENT ON COLUMN reorg_history.new_block_hash IS '新的区块哈希';
COMMENT ON COLUMN reorg_history.affected_tx_count IS '受影响的交易数量';

CREATE INDEX idx_reorg_contract ON reorg_history(contract_address);
CREATE INDEX idx_reorg_detected_at ON reorg_history(detected_at);
