package com.catchtable.store.repository;

import com.catchtable.store.entity.Menu;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    Optional<Menu> findByIdAndStoreId(Long id, Long storeId);

    // 노출 순서 컬럼이 없어 등록순으로 보여준다
    List<Menu> findAllByStoreIdOrderByIdAsc(Long storeId);

}
