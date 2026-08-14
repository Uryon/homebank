package com.homebank.mapper;

import com.homebank.dto.CategoryDTO;
import com.homebank.model.Category;
import org.springframework.stereotype.Component;

@Component
public class CategoryMapper {

    public CategoryDTO toDTO(Category category) {
        return CategoryDTO.builder()
                .id(category.getId())
                .name(category.getName())
                .parentId(category.getParent() != null
                        ? category.getParent().getId()
                        : null)
                .type(category.getType())
                .build();
    }
}