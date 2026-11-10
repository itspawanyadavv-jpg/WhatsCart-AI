package com.drc.whatsappbot.repository;

import com.drc.whatsappbot.model.CatalogProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CatalogProductRepository extends JpaRepository<CatalogProduct, Long> {
    Optional<CatalogProduct> findByExternalDrcId(Long externalDrcId);
    Optional<CatalogProduct> findByNameIgnoreCase(String name);
    List<CatalogProduct> findBySource(String source);
}

