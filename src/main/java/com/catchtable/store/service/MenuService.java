package com.catchtable.store.service;


import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.dto.MenuCreateRequest;
import com.catchtable.store.dto.MenuResponse;
import com.catchtable.store.dto.MenuUpdateRequest;
import com.catchtable.store.entity.Menu;
import com.catchtable.store.entity.Store;
import com.catchtable.store.repository.MenuRepository;
import com.catchtable.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuService {

    private final StoreRepository storeRepository;
    private final MenuRepository menuRepository;

    // TODO: 점주 본인 매장인지 확인 (로그인 기능 만든 후 작업)

    @Transactional
    public MenuResponse createMenu(Long storeId, MenuCreateRequest request)
    {
        Store store=storeRepository.findById(storeId)
                .orElseThrow(()->new BusinessException(ErrorCode.STORE_NOT_FOUND));

        Menu menu=Menu.builder()
                .store(store)
                .name(request.name())
                .price(request.price())
                .description(request.description())
                .build();
        return MenuResponse.from(menuRepository.save(menu));
    }

    @Transactional
    public MenuResponse updateMenu(Long storeId, Long menuId, MenuUpdateRequest request)
    {
        Menu menu=menuRepository.findByIdAndStoreId(menuId, storeId)
                .orElseThrow(()->new BusinessException(ErrorCode.MENU_NOT_FOUND));

        menu.changeInfo(request.name(), request.price(), request.description());
        return MenuResponse.from(menu);
    }
}
