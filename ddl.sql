
-- PostgreSQL DDL

-- 表: deposit_record (充值记录表)
CREATE TABLE deposit_record (
    id                BIGSERIAL PRIMARY KEY,
    tx_hash           VARCHAR(66)     NOT NULL,
    block_number      BIGINT          NOT NULL,
    block_hash        VARCHAR(66)     NOT NULL,
    contract_address  VARCHAR(42)     NOT NULL,
    from_address      VARCHAR(42)     NOT NULL,
    to_address        VARCHAR(42)     NOT NULL,
    amount            VARCHAR(78)     NOT NULL,
    amount_decimal    DECIMAL(36, 18) NOT NULL,
    decimals          INTEGER         NOT NULL DEFAULT 18,
    log_index         INTEGER         NOT NULL,
    transaction_index INTEGER         NOT NULL,
    timestamp         BIGINT          NOT NULL,
    created_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- 唯一索引: (交易哈希 + 日志索引) 保证幂等性
CREATE UNIQUE INDEX uk_tx_hash_log_index ON deposit_record(tx_hash, log_index);




COMMENT ON TABLE deposit_record IS 'ERC20 Token充值记录';
COMMENT ON COLUMN deposit_record.id IS '主键ID';
COMMENT ON COLUMN deposit_record.tx_hash IS '交易哈希';
COMMENT ON COLUMN deposit_record.block_number IS '区块号';
COMMENT ON COLUMN deposit_record.block_hash IS '区块哈希';
COMMENT ON COLUMN deposit_record.contract_address IS '合约地址';
COMMENT ON COLUMN deposit_record.from_address IS '转出地址';
COMMENT ON COLUMN deposit_record.to_address IS '转入地址';
COMMENT ON COLUMN deposit_record.amount IS '转账金额（原始值，不带小数）';
COMMENT ON COLUMN deposit_record.amount_decimal IS '转账金额（带小数）';
COMMENT ON COLUMN deposit_record.decimals IS 'Token小数位数';
COMMENT ON COLUMN deposit_record.log_index IS '日志索引';
COMMENT ON COLUMN deposit_record.transaction_index IS '交易索引';
COMMENT ON COLUMN deposit_record.timestamp IS '事件时间戳';
COMMENT ON COLUMN deposit_record.created_at IS '创建时间';
COMMENT ON COLUMN deposit_record.updated_at IS '更新时间';


