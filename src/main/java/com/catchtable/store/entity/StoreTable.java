package com.catchtable.store.entity;


import com.catchtable.global.common.BaseTimeEntity;
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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "table_number", nullable = false)
    private int tableNumber;

    @Column(nullable = false)
    private int capacity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TableStatus status;

    @Builder
    private StoreTable(Store store, int tableNumber, int capacity) {
        this.store = store;
        this.tableNumber = tableNumber;
        this.capacity = capacity;
        this.status = TableStatus.ACTIVE;
    }
    public void changeTableNumber(int tableNumber) {
        this.tableNumber = tableNumber;
    }

    public void changeCapacity(int capacity) {
        this.capacity = capacity;
    }

    public void changeStatus(TableStatus status) {
        this.status = status;
    }

}
