package com.catchtable.store.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

//csv 올리는 거 관리
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreImportService {

    private static final int CHUNK_SIZE = 1_000;

    private final PublicRestaurantCsvReader reader;
    private final CoordinateConverter coordinateConverter;
    private final StoreImportJdbcWriter writer;

    public void importFrom(Path path) throws IOException {
        List<StoreImportRow> chunk = new ArrayList<>(CHUNK_SIZE);
        int[] counts = new int[2];   // [읽음, 적재]

        reader.read(path, row -> {
            counts[0]++;
            // 폐업·좌표 없는 매장 제외
            if (!row.isOpen() || !row.hasCoordinate()) {
                return;
            }
            chunk.add(toImportRow(row));
            if (chunk.size() == CHUNK_SIZE) {
                counts[1] += flush(chunk);
            }
        });
        counts[1] += flush(chunk);

        log.info("공공데이터 매장 적재 완료: 읽음 {}건, 적재 {}건", counts[0], counts[1]);
    }

    private StoreImportRow toImportRow(PublicRestaurantRow row) {
        double[] latLng = coordinateConverter.toLatLng(
                Double.parseDouble(row.x()), Double.parseDouble(row.y()));
        String address = row.roadAddress() != null ? row.roadAddress() : row.lotAddress();
        return new StoreImportRow(
                row.mgtNo(),
                row.bizName(),
                StoreCategoryMapper.map(row.bizType()),
                address,
                row.phone(),
                latLng[0],
                latLng[1]);
    }

    private int flush(List<StoreImportRow> chunk) {
        if (chunk.isEmpty()) {
            return 0;
        }
        writer.upsert(chunk);
        int size = chunk.size();
        chunk.clear();
        return size;
    }
}
