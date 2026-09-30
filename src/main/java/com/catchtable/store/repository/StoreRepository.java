package com.catchtable.store.repository;

import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store,Long> {

    // 예약 생성끼리는 병행하되, 검증부터 저장까지 매장 설정이 바뀌지 않도록 보호한다.
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select s from Store s where s.id = :id")
    Optional<Store> findByIdForShare(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Store s where s.id = :id")
    Optional<Store> findByIdForUpdate(@Param("id") Long id);

    List<Store> findAllByCategory(StoreCategory category);
}
