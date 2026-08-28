package com.homebank.mapper;

import com.homebank.dto.CategorieDTO;
import com.homebank.model.Categorie;
import org.springframework.stereotype.Component;

@Component
public class MappeurCategorie {

    public CategorieDTO toDTO(Categorie category) {
        return CategorieDTO.builder()
                .id(category.getId())
                .name(category.getName())
                .parentId(category.getParent() != null
                        ? category.getParent().getId()
                        : null)
                .type(category.getType())
                .hidden(category.isHidden())
                .build();
    }
}