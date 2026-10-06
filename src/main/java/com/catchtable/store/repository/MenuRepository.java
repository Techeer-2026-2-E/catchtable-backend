package com.catchtable.store.repository;

import com.catchtable.store.entity.Menu;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    Optional<Menu> findByIdAndStoreId(Long id, Long storeId);

}
