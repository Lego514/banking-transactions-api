package com.ray.banking.repository;

import com.ray.banking.model.TransactionRecord;
import com.ray.banking.model.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<TransactionRecord, Long> {

    List<TransactionRecord> findByAccountIdOrderByTimestampDescIdDesc(Long accountId);

    Optional<TransactionRecord> findByTransferIdAndType(UUID transferId, TransactionType type);
}
