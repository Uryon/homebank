package com.homebank.repository;

import com.homebank.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findByParentIsNull();
    List<Category> findByParent(Category parent);
    Optional<Category> findByNameIgnoreCaseAndParent(String name, Category parent);
    Optional<Category> findByNameIgnoreCaseAndParentIsNull(String name);
}
