package com.homebank.controller;

import com.homebank.dto.CategorieDTO;
import com.homebank.dto.DemandeFusionDTO;
import com.homebank.mapper.MappeurCategorie;
import com.homebank.service.ServiceCategorie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class ControleurCategorie {

    private final ServiceCategorie categoryService;
    private final MappeurCategorie categoryMapper;

    public ControleurCategorie(ServiceCategorie categoryService, MappeurCategorie categoryMapper) {
        this.categoryService = categoryService;
        this.categoryMapper = categoryMapper;
    }

    @GetMapping
    public List<CategorieDTO> findAll() {
        return categoryService.findAll().stream()
                .map(categoryMapper::toDTO)
                .toList();
    }

    @GetMapping("/{id}")
    public CategorieDTO findById(@PathVariable Long id) {
        return categoryMapper.toDTO(categoryService.findById(id));
    }

    @PostMapping
    public ResponseEntity<CategorieDTO> create(@RequestBody CategorieDTO payload) {
        return ResponseEntity.ok(categoryMapper.toDTO(categoryService.create(payload)));
    }

    @PutMapping("/{id}")
    public CategorieDTO update(@PathVariable Long id, @RequestBody CategorieDTO payload) {
        return categoryMapper.toDTO(categoryService.update(id, payload));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/toggle-hidden")
    public CategorieDTO toggleHidden(@PathVariable Long id) {
        return categoryMapper.toDTO(categoryService.toggleHidden(id));
    }

    /**
     * Fusionne plusieurs categories dans une categorie cible : toutes les
     * transactions et sous-categories des categories source sont reaffectees
     * a la cible, puis les categories source sont supprimees. Operation atomique.
     */
    @PostMapping("/merge")
    public CategorieDTO merge(@RequestBody DemandeFusionDTO request) {
        return categoryMapper.toDTO(categoryService.merge(request.getSourceIds(), request.getTargetId()));
    }
}
