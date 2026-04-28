# ERC20 Deposit Listener

Production-ready ERC20 deposit monitoring system with checkpoint recovery and chain reorganization detection.

[中文文档](README-Chinese.md)

## Features

- **Checkpoint Recovery**: Automatic resume from last processed block after crashes
- **Reorg Detection**: Multi-threaded detection with smart alerting for shallow/deep reorgs
- **State Machine**: PENDING → CONFIRMED → REORG with configurable confirmation thresholds
- **Idempotency**: Database-level duplicate prevention for multi-instance deployment
- **Monitoring APIs**: Real-time system status and reorg history tracking

## Quick Start

```bash
# Setup database
psql -U postgres -c "CREATE DATABASE web3_demo"
psql -U postgres -d web3_demo -f ddl.sql
psql -U postgres -d web3_demo -f ddl_upgrade.sql

# Configure application.yml with your RPC endpoint and contract address

# Run
mvn clean package
java -jar target/erc20-deposit-demo-1.0.0.jar
```

## Architecture

**Core Components:**
- `ImprovedTokenTransferListener`: Event monitoring with checkpoint recovery
- `ReorgDetectionService`: Parallel reorg detection (10 threads)
- `BlockCheckpointService`: Checkpoint management for crash recovery
- `DepositService`: Transaction state management

**Database Schema:**
- `deposit_record`: Transaction records with unique index on (tx_hash, log_index)
- `block_checkpoint`: Last processed block per contract
- `reorg_history`: Audit log of detected reorganizations

## How It Works

### Checkpoint Recovery

Saves checkpoint after each confirmed block. On restart:
- Reads last checkpoint and resumes from next block
- Verifies checkpoint block hash (rolls back 10 blocks if reorganized)
- Reprocesses PENDING transactions (duplicates prevented by unique index)
- Handles extended downtime by batch-fetching historical events

### Reorg Detection

Scheduled task (every 30s) checks PENDING blocks:
- Fetches current block hash from chain
- Compares with stored hash
- Marks affected transactions as REORG
- Triggers alert for deep reorgs (3+ consecutive blocks)

### Confirmation Strategy

Configurable confirmation count:
- Testnet: 3-6 blocks (~1 min)
- Mainnet: 12-32 blocks (~2-6 min)
- High-value: 64+ blocks (~13 min)

## API Endpoints

```bash
GET /api/deposits/address/{address}?limit=100
GET /api/deposits/tx/{txHash}
GET /api/monitor/status?contractAddress=0x...
GET /api/monitor/reorg-history?limit=10
GET /api/monitor/pending-records?limit=50
```

## Configuration

```yaml
web3j:
  rpc-url: https://sepolia.infura.io/v3/YOUR_KEY
  token:
    contract-address: "0x..."
    confirmations: 12
    start-block: latest
```

## FAQ

**Multi-instance deployment?**  
Yes. Unique index prevents duplicates.

**Reprocess historical blocks?**  
Delete checkpoint and set `start-block` to target block.

**Handle REORG transactions?**  
Filter with `WHERE status != 'REORG'`. System auto-captures new version.

**Other EVM chains?**  
Yes. Change RPC URL and chain ID.

## Tech Stack

Java 11 • Spring Boot • Web3j • PostgreSQL • MyBatis

## License

MIT

## References

- [Web3j Docs](https://docs.web3j.io/)
- [ERC20 Standard](https://eips.ethereum.org/EIPS/eip-20)
