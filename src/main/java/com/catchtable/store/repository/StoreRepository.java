package com.catchtable.store.repository;

import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
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

    // 필터 없는 목록: DB가 해당 페이지만 읽는다 (Slice는 size+1건을 읽어 hasNext만 계산하고 count 쿼리는 없다)
    Slice<Store> findAllBy(Pageable pageable);

    Slice<Store> findAllByCategory(StoreCategory category, Pageable pageable);

    // 필터 있는 목록: 전부 읽되 id 순으로 정렬해 페이지 사이 순서를 고정한다
    List<Store> findAllByCategory(StoreCategory category, Sort sort);
}