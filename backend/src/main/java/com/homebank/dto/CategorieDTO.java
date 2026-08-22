package com.homebank.dto;

import com.homebank.model.Categorie.TypeCategorie;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class CategorieDTO {

    private Long id;
    private String name;
    private Long parentId;
    private TypeCategorie type;
}