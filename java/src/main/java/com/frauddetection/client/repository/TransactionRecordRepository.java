package com.frauddetection.client.repository;

import com.frauddetection.client.model.TransactionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for local transaction history.
 *
 * <p>Spring auto-generates the implementation at startup — no boilerplate SQL needed.
 * This is the Java equivalent of SQLAlchemy's session queries in the Python API.
 */
@Repository
public interface TransactionRecordRepository extends JpaRepository<TransactionRecord, Long> {

    Optional<TransactionRecord> findByTransactionId(String transactionId);

    List<TransactionRecord> findByUserId(String userId);

    List<TransactionRecord> findByIsFraudulentTrue();

    long countByIsFraudulentTrue();
}
