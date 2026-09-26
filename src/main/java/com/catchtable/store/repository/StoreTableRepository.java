package com.catchtable.store.repository;

import com.catchtable.store.entity.StoreTable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoreTableRepository extends JpaRepository<StoreTable, Long> {

    List<StoreTable> findAllByStoreIdOrderByTableNumberAsc(Long storeId);

    boolean existsByStoreIdAndTableNumber(Long storeId, int tableNumber);

    Optional<StoreTable> findByIdAndStoreId(Long id, Long storeId);
}
