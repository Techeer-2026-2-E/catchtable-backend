package com.catchtable.store.entity;


import com.catchtable.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name="menu")
@Getter
@NoArgsConstructor(access= AccessLevel.PROTECTED)
public class Menu extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name="store_id",nullable = false)
    private Store store;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int price;

    @Column(length = 1000)
    private String description;

    @Builder
    private Menu(Store store, String name, int price, String description) {
        this.store = store;
        this.name = name;
        this.price = price;
        this.description = emptyToNull(description);
    }
    public void changeInfo(String name, Integer price, String description)
    {
        if(name!=null)
        {
            this.name=name;
        }
        if (price != null) this.price = price;
        if (description != null) this.description = emptyToNull(description);
    }

    private static String emptyToNull(String value)
    {
        return (value ==null || value.isBlank()) ? null : value;
    }
}
