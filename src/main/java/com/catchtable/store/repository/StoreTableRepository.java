package com.catchtable.store.repository;

import com.catchtable.store.entity.StoreTable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreTableRepository extends JpaRepository<StoreTable, Long> {
}
