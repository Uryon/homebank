package com.homebank.controller;

import com.homebank.dto.CategoryDTO;
import com.homebank.dto.MergeRequestDTO;
import com.homebank.mapper.CategoryMapper;
import com.homebank.model.Category;
import com.homebank.service.CategoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;
    private final CategoryMapper categoryMapper;

    public CategoryController(CategoryService categoryService, CategoryMapper categoryMapper) {
        this.categoryService = categoryService;
        this.categoryMapper = categoryMapper;
    }

    @GetMapping
    public List<CategoryDTO> findAll() {
        return categoryService.findAll().stream()
                .map(categoryMapper::toDTO)
                .toList();
    }

    @GetMapping("/roots")
    public List<CategoryDTO> findRoots() {
        return categoryService.findRoots().stream()
                .map(categoryMapper::toDTO)
                .toList();
    }

    @GetMapping("/{id}")
    public CategoryDTO findById(@PathVariable Long id) {
        return categoryMapper.toDTO(categoryService.findById(id));
    }

    @PostMapping
    public ResponseEntity<CategoryDTO> create(@RequestBody Category category) {
        return ResponseEntity.ok(categoryMapper.toDTO(categoryService.create(category)));
    }

    @PutMapping("/{id}")
    public CategoryDTO update(@PathVariable Long id, @RequestBody Category category) {
        return categoryMapper.toDTO(categoryService.update(id, category));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Fusionne plusieurs categories dans une categorie cible : toutes les
     * transactions et sous-categories des categories source sont reaffectees
     * a la cible, puis les categories source sont supprimees. Operation atomique.
     */
    @PostMapping("/merge")
    public CategoryDTO merge(@RequestBody MergeRequestDTO request) {
        return categoryMapper.toDTO(categoryService.merge(request.getSourceIds(), request.getTargetId()));
    }
}
