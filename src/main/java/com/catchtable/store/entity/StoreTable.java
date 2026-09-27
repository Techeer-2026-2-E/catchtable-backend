package com.catchtable.store.entity;


import com.catchtable.global.common.BaseTimeEntity;
import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name="store_table")
@Getter
@NoArgsConstructor(access= AccessLevel.PROTECTED)
public class StoreTable extends BaseTimeEntity {

    private static final int DEFAULT_MIN_CAPACITY = 1;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "table_number", nullable = false)
    private int tableNumber;

    //최소
    @Column(name = "min_capacity", nullable = false)
    private int minCapacity;
    //최대 인원
    @Column(nullable = false)
    private int capacity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TableStatus status;

    @Builder
    private StoreTable(Store store, int tableNumber, Integer minCapacity,int capacity) {
        int min=(minCapacity!=null) ? minCapacity:DEFAULT_MIN_CAPACITY;
        validateCapacity(min, capacity);
        this.store = store;
        this.tableNumber = tableNumber;
        this.capacity = capacity;
        this.minCapacity=min;
        this.status = TableStatus.ACTIVE;
    }
    public void changeTableNumber(int tableNumber) {
        this.tableNumber = tableNumber;
    }

    public void changeCapacityRange(int minCapacity,int capacity) {
        validateCapacity(minCapacity,capacity);
        this.minCapacity=minCapacity;
        this.capacity = capacity;
    }

    public void changeStatus(TableStatus status) {
        this.status = status;
    }

    public boolean canSeat(int partySize)
    {
        return minCapacity <=partySize && partySize<=capacity;
    }

    private static void validateCapacity(int minCapacity, int capacity)
    {
        if(minCapacity <1 || minCapacity>capacity)
        {
            throw new BusinessException(ErrorCode.INVALID_TABLE_CAPACITY);
        }
    }

}
