package com.ray.banking.repository;

import com.ray.banking.model.TransactionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<TransactionRecord, Long> {

    List<TransactionRecord> findByAccountIdOrderByTimestampDescIdDesc(Long accountId);
}
