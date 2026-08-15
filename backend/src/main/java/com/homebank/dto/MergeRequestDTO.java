package com.homebank.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class MergeRequestDTO {

    /** Identifiants des lignes a fusionner et supprimer (peut inclure ou non targetId, ignore si present) */
    private List<Long> sourceIds;

    /** Identifiant de la ligne qui recevra toutes les operations des sources et qui sera conservee */
    private Long targetId;
}
