package com.catchtable.store.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.dto.SpecialBusinessDayRequest;
import com.catchtable.store.dto.SpecialBusinessDayResponse;
import com.catchtable.store.entity.SpecialBusinessDay;
import com.catchtable.store.entity.Store;
import com.catchtable.store.repository.SpecialBusinessDayRepository;
import com.catchtable.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpecialBusinessDayService {

    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Seoul");

    private final StoreRepository storeRepository;
    private final SpecialBusinessDayRepository specialBusinessDayRepository;
    private final BusinessHourService businessHourService;
    private final Clock clock;

    // TODO: 점주 본인 매장인지 확인 (로그인 기능 만든 후 작업)

    public List<SpecialBusinessDayResponse> getSpecialBusinessDays(Long storeId) {
        storeRepository.findById(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND))
                .validateManaged();
        return specialBusinessDayRepository.findAllByStoreIdOrderByBusinessDateAsc(storeId).stream()
                .map(SpecialBusinessDayResponse::from)
                .toList();
    }

    // 그 날짜 설정이 있으면 수정하고, 없으면 새로 저장한다.
    @Transactional
    public SpecialBusinessDayResponse saveSpecialBusinessDay(Long storeId, LocalDate businessDate,
                                                             SpecialBusinessDayRequest request) {
        Store store = lockManagedStore(storeId);
        validateNotPast(businessDate);

        SpecialBusinessDay day = specialBusinessDayRepository.findByStoreIdAndBusinessDate(storeId, businessDate)
                .orElseGet(() -> SpecialBusinessDay.closedDay(store, businessDate));
        if (request.closed()) {
            day.changeToClosed();
        } else {
            day.changeToOpen(request.openTime(), request.closingTime(),
                    request.breakStartTime(), request.breakEndTime());
        }
        specialBusinessDayRepository.saveAndFlush(day);

        // 충돌하면 예외가 나면서 위 저장까지 전부 롤백된다.
        businessHourService.validateActiveReservationsCovered(storeId);
        return SpecialBusinessDayResponse.from(day);
    }

    // 삭제하면 요일별 설정으로 돌아가므로, 그 설정과 충돌하는 예약이 없는지도 확인한다.
    @Transactional
    public void deleteSpecialBusinessDay(Long storeId, LocalDate businessDate) {
        lockManagedStore(storeId);
        validateNotPast(businessDate);

        SpecialBusinessDay day = specialBusinessDayRepository.findByStoreIdAndBusinessDate(storeId, businessDate)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPECIAL_BUSINESS_DAY_NOT_FOUND));
        day.delete();
        specialBusinessDayRepository.flush();

        businessHourService.validateActiveReservationsCovered(storeId);
    }

    // 예약 생성의 공유 잠금과 맞물려, 검증 중 새 예약이 생기지 않게 한다.
    private Store lockManagedStore(Long storeId) {
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        store.validateManaged();
        return store;
    }

    private void validateNotPast(LocalDate businessDate) {
        LocalDate today = OffsetDateTime.now(clock).atZoneSameInstant(STORE_ZONE).toLocalDate();
        if (businessDate.isBefore(today)) {
            throw new BusinessException(ErrorCode.PAST_BUSINESS_DATE);
        }
    }
}
