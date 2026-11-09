package com.catchtable.store.service;

import com.catchtable.store.dto.OpenStatus;
import com.catchtable.store.dto.OpenStatusInfo;
import com.catchtable.store.dto.OpeningWindow;
import com.catchtable.store.entity.BusinessHour;
import com.catchtable.store.entity.SpecialBusinessDay;
import com.catchtable.store.repository.BusinessHourRepository;
import com.catchtable.store.repository.SpecialBusinessDayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

// 지도 목록처럼 여러 매장의 "지금 영업 상태"를 한 번에 계산한다.
// 영업 구간 계산은 BusinessHour·SpecialBusinessDay의 규칙(브레이크, 자정 넘김, 특정일 우선)을 그대로 쓴다
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreOpenStatusService {

    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Seoul");
    // 다음 영업을 찾는 범위. 요일별 설정은 일주일 단위로 반복되므로 7일이면 충분하다
    private static final int SEARCH_DAYS = 7;

    private final BusinessHourRepository businessHourRepository;
    private final SpecialBusinessDayRepository specialBusinessDayRepository;
    private final Clock clock;

    // 영업시간(요일별·특정일)이 하나도 없는 매장은 결과에 넣지 않는다 (= 상태를 알 수 없음)
    public Map<Long, OpenStatusInfo> getOpenStatuses(Collection<Long> storeIds) {
        if (storeIds.isEmpty()) {
            return Map.of();
        }
        LocalDateTime now = OffsetDateTime.now(clock).atZoneSameInstant(STORE_ZONE).toLocalDateTime();
        LocalDate today = now.toLocalDate();

        Map<Long, Map<DayOfWeek, BusinessHour>> hoursByStore = new HashMap<>();
        for (BusinessHour hour : businessHourRepository
                .findAllByStoreIdInAndDayOfWeekIn(storeIds, EnumSet.allOf(DayOfWeek.class))) {
            hoursByStore.computeIfAbsent(hour.getStore().getId(), id -> new EnumMap<>(DayOfWeek.class))
                    .put(hour.getDayOfWeek(), hour);
        }

        // 어제 영업이 자정을 넘겨 오늘로 이어질 수 있으므로 어제부터 읽는다
        Map<Long, Map<LocalDate, SpecialBusinessDay>> specialsByStore = new HashMap<>();
        for (SpecialBusinessDay special : specialBusinessDayRepository
                .findAllByStoreIdInAndBusinessDateBetween(storeIds, today.minusDays(1), today.plusDays(SEARCH_DAYS))) {
            specialsByStore.computeIfAbsent(special.getStore().getId(), id -> new HashMap<>())
                    .put(special.getBusinessDate(), special);
        }

        Map<Long, OpenStatusInfo> result = new HashMap<>();
        for (Long storeId : storeIds) {
            Map<DayOfWeek, BusinessHour> hours = hoursByStore.getOrDefault(storeId, Map.of());
            Map<LocalDate, SpecialBusinessDay> specials = specialsByStore.getOrDefault(storeId, Map.of());
            if (hours.isEmpty() && specials.isEmpty()) {
                continue;
            }
            result.put(storeId, resolve(now, date -> windowsOf(date, hours, specials)));
        }
        return result;
    }

    // 특정일 설정이 있으면 요일별 설정보다 우선한다. 특정일 휴무와 요일 설정 없음은 빈 목록이다
    private static List<OpeningWindow> windowsOf(LocalDate date, Map<DayOfWeek, BusinessHour> hours,
                                                 Map<LocalDate, SpecialBusinessDay> specials) {
        SpecialBusinessDay special = specials.get(date);
        if (special != null) {
            return special.windows();
        }
        BusinessHour hour = hours.get(date.getDayOfWeek());
        return hour == null ? List.of() : hour.windowsOn(date);
    }

    // now 시점의 상태. windowsOf는 영업일 하나의 영업 구간을 돌려준다 (브레이크가 있으면 구간 2개)
    // 경계는 OpeningWindow와 같다: 시작 시각 정각은 영업 중, 종료 시각 정각은 영업 종료
    static OpenStatusInfo resolve(LocalDateTime now, Function<LocalDate, List<OpeningWindow>> windowsOf) {
        LocalDate today = now.toLocalDate();

        // days.get(0)=어제, days.get(1)=오늘, ... days.get(SEARCH_DAYS+1)=7일 뒤
        List<List<OpeningWindow>> days = new ArrayList<>();
        List<OpeningWindow> windows = new ArrayList<>();
        for (int offset = -1; offset <= SEARCH_DAYS; offset++) {
            List<OpeningWindow> dayWindows = windowsOf.apply(today.plusDays(offset));
            days.add(dayWindows);
            windows.addAll(dayWindows);
        }

        for (OpeningWindow window : windows) {
            if (window.contains(now)) {
                return new OpenStatusInfo(OpenStatus.OPEN, continuousEnd(window, windows));
            }
        }

        // 브레이크타임: 같은 영업일의 첫 구간이 끝나고 둘째 구간이 시작하기 전. 어제 영업일이 자정을 넘긴 경우도 본다
        for (int i = 0; i <= 1; i++) {
            List<OpeningWindow> dayWindows = days.get(i);
            if (dayWindows.size() == 2
                    && !now.isBefore(dayWindows.get(0).end())
                    && now.isBefore(dayWindows.get(1).start())) {
                return new OpenStatusInfo(OpenStatus.BREAK, dayWindows.get(1).start());
            }
        }

        Optional<LocalDateTime> next = windows.stream()
                .map(OpeningWindow::start)
                .filter(start -> start.isAfter(now))
                .min(LocalDateTime::compareTo);
        if (next.isEmpty()) {
            return new OpenStatusInfo(OpenStatus.CLOSED, null);
        }
        OpenStatus status = next.get().toLocalDate().equals(today) ? OpenStatus.BEFORE_OPEN : OpenStatus.CLOSED;
        return new OpenStatusInfo(status, next.get());
    }

    // 자정에 영업일이 바뀌어도 끊김 없이 이어지는 영업(예: 월 00:00 마감, 화 00:00 오픈)은 하나로 본다
    private static LocalDateTime continuousEnd(OpeningWindow window, List<OpeningWindow> windows) {
        LocalDateTime end = window.end();
        boolean extended = true;
        while (extended) {
            extended = false;
            for (OpeningWindow other : windows) {
                if (other.start().equals(end)) {
                    end = other.end();
                    extended = true;
                }
            }
        }
        return end;
    }
}
