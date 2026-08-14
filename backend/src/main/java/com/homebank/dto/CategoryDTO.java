package com.homebank.dto;

import com.homebank.model.Category.CategoryType;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class CategoryDTO {

    private Long id;
    private String name;
    private Long parentId;
    private CategoryType type;
}