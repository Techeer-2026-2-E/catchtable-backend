package com.catchtable.store.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.DayOfWeek;

// DB에는 1=월 ~ 7=일 숫자로 저장 (java.time.DayOfWeek.getValue()와 동일)
@Converter
public class DayOfWeekConverter implements AttributeConverter<DayOfWeek, Integer> {

    @Override
    public Integer convertToDatabaseColumn(DayOfWeek dayOfWeek) {
        return dayOfWeek == null ? null : dayOfWeek.getValue();
    }

    @Override
    public DayOfWeek convertToEntityAttribute(Integer value) {
        return value == null ? null : DayOfWeek.of(value);
    }
}
