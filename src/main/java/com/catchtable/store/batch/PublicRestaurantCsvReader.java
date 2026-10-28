package com.catchtable.store.batch;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

// 서울시 일반음식점 인허가 CSV (MS949, 헤더 1줄)
// CSV가 한글 깨짐 인코딩이고 50만 줄이 넘음
@Component
public class PublicRestaurantCsvReader {

    private static final Charset MS949 = Charset.forName("MS949");
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .build();

    // 50만 행이 넘으므로 메모리에 다 올리지 않고 한 줄씩 넘긴다
    public void read(Path path, Consumer<PublicRestaurantRow> consumer) throws IOException {
        // 원본에 MS949로 해석되지 않는 바이트가 있다(2026-10-28 기준 폐업 매장 4행)
        //   - 기본 디코더는 예외로 전체 읽기를 멈추므로 �로 바꾸고 계속 읽는다
        CharsetDecoder decoder = MS949.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (Reader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(path), decoder));
             CSVParser parser = FORMAT.parse(reader)) {
            for (CSVRecord record : parser) {
                consumer.accept(toRow(record));
            }
        }
    }

    // 컬럼 순서가 바뀌어도 깨지지 않도록 헤더 이름으로 읽는다
    private PublicRestaurantRow toRow(CSVRecord r) {
        return new PublicRestaurantRow(
                r.get("관리번호"),
                r.get("영업상태명"),
                r.get("전화번호"),
                r.get("지번주소"),
                r.get("도로명주소"),
                r.get("사업장명"),
                r.get("최종수정일자"),
                r.get("업태구분명"),
                r.get("X좌표"),
                r.get("Y좌표"));
    }
}
