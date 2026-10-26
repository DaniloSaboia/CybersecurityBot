package com.sentinel.kms.kb;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Knowledge-base collection, e.g. id="threat-intel", abbr="TI".
 * {@code docCount} is the headline number shown on the category chip.
 */
@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
public class Category {

    @Id
    private String id;

    private String label;

    private String abbr;

    @Column(name = "doc_count")
    private int docCount;

    @Column(name = "sort_order")
    private int sortOrder;
}
