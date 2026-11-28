package com.platform.sales;

import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Catalogue and stock of the Vendocas PDV: categories, products (with photo) and stock movements. Plan feature PRODUCT_CATALOG. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "sales")
public class CatalogController {
    private final ProductService products;

    public CatalogController(ProductService products) {
        this.products = products;
    }

    // ---- categories
    @GetMapping("/product-categories")
    @RequiresPermission(Permission.CONTACTS_READ)
    public List<ProductService.CategoryView> categories() {
        return products.categories(TenantContext.current().orgId());
    }

    @PostMapping("/product-categories")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public Map<String, UUID> createCategory(@Valid @RequestBody ProductService.CategoryInput in) {
        var t = TenantContext.current();
        return Map.of("id", products.saveCategory(t.orgId(), t.userId(), null, in));
    }

    @PutMapping("/product-categories/{id}")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public Map<String, UUID> updateCategory(@PathVariable UUID id, @Valid @RequestBody ProductService.CategoryInput in) {
        var t = TenantContext.current();
        return Map.of("id", products.saveCategory(t.orgId(), t.userId(), id, in));
    }

    @DeleteMapping("/product-categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public void deleteCategory(@PathVariable UUID id) {
        var t = TenantContext.current();
        products.deleteCategory(t.orgId(), t.userId(), id);
    }

    // ---- products
    /** Search by name, SKU or exact barcode (what a scanner types). */
    @GetMapping("/products")
    @RequiresPermission(Permission.CONTACTS_READ)
    public List<ProductService.ProductView> list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean includeInactive,
                                                 @RequestParam(required = false) UUID categoryId, @RequestParam(defaultValue = "false") boolean lowStock) {
        return products.products(TenantContext.current().orgId(), q == null || q.isBlank() ? null : q, includeInactive, categoryId, lowStock);
    }

    @GetMapping("/products/{id}")
    @RequiresPermission(Permission.CONTACTS_READ)
    public ProductService.ProductView get(@PathVariable UUID id) {
        return products.product(TenantContext.current().orgId(), id);
    }

    @PostMapping("/products")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public Map<String, UUID> create(@Valid @RequestBody ProductService.ProductInput in) {
        var t = TenantContext.current();
        return Map.of("id", products.saveProduct(t.orgId(), t.userId(), null, in));
    }

    @PutMapping("/products/{id}")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public Map<String, UUID> update(@PathVariable UUID id, @Valid @RequestBody ProductService.ProductInput in) {
        var t = TenantContext.current();
        return Map.of("id", products.saveProduct(t.orgId(), t.userId(), id, in));
    }

    /** Product photo (JPEG, PNG or WEBP up to 5 MB), stored in the private bucket under the store's own folder. */
    @PostMapping(value = "/products/{id}/photo", consumes = "multipart/form-data")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public ProductService.ProductView photo(@PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
        var t = TenantContext.current();
        return products.setPhoto(t.orgId(), t.userId(), id, file.getBytes(), file.getOriginalFilename());
    }

    @DeleteMapping("/products/{id}/photo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public void removePhoto(@PathVariable UUID id) {
        var t = TenantContext.current();
        products.removePhoto(t.orgId(), t.userId(), id);
    }

    // ---- stock
    @PostMapping("/products/{id}/stock")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public ProductService.ProductView stock(@PathVariable UUID id, @Valid @RequestBody ProductService.StockInput in) {
        var t = TenantContext.current();
        return products.adjustStock(t.orgId(), t.userId(), id, in);
    }

    @GetMapping("/stock/movements")
    @RequiresPermission(Permission.CONTACTS_READ)
    public List<ProductService.MovementView> movements(@RequestParam(required = false) UUID productId, @RequestParam(defaultValue = "100") int limit) {
        return products.movements(TenantContext.current().orgId(), productId, limit);
    }

    @GetMapping("/stock/summary")
    @RequiresPermission(Permission.REPORTS_READ)
    public ProductService.StockSummary summary() {
        return products.stockSummary(TenantContext.current().orgId());
    }
}
