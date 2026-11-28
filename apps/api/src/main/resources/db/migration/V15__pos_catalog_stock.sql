-- =====================================================================
-- V15: Vendocas PDV (paid plans, PRODUCT_CATALOG): product categories, product details and photo,
-- stock control with a movement ledger, sale discounts and sale cancellation.
-- =====================================================================

-- ---------- categories (were free text on products.category) ----------
CREATE TABLE product_categories (
    id               uuid PRIMARY KEY,
    organization_id  uuid         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name             varchar(100) NOT NULL,
    position         int          NOT NULL DEFAULT 0,
    created_at       timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_product_categories_name ON product_categories(organization_id, lower(name));

INSERT INTO product_categories (id, organization_id, name)
SELECT gen_random_uuid(), organization_id, min(trim(category)) FROM products
WHERE category IS NOT NULL AND trim(category) <> '' GROUP BY organization_id, lower(trim(category));

ALTER TABLE products ADD COLUMN category_id uuid REFERENCES product_categories(id) ON DELETE SET NULL;
UPDATE products p SET category_id = c.id FROM product_categories c
 WHERE c.organization_id = p.organization_id AND p.category IS NOT NULL AND lower(c.name) = lower(trim(p.category));

-- ---------- product details ----------
ALTER TABLE products
    ADD COLUMN description    varchar(1000),
    ADD COLUMN cost_minor     bigint CHECK (cost_minor >= 0),            -- purchase cost → margin and stock value
    ADD COLUMN barcode        varchar(50),                               -- EAN/UPC, read by a scanner at the counter
    ADD COLUMN unit           varchar(10) NOT NULL DEFAULT 'UN' CHECK (unit IN ('UN','KG','G','L','ML','M','CX','PCT','PAR','SERV')),
    ADD COLUMN track_stock    boolean NOT NULL DEFAULT false,
    ADD COLUMN stock_quantity int     NOT NULL DEFAULT 0,                -- may go negative: the counter is never blocked
    ADD COLUMN min_stock      int CHECK (min_stock >= 0),                -- low-stock alert threshold
    ADD COLUMN photo_id       uuid REFERENCES media_assets(id) ON DELETE SET NULL;
CREATE UNIQUE INDEX ux_products_barcode ON products(organization_id, barcode) WHERE barcode IS NOT NULL;
CREATE INDEX ix_products_category ON products(organization_id, category_id);

-- Product photos live in the private bucket under each store's own prefix (org/{id}/products/...).
ALTER TABLE media_assets DROP CONSTRAINT media_assets_purpose_check;
ALTER TABLE media_assets ADD CONSTRAINT media_assets_purpose_check CHECK (purpose IN ('LOGO','CAMPAIGN','ATTACHMENT','DOCUMENT','PRODUCT'));

-- ---------- stock ledger: every change of products.stock_quantity has a row ----------
CREATE TABLE stock_movements (
    id               uuid PRIMARY KEY,
    organization_id  uuid         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    product_id       uuid         NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    kind             varchar(20)  NOT NULL CHECK (kind IN ('INITIAL','PURCHASE','SALE','SALE_CANCELED','COUNT','LOSS')),
    quantity         int          NOT NULL,                              -- signed delta
    balance_after    int          NOT NULL,
    unit_cost_minor  bigint CHECK (unit_cost_minor >= 0),
    order_id         uuid REFERENCES orders(id) ON DELETE SET NULL,
    reason           varchar(300),
    created_by       uuid REFERENCES users(id),
    created_at       timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_stock_movements_product ON stock_movements(product_id, created_at DESC);
CREATE INDEX ix_stock_movements_org ON stock_movements(organization_id, created_at DESC);

-- ---------- sales ----------
ALTER TABLE orders
    ADD COLUMN discount_minor bigint NOT NULL DEFAULT 0 CHECK (discount_minor >= 0),
    ADD COLUMN canceled_at    timestamptz,
    ADD COLUMN canceled_by    uuid REFERENCES users(id),
    ADD COLUMN cancel_reason  varchar(300);
ALTER TABLE order_items ADD COLUMN unit_cost_minor bigint CHECK (unit_cost_minor >= 0);  -- cost at sale time → margin
CREATE INDEX ix_orders_sales ON orders(organization_id, occurred_at DESC) WHERE sales_channel IS NOT NULL;
